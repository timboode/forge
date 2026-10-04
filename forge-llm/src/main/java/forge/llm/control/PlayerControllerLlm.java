package forge.llm.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.tinylog.Logger;

import com.google.common.eventbus.Subscribe;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnEnded;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.llm.action.ActionExecutor;
import forge.llm.action.ActionSet;
import forge.llm.action.AttackAction;
import forge.llm.action.BlockAction;
import forge.llm.action.CombatActionEnumerator;
import forge.llm.action.GameAction;
import forge.llm.action.PassAction;
import forge.llm.action.PriorityAction;
import forge.llm.action.PriorityActionEnumerator;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.ContextOverflowException;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.DecisionKind;
import forge.llm.bridge.AiBridge;
import forge.llm.context.ContextManager;
import forge.llm.context.SessionState;
import forge.llm.prompt.PromptBuilder;

/**
 * A player controller that hands the three decisions that matter most - what to do with priority, whom to
 * attack with, and how to block - to a {@link DecisionAgent}, and leaves every other prompt the engine can
 * raise (mulligans, discards, scry, target re-picks, ...) to the built-in AI it extends.
 *
 * Any trouble (agent exception or timeout, repeated invalid answers, an action that cannot be executed)
 * degrades to the built-in AI's own decision, so a misbehaving model can slow a game down but never wedge it.
 */
public class PlayerControllerLlm extends PlayerControllerAi {
    private static final int MAX_REPLANS = 5;
    /** An action that fails this often in one turn is not offered again that turn. */
    private static final int MAX_FAILURES_PER_TURN = 2;

    private final DecisionAgent agent;
    private final LlmPlayerConfig cfg;
    private final LlmStats stats;
    private final ContextManager ctx;
    private final PromptBuilder prompts;
    private final PriorityActionEnumerator priorityEnum = new PriorityActionEnumerator();
    private final CombatActionEnumerator combatEnum = new CombatActionEnumerator();
    private final ActionExecutor executor;

    /** Actions that failed to execute in the current window; not offered again until the window changes. */
    private final Set<String> failedKeys = new HashSet<>();
    /** How often each action failed this turn. */
    private final Map<String, Integer> failuresThisTurn = new HashMap<>();
    private String failureWindow = "";
    private PriorityAction lastChosen;

    private int consultationsThisTurn;
    private boolean myTurnActive;
    /** Set when the turn's end event has been handled; the end event can fire twice if cleanup repeats. */
    private boolean turnClosed;
    private int reportedSummaries;

    public PlayerControllerLlm(Game game, Player p, LobbyPlayer lp, DecisionAgent agent, LlmPlayerConfig cfg,
                               LlmStats stats) {
        super(game, p, lp);
        this.agent = agent;
        this.cfg = cfg;
        this.stats = stats;
        this.ctx = new ContextManager(p.getName(), agent);
        this.prompts = new PromptBuilder(cfg.maxLogLines);
        this.executor = new ActionExecutor(p, getAi());
        game.subscribeToEvents(new TurnLifecycle());
    }

    public ContextManager context() {
        return ctx;
    }

    // ---- turn lifecycle (memory compression) -----------------------------------------------------------

    /** Reacts to turn boundaries to run the context-compression lifecycle. */
    public final class TurnLifecycle {
        @Subscribe
        public void onTurnBegan(GameEventTurnBegan event) {
            consultationsThisTurn = 0;
            turnClosed = false;
            failuresThisTurn.clear();
            myTurnActive = event.turnOwner().getId() == player.getId();
            if (myTurnActive) {
                guarded("compress instant-speed context", ctx::onOwnTurnBegan);
                countSummaries();
            }
        }

        @Subscribe
        public void onTurnEnded(GameEventTurnEnded event) {
            if (turnClosed) {
                return; // cleanup repeated (CR 514.3a) and the event fired again
            }
            turnClosed = true;
            if (myTurnActive) {
                guarded("compress turn", ctx::onOwnTurnEnded);
                countSummaries();
                myTurnActive = false;
            } else {
                guarded("close response session", ctx::onOtherTurnEnded);
            }
        }
    }

