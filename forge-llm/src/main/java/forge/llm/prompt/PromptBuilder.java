package forge.llm.prompt;

import java.util.List;

import forge.game.Game;
import forge.game.player.Player;
import forge.llm.action.ActionSet;
import forge.llm.action.GameAction;
import forge.llm.action.UnavailableAction;
import forge.llm.agent.DecisionKind;
import forge.llm.context.Memory;
import forge.llm.context.SessionState;
import forge.llm.state.DeckSummary;
import forge.llm.state.GameStateRenderer;
import forge.llm.state.MatchLogView;

/**
 * Renders agent prompts. The first prompt of a session carries the complete context (standing
 * instructions, memory from earlier turns, decklist, the whole match log, full board); every later prompt
 * of the same session only carries what changed (new log lines, current board, fresh option list).
 */
public final class PromptBuilder {
    private final GameStateRenderer renderer = new GameStateRenderer();
    private final int maxLogLines;

    public PromptBuilder(int maxLogLines) {
        this.maxLogLines = maxLogLines;
    }

    /** Log lines kept in a compact full prompt (see {@link #full}). */
    static final int COMPACT_LOG_LINES = 40;

    /**
     * Prompt for the first decision of a session. Advances the session's log cursor. The standing instructions
     * ({@link SystemPrompt#TEXT}) are not part of it: the agent supplies them as its system prompt.
     *
     * @param compact shortened variant for a model whose context window overflowed: only the most recent log
     *                lines and no library listing
     */
    public String full(Player me, DecisionKind kind, ActionSet set, Memory memory, SessionState session,
                       String headline, boolean compact) {
        final Game game = me.getGame();
        final StringBuilder sb = new StringBuilder();

        sb.append("# GAME\n");
        sb.append("You are ").append(me.getName()).append(". Format: ").append(game.getRules().getGameType()).append(". ");
        sb.append("Opponents: ");
        List<String> opponents = me.getOpponents().stream().map(Player::getName).toList();
        sb.append(String.join(", ", opponents)).append(".\n");

        sb.append("\n# MEMORY\n");
        sb.append("## Your previous turn (compressed)\n")
                .append(blankAs(memory.previousTurnSummary(), "(none - this is your first turn)")).append('\n');
        sb.append("## Your instant-speed activity during the opponents' turns since then (compressed)\n")
                .append(blankAs(memory.instantSummary(), "(none)")).append('\n');

        sb.append("\n# YOUR DECK\n").append(DeckSummary.decklist(me)).append('\n');
        if (!compact) {
            sb.append("\n## Cards still in your library\n").append(DeckSummary.libraryComposition(me)).append('\n');
        }

        final int logLines = compact ? (maxLogLines > 0 ? Math.min(maxLogLines, COMPACT_LOG_LINES) : COMPACT_LOG_LINES) : maxLogLines;
        sb.append(compact ? "\n# MATCH LOG (only the most recent events; the context window is small)\n"
                        : "\n# MATCH LOG (everything that has happened so far)\n")
                .append(MatchLogView.render(MatchLogView.since(game, 0), logLines)).append('\n');

        appendBoardAndDecision(sb, me, kind, set, session, headline, "\n# CURRENT STATE\n");
        session.promptBuilt(MatchLogView.cursor(game));
        return sb.toString();
    }

    /** Prompt for a later decision in an already-open session. Advances the session's log cursor. */
    public String delta(Player me, DecisionKind kind, ActionSet set, SessionState session, String headline) {
        final Game game = me.getGame();
        final StringBuilder sb = new StringBuilder();
        sb.append("# UPDATE (decision ").append(session.decisionNumber()).append(" of this session)\n");
        sb.append("## What happened since your last decision\n")
                .append(MatchLogView.render(MatchLogView.since(game, session.logCursor()), maxLogLines)).append('\n');
        appendBoardAndDecision(sb, me, kind, set, session, headline, "\n## Current state\n");
        session.promptBuilt(MatchLogView.cursor(game));
        return sb.toString();
    }

    /** A short reminder appended on retry when the previous answer was rejected. */
    public String retry(String problem, ActionSet set) {
        StringBuilder sb = new StringBuilder();
        sb.append("# YOUR PREVIOUS ANSWER WAS REJECTED\n").append(problem).append("\n\n");
        appendOptions(sb, set);
        return sb.toString();
    }

    private void appendBoardAndDecision(StringBuilder sb, Player me, DecisionKind kind, ActionSet set,
                                        SessionState session, String headline, String boardHeading) {
        GameStateRenderer.Rendered rendered = renderer.render(me);
        sb.append(boardHeading).append(rendered.board()).append('\n');

        String reference = renderer.cardReference(rendered.mentioned(), session.cardTextSent());
        if (!reference.isEmpty()) {
            sb.append("\n## Card text (cards not shown before in this session)\n").append(reference).append('\n');
        }

        sb.append("\n# DECISION\n").append(headline).append("\n\n");
        appendOptions(sb, set);
    }

    private void appendOptions(StringBuilder sb, ActionSet set) {
        sb.append("## AVAILABLE ACTIONS (choose from these ids only)\n");
        for (GameAction a : set.actions()) {
            sb.append(a.toPromptLine()).append('\n');
        }
        if (!set.unavailable().isEmpty()) {
            sb.append("\n## CANNOT DO RIGHT NOW (do not attempt)\n");
            for (UnavailableAction u : set.unavailable()) {
                sb.append("- ").append(u.toPromptLine()).append('\n');
            }
        }
    }

    private static String blankAs(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
