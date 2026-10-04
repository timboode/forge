package forge.llm.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;

import org.testng.annotations.Test;

public class ChoiceParserTest {

    @Test
    public void singleAction() {
        AgentChoice c = ChoiceParser.parse("ACTION 7\nREASON: curve out");
        assertEquals(c.actionIds(), List.of(7));
        assertEquals(c.reasoning(), "curve out");
    }

    @Test
    public void multipleActionsWithSeparators() {
        assertEquals(ChoiceParser.parse("ACTIONS 3, 5,9").actionIds(), List.of(3, 5, 9));
        assertEquals(ChoiceParser.parse("actions: 4 6").actionIds(), List.of(4, 6));
    }

    @Test
    public void passAndNone() {
        assertEquals(ChoiceParser.parse("PASS").actionIds(), List.of(0));
        assertTrue(ChoiceParser.parse("none\nREASON: no good attacks").actionIds().isEmpty());
    }

    @Test
    public void surroundingChatterIsIgnored() {
        AgentChoice c = ChoiceParser.parse("Let me think.\n\nACTION 2\nREASON: it kills their best creature.");
        assertEquals(c.actionIds(), List.of(2));
        assertEquals(c.reasoning(), "it kills their best creature.");
    }

    @Test
    public void numbersOnTheFollowingLineAreNotActionIds() {
        assertEquals(ChoiceParser.parse("ACTION 2\n3 damage to the face").actionIds(), List.of(2));
    }

    @Test
    public void adjacentBracketedIdsStaySeparate() {
        assertEquals(ChoiceParser.parse("ACTIONS [3][5]").actionIds(), List.of(3, 5));
    }

    @Test
    public void aReasonMayFollowTheChoiceOnTheSameLine() {
        AgentChoice c = ChoiceParser.parse("PASS [0] REASON: nothing worth doing");
        assertEquals(c.actionIds(), List.of(0));
        assertEquals(c.reasoning(), "nothing worth doing");
    }

    @Test
    public void garbageIsRejected() {
        expectThrows(IllegalArgumentException.class, () -> ChoiceParser.parse("I would like to attack with everything"));
        expectThrows(IllegalArgumentException.class, () -> ChoiceParser.parse(null));
    }
}
