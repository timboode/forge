package forge.llm.control;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.game.phase.PhaseType;
import forge.util.Localizer;

public class PriorityGateTest {
    private LlmPlayerConfig cfg;

    // PhaseType carries localized display names, so the Localizer must be loaded before the enum is touched
    @BeforeClass
    public void loadLocalizer() {
        Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
    }

    @BeforeMethod
    public void freshConfig() {
        cfg = new LlmPlayerConfig();
    }

    private boolean consult(boolean ownTurn, PhaseType phase, boolean stackEmpty, boolean topMine, int used) {
        return PriorityGate.evaluate(new PriorityGate.Situation(ownTurn, phase, stackEmpty, topMine, used), cfg).consult();
    }

    @Test
    public void ownTurnIsConsultedInEveryPhaseByDefault() {
        for (PhaseType phase : new PhaseType[] {PhaseType.UPKEEP, PhaseType.DRAW, PhaseType.MAIN1, PhaseType.COMBAT_BEGIN,
                PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.MAIN2, PhaseType.END_OF_TURN}) {
            assertTrue(consult(true, phase, true, false, 0), phase.toString());
        }
    }

    @Test
    public void ownSpellOnTheStackIsLeftToResolve() {
        assertFalse(consult(true, PhaseType.MAIN1, false, true, 0));
        cfg.autoPassOnOwnStackItem = false;
        assertTrue(consult(true, PhaseType.MAIN1, false, true, 0));
    }

    @Test
    public void opponentTurnOnlyWakesTheAgentForStackItemsAndKeyWindows() {
        assertTrue(consult(false, PhaseType.MAIN1, false, false, 0), "opponent spell on the stack");
        assertFalse(consult(false, PhaseType.MAIN1, true, false, 0), "opponent main phase, empty stack");
        assertFalse(consult(false, PhaseType.UPKEEP, true, false, 0));
        assertTrue(consult(false, PhaseType.COMBAT_DECLARE_ATTACKERS, true, false, 0));
        assertTrue(consult(false, PhaseType.COMBAT_DECLARE_BLOCKERS, true, false, 0));
        assertTrue(consult(false, PhaseType.END_OF_TURN, true, false, 0));
    }

    @Test
    public void respondingToOpponentCanBeDisabled() {
        cfg.respondToOpponentStackItems = false;
        assertFalse(consult(false, PhaseType.MAIN1, false, false, 0));
    }

    @Test
    public void budgetStopsConsultations() {
        assertTrue(consult(true, PhaseType.MAIN1, true, false, cfg.maxConsultationsPerTurn - 1));
        assertFalse(consult(true, PhaseType.MAIN1, true, false, cfg.maxConsultationsPerTurn));
    }
}
