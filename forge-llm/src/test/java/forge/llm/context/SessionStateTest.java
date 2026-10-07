package forge.llm.context;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class SessionStateTest {

    @Test
    public void rollbackUndoesAPromptThatWasNeverDelivered() {
        SessionState s = new SessionState("k");
        assertTrue(s.isNew());

        s.checkpoint();
        s.cardTextSent().add("Lightning Bolt");
        s.promptBuilt(42);
        assertFalse(s.isNew());
        assertEquals(s.logCursor(), 42);

        s.rollback();
        assertTrue(s.isNew(), "the model never saw the first prompt, so the next one must be a full one again");
        assertEquals(s.logCursor(), 0);
        assertTrue(s.cardTextSent().isEmpty(), "card texts that were never delivered must be re-sent");
    }

    @Test
    public void aDeliveredPromptStays() {
        SessionState s = new SessionState("k");
        s.checkpoint();
        s.promptBuilt(10);
        s.checkpoint();
        s.cardTextSent().add("Island");
        s.promptBuilt(25);
        s.rollback();
        assertFalse(s.isNew());
        assertEquals(s.logCursor(), 10, "rolls back only the undelivered prompt");
        assertFalse(s.cardTextSent().contains("Island"));
    }
}