    private void countSummaries() {
        stats.summaries.addAndGet(ctx.summariesMade() - reportedSummaries);
        reportedSummaries = ctx.summariesMade();
    }

    private void guarded(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | StackOverflowError e) {
            Logger.warn(e, "LLM player {}: failed to {}", player.getName(), what);
        }
    }

    // ---- priority ---------------------------------------------------------------------------------------

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        try {
            return choosePriorityAction(0);
        } catch (RuntimeException | StackOverflowError e) {
            Logger.warn(e, "LLM player {}: priority decision failed, using the built-in AI", player.getName());
            stats.fallbacksToAi.incrementAndGet();
            return super.chooseSpellAbilityToPlay();
        }
    }

    private List<SpellAbility> choosePriorityAction(int replans) {
        final Game game = getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        lastChosen = null;
        if (turnClosed) {
            return null; // a repeated cleanup step: the turn has already been written up
        }
        final boolean ownTurn = ph.isPlayerTurn(player);
        refreshFailureWindow(game);
        AiBridge.beginDecision(getAi(), player);

        // Hard logic first: if the engine says there is nothing legal to do, nobody needs to be asked.
        final ActionSet set = priorityEnum.enumerate(player, excludedKeys());
        if (set.isEmpty()) {
            stats.autoPassedNoActions.incrementAndGet();
            return null;
        }

        final boolean stackEmpty = game.getStack().isEmpty();
        final boolean topMine = !stackEmpty && player.equals(game.getStack().peekAbility().getActivatingPlayer());
        final PriorityGate.Verdict verdict = PriorityGate.evaluate(
                new PriorityGate.Situation(ownTurn, ph.getPhase(), stackEmpty, topMine, consultationsThisTurn), cfg);
        if (!verdict.consult()) {
            stats.autoPassedByGate.incrementAndGet();
            return null;
        }

        final String headline = ownTurn
                ? "You have priority. Choose ONE action (id 0 passes)."
                : "An opponent's turn: you have priority and could respond. Choose ONE action (id 0 passes).";
        final AgentChoice choice = consult(DecisionKind.PRIORITY, set, headline, c -> validateSingle(set, c));
        if (choice == null) {
            stats.fallbacksToAi.incrementAndGet();
            return super.chooseSpellAbilityToPlay();
        }

        final GameAction action = set.byId(choice.actionIds().get(0));
        if (action instanceof PassAction) {
            record(ownTurn, ph, null, choice.reasoning());
            return null;
        }

        final PriorityAction chosen = (PriorityAction) action;
        final List<SpellAbility> prepared = executor.prepare(chosen);
        if (prepared == null) {
            Logger.warn("LLM player {}: could not set up '{}' (targets or X could not be resolved)", player.getName(), chosen.describe());
            markFailed(chosen);
            return replans < MAX_REPLANS ? choosePriorityAction(replans + 1) : null;
        }
        record(ownTurn, ph, chosen.describe(), choice.reasoning());
        lastChosen = chosen;
        return prepared;
    }

    /**
     * The built-in AI's {@code playChosenSpellAbility} always reports success, even when paying the costs
     * failed and the ability was put back, so success is judged by whether anything actually happened.
     */
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        final PriorityAction chosen = lastChosen;
        lastChosen = null;
        if (chosen == null) {
            return super.playChosenSpellAbility(sa); // not one of ours (built-in AI fallback)
        }
        final long before = signature(chosen.host());
        final int stackBefore = getGame().getStack().size();
        final int landsBefore = player.getLandsPlayedThisTurn();
        super.playChosenSpellAbility(sa);
        if (wasPlayed(sa, chosen, before, stackBefore, landsBefore)) {
            return true;
        }
        Logger.warn("LLM player {}: the engine did not play '{}'", player.getName(), chosen.describe());
        sa.setSkip(false);
        markFailed(chosen);
        return false;
    }

    /**
     * Lands count when the land drop was used; spells and activated abilities only when they reached the stack
     * (a spell whose casting fails midway can already have left the hand without ever being cast, so a changed
     * fingerprint alone is not proof). Anything else (special actions that do not use the stack) is judged by
     * the fingerprint.
     */
    private boolean wasPlayed(SpellAbility sa, PriorityAction chosen, long signatureBefore, int stackBefore, int landsBefore) {
        if (sa.isLandAbility()) {
            return player.getLandsPlayedThisTurn() > landsBefore;
        }
        if (sa.isSpell() || sa.isActivatedAbility()) {
            return getGame().getStack().size() > stackBefore;
        }
        return signature(chosen.host()) != signatureBefore;
    }

    /** Cheap fingerprint of the things playing a special action changes. */
    private long signature(Card host) {
        final Game game = getGame();
        long h = game.getStack().size();
        h = h * 31 + player.getLandsPlayedThisTurn();
        h = h * 31 + (host.getZone() == null ? -1 : host.getZone().getZoneType().ordinal());
        h = h * 31 + (host.isTapped() ? 1 : 0);
        h = h * 31 + player.getCardsIn(ZoneType.Hand).size();
        int tapped = 0;
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped()) {
                tapped++;
            }
        }
        h = h * 31 + tapped;
        return h;
    }

    private void markFailed(PriorityAction action) {
        failedKeys.add(action.key());
        failuresThisTurn.merge(action.key(), 1, Integer::sum);
        stats.failedExecutions.incrementAndGet();
    }

    private Set<String> excludedKeys() {
        final Set<String> excluded = new HashSet<>(failedKeys);
        failuresThisTurn.forEach((key, n) -> {
            if (n >= MAX_FAILURES_PER_TURN) {
                excluded.add(key);
            }
        });
        return excluded;
    }

    private void refreshFailureWindow(Game game) {
        final String window = game.getPhaseHandler().getTurn() + "|" + game.getPhaseHandler().getPhase() + "|"
                + game.getStack().size();
        if (!window.equals(failureWindow)) {
            failureWindow = window;
            failedKeys.clear();
        }
    }

    private static String validateSingle(ActionSet set, AgentChoice choice) {
        if (choice.actionIds().size() != 1) {
            return "Choose exactly one id (or PASS).";
        }
        int id = choice.actionIds().get(0);
        return set.byId(id) == null ? "Id " + id + " is not in the list of available actions." : null;
    }

    // ---- combat -----------------------------------------------------------------------------------------

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (!attacker.equals(player)) {
            super.declareAttackers(attacker, combat);
            return;
        }
        try {
            if (declareAttackersWithAgent(combat)) {
                return;
            }
        } catch (RuntimeException | StackOverflowError e) {
            Logger.warn(e, "LLM player {}: attack declaration failed, using the built-in AI", player.getName());
        }
        combat.clearAttackers();
        stats.fallbacksToAi.incrementAndGet();
        super.declareAttackers(attacker, combat);
    }

    private boolean declareAttackersWithAgent(Combat combat) {
        final ActionSet set = combatEnum.attacks(player, combat);
        if (set.isEmpty()) {
            return true; // nothing is able to attack: nothing to ask
        }
        if (consultationsThisTurn >= cfg.maxConsultationsPerTurn) {
            return false;
        }
        final AgentChoice choice = consult(DecisionKind.ATTACK, set,
                "Declare attackers. Choose any number of the 'Attack' options below (at most one per creature), "
                        + "or NONE to not attack. Creatures you leave out stay home as potential blockers.",
                c -> applyAttacks(set, c, combat));
        if (choice == null) {
            return false;
        }
        record(true, getGame().getPhaseHandler(), "Declared attackers: " + describeAll(set, choice), choice.reasoning());
        return true;
    }

    /** Applies the chosen attacks to the combat; returns a problem description if they are not acceptable. */
    private String applyAttacks(ActionSet set, AgentChoice choice, Combat combat) {
        final String problem = validateIds(set, choice);
        if (problem != null) {
            return problem;
        }
        final Set<Card> seen = new HashSet<>();
        final List<AttackAction> attacks = new ArrayList<>();
        for (int id : choice.actionIds()) {
            AttackAction a = (AttackAction) set.byId(id);
            if (!seen.add(a.attacker())) {
                return a.attacker().getName() + " (#" + a.attacker().getId() + ") is attacking more than once.";
            }
            attacks.add(a);
        }
        combat.clearAttackers();
        for (AttackAction a : attacks) {
            combat.addAttacker(a.attacker(), a.defender());
        }
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            return "That attack declaration breaks a rule (for example a creature that must attack was left out).";
        }
        return null;
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        if (!defender.equals(player)) {
            super.declareBlockers(defender, combat);
            return;
        }
        try {
            if (declareBlockersWithAgent(combat)) {
                return;
            }
        } catch (RuntimeException | StackOverflowError e) {
            Logger.warn(e, "LLM player {}: block declaration failed, using the built-in AI", player.getName());
            undoAllBlocks(combat);
        }
        stats.fallbacksToAi.incrementAndGet();
        super.declareBlockers(defender, combat);
    }

    private boolean declareBlockersWithAgent(Combat combat) {
        final ActionSet set = combatEnum.blocks(player, combat);
        if (set.isEmpty()) {
            return true; // nothing is able to block: nothing to ask
        }
        if (consultationsThisTurn >= cfg.maxConsultationsPerTurn) {
            return false;
        }
        final AgentChoice choice = consult(DecisionKind.BLOCK, set,
                "Declare blockers. Choose any number of the 'Block' options below (each of your creatures blocks "
                        + "at most one attacker; several creatures may gang-block one attacker), or NONE to not block.",
                c -> applyBlocks(set, c, combat));
        if (choice == null) {
            return false;
        }
        record(false, getGame().getPhaseHandler(), "Declared blockers: " + describeAll(set, choice), choice.reasoning());
        return true;
    }

    private String applyBlocks(ActionSet set, AgentChoice choice, Combat combat) {
        final String problem = validateIds(set, choice);
        if (problem != null) {
            return problem;
        }
        final Set<Card> usedBlockers = new HashSet<>();
        final List<BlockAction> blocks = new ArrayList<>();
        for (int id : choice.actionIds()) {
            BlockAction b = (BlockAction) set.byId(id);
            if (!usedBlockers.add(b.blocker())) {
                return b.blocker().getName() + " (#" + b.blocker().getId() + ") is blocking more than once.";
            }
            blocks.add(b);
        }
        // Add one at a time against the combat as it stands, so limits that depend on the other blocks
        // (e.g. "no more than one creature can block each combat") are enforced.
        final List<BlockAction> added = new ArrayList<>();
        for (BlockAction b : blocks) {
            if (!CombatUtil.canBlock(b.blocker(), combat) || !CombatUtil.canBlock(b.attacker(), b.blocker(), combat)) {
                undo(combat, added);
                return b.describe() + " is not allowed together with the other blocks you chose.";
            }
            combat.addBlocker(b.attacker(), b.blocker());
            added.add(b);
        }
        final String error = CombatUtil.validateBlocks(combat, player);
        if (error != null && !error.isEmpty()) {
            undo(combat, added);
            return "That block declaration breaks a rule: " + error;
        }
        return null;
    }

    private static void undo(Combat combat, List<BlockAction> added) {
        for (BlockAction b : added) {
            combat.removeBlockAssignment(b.attacker(), b.blocker());
        }
    }

    private void undoAllBlocks(Combat combat) {
        for (Card creature : player.getCreaturesInPlay()) {
            combat.undoBlockingAssignment(creature);
        }
    }

    private static String validateIds(ActionSet set, AgentChoice choice) {
        for (int id : choice.actionIds()) {
            if (set.byId(id) == null) {
                return "Id " + id + " is not in the list of available actions.";
            }
        }
        return null;
    }

    private static String describeAll(ActionSet set, AgentChoice choice) {
        final StringBuilder what = new StringBuilder();
        for (int id : choice.actionIds()) {
            what.append(what.length() == 0 ? "" : "; ").append(set.byId(id).describe());
        }
        return what.length() == 0 ? "none" : what.toString();
    }

    // ---- consulting the agent ---------------------------------------------------------------------------

    /**
     * Builds the prompt for this decision, asks the agent and validates the answer, re-asking up to
     * {@code maxRetries} times with the problem spelled out.
     *
     * If the agent reports that its context window overflowed, the conversation is thrown away and the decision
     * is put to a fresh session with a compact full prompt (once; if even that does not fit, the AI decides).
     *
     * @param validator returns null if the choice is acceptable (and may apply it), else a problem description
     * @return the accepted choice, or null if the agent failed or never produced an acceptable answer
     */
    private AgentChoice consult(DecisionKind kind, ActionSet set, String headline,
                                Function<AgentChoice, String> validator) {
        final PhaseHandler ph = getGame().getPhaseHandler();
        final boolean ownTurn = ph.isPlayerTurn(player);
        final String sessionKey = player.getName() + "-T" + ph.getTurn() + (ownTurn ? "-own" : "-resp");
        SessionState session = ctx.session(sessionKey);

        boolean compact = false;
        boolean fresh = session.isNew();
        session.checkpoint();
        String prompt = fresh
                ? prompts.full(player, kind, set, ctx.memory(), session, headline, false)
                : prompts.delta(player, kind, set, session, headline);

        consultationsThisTurn++;
        stats.consultations.incrementAndGet();

        int attempt = 0;
        while (attempt <= cfg.maxRetries) {
            final AgentChoice choice;
            try {
                choice = agent.decide(new AgentRequest(session.key(), fresh && attempt == 0, kind, prompt, set.actions()));
            } catch (ContextOverflowException e) {
                if (compact) {
                    Logger.warn("LLM player {}: even a compact prompt does not fit the model's context ({})", player.getName(), e.getMessage());
                    return null;
                }
                Logger.info("LLM player {}: context window overflow ({}); restarting the session with a compact prompt", player.getName(), e.getMessage());
                stats.contextRestarts.incrementAndGet();
                compact = true;
                session = ctx.restartSession(sessionKey);
                fresh = true;
                session.checkpoint();
                prompt = prompts.full(player, kind, set, ctx.memory(), session, headline, true);
                attempt = 0;
                continue;
            } catch (RuntimeException | StackOverflowError e) {
                Logger.warn(e, "LLM player {}: agent failed on a {} decision", player.getName(), kind);
                if (attempt == 0) {
                    session.rollback(); // the model never saw this prompt: the next one must carry the full context again
                }
                return null;
            }
            attempt++;
            if (choice == null) {
                prompt = prompts.retry("No answer was given.", set);
                stats.invalidAnswers.incrementAndGet();
                continue;
            }
            final String problem = validator.apply(choice);
            if (problem == null) {
                return choice;
            }
            stats.invalidAnswers.incrementAndGet();
            prompt = prompts.retry(problem, set);
        }
        return null;
    }

    private void record(boolean ownTurn, PhaseHandler ph, String what, String reasoning) {
        final boolean hasReason = reasoning != null && !reasoning.isBlank();
        if (what == null && !hasReason) {
            return; // an unexplained pass carries no information
        }
        final StringBuilder line = new StringBuilder("T").append(ph.getTurn()).append(' ').append(ph.getPhase().nameForUi)
                .append(": ").append(what == null ? "passed priority" : what);
        if (hasReason) {
            line.append("  (reason: ").append(reasoning.trim()).append(')');
        }
        ctx.record(ownTurn, line.toString());
    }
}
