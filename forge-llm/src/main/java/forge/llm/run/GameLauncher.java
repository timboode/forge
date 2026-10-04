package forge.llm.run;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.view.TimeLimitedCodeBlock;

/** Runs headless games the same way Forge's own "sim" mode does, with a wall-clock limit per game. */
public final class GameLauncher {
    private GameLauncher() {
    }

    public record Result(Game game, boolean timedOut, long millis) {
    }

    public static Match newMatch(List<RegisteredPlayer> players) {
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(java.util.EnumSet.of(GameType.Constructed));
        return new Match(rules, players, "LLM test");
    }

    /** Plays one game to the end (or until the time limit, which counts as a draw). */
    public static Result play(Match match, int timeoutSeconds) {
        final Game game = match.createGame();
        game.setNoGUIUser();
        final long start = System.currentTimeMillis();
        boolean timedOut = false;
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timedOut = true;
        } catch (Exception | StackOverflowError e) {
            e.printStackTrace();
        } finally {
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        }
        return new Result(game, timedOut, System.currentTimeMillis() - start);
    }
}
