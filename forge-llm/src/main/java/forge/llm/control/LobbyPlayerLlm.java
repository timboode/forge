package forge.llm.control;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.TimeLimitedAgent;

/**
 * The lobby-level identity of an LLM-controlled player. Being a {@link LobbyPlayerAi} keeps every part of
 * Forge that special-cases AI players (profiles, AI-only code paths) working unchanged; the only difference
 * is the controller of the in-game player.
 */
public class LobbyPlayerLlm extends LobbyPlayerAi {
    private final DecisionAgent agent;
    private final LlmPlayerConfig config;
    private final LlmStats stats = new LlmStats();

    /** The game currently being played by this lobby player, to recognise AI-simulation copies of it. */
    private volatile Game liveGame;

    public LobbyPlayerLlm(String name, DecisionAgent agent, LlmPlayerConfig config) {
        super(name, null);
        this.agent = config.decisionTimeoutSeconds > 0 ? new TimeLimitedAgent(agent, config.decisionTimeoutSeconds) : agent;
        this.config = config;
        setAiProfile("Default");
    }

    public LlmStats stats() {
        return stats;
    }

    /**
     * Builds the in-game player with the LLM controller - except for simulation copies.
     *
     * Opposing AIs that simulate ("what if I do this?") copy the running game, and the copier keeps any
     * {@link LobbyPlayerAi} as it is. Such a copy is created while the real game is still in progress, so it
     * is recognised that way and handed to the parent, which gives it the plain AI controller: simulated
     * games must not talk to the model, subscribe to turn events, or mix up the real player's memory.
     *
     * (The parent's own setup cannot be reused for the real game because a player's first controller can be
     * assigned only once; the AI profile rotation it offers is irrelevant for an LLM seat.)
     */
    @Override
    public Player createIngamePlayer(Game game, int id) {
        if (liveGame != null && liveGame != game && !liveGame.isGameOver()) {
            return super.createIngamePlayer(game, id);
        }
        liveGame = game;
        final Player player = new Player(getName(), game, id);
        player.setFirstController(new PlayerControllerLlm(game, player, this, agent, config, stats));
        return player;
    }
}
