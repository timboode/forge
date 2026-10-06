package forge.llm.opencode;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.ContextOverflowException;
import forge.llm.agent.DecisionKind;
import forge.llm.agent.SummaryRequest;

public class OpencodeAgentTest {
    private FakeOpencodeServer fake;
    private OpencodeSettings settings;
    private OpencodeAgent agent;

    @BeforeMethod
    public void start() throws IOException {
        fake = new FakeOpencodeServer();
        settings = new OpencodeSettings();
        settings.model = "lmstudio/google/gemma-4-e2b";
        settings.contextTokens = 16384;
        agent = new OpencodeAgent(fake.client(), settings, null);
    }

    @AfterMethod
    public void stop() {
        fake.close();
    }

    private static AgentRequest request(String key, boolean fresh, String prompt) {
        return new AgentRequest(key, fresh, DecisionKind.PRIORITY, prompt, List.of());
    }

    private void useContext(int tokens) {
        settings.contextTokens = tokens;
        agent = new OpencodeAgent(fake.client(), settings, null);
    }

    // ---- sessions and parsing --------------------------------------------------------------------------

    @Test
    public void aNewSessionOpensAConversationAndTheAnswerIsParsed() {
        fake.brain = m -> FakeOpencodeServer.Answer.text("ACTION [3]\nREASON: kill the bear", 500);

        AgentChoice choice = agent.decide(request("P-T1-own", true, "the full prompt"));

        assertEquals(choice.actionIds(), List.of(3));
        assertEquals(choice.reasoning(), "kill the bear");
        assertEquals(fake.openSessions.values().iterator().next(), "P-T1-own", "the session is named after the forge-llm session key");
        FakeOpencodeServer.Message sent = fake.messages.get(0);
        assertEquals(sent.text(), "the full prompt");
        assertEquals(sent.agent(), OpencodeSettings.AGENT_NAME, "the lean forge-player agent, not a stock coding agent");
        assertEquals(sent.providerId(), "lmstudio");
        assertEquals(sent.modelId(), "google/gemma-4-e2b", "model ids may contain slashes");
    }

    @Test
    public void laterRequestsOfASessionContinueTheSameOpencodeConversation() {
        agent.decide(request("P-T1-own", true, "full"));
        agent.decide(request("P-T1-own", false, "update"));

        assertEquals(fake.openSessions.size(), 1, "one conversation for the whole turn");
        assertEquals(fake.messages.get(0).sessionId(), fake.messages.get(1).sessionId());
        assertEquals(fake.messages.get(1).text(), "update");
    }

    @Test
    public void aNewSessionWithTheSameKeyReplacesTheOldConversation() {
        agent.decide(request("K", true, "one"));
        agent.decide(request("K", true, "two"));

        assertEquals(fake.deletedSessions.size(), 1);
        assertEquals(fake.openSessions.size(), 1);
    }

    @Test
    public void anAnswerSpreadOverSeveralTextPartsIsStillReadable() {
        // text, a tool call, then the real answer: the parts must not run together into "Let me check.ACTION 3"
        fake.brain = m -> FakeOpencodeServer.Answer.parts(900, "Let me check that card.", "ACTION 3\nREASON: it dies to my burn");
        AgentChoice choice = agent.decide(request("S", true, "full"));
        assertEquals(choice.actionIds(), List.of(3));
        assertEquals(choice.reasoning(), "it dies to my burn");
        assertEquals(fake.messages.size(), 1, "no format reminder was needed");
    }

    @Test
    public void aReasonOnTheSameLineAsTheChoiceIsKept() {
        fake.brain = m -> FakeOpencodeServer.Answer.text("PASS [0] REASON: nothing to do this step", 300);
        AgentChoice choice = agent.decide(request("S", true, "full"));
        assertEquals(choice.actionIds(), List.of(0));
        assertEquals(choice.reasoning(), "nothing to do this step");
    }

    @Test
    public void anUnreadableReplyGetsOneFormatReminderAndAnUnreadableSecondReplyIsGivenUp() {
        fake.brain = m -> FakeOpencodeServer.Answer.text(m.text().equals(OpencodeAgent.FORMAT_REMINDER)
                ? "ACTION 2" : "I think we should attack", 300);
        assertEquals(agent.decide(request("A", true, "full")).actionIds(), List.of(2));
        assertEquals(fake.messages.size(), 2, "prompt + reminder");

        fake.brain = m -> FakeOpencodeServer.Answer.text("still chatting", 300);
        assertNull(agent.decide(request("B", true, "full")), "null = the controller asks again or lets the AI decide");
    }

    @Test
    public void usedToolsAreNotMistakenForTheAnswer() {
        fake.brain = m -> FakeOpencodeServer.Answer.text("ACTION 4", 600).withTools("mtgcards_lookupCard");
        assertEquals(agent.decide(request("S", true, "full")).actionIds(), List.of(4));
    }

    // ---- context window --------------------------------------------------------------------------------

    @Test
    public void aRequestThatFitsIsSentAndOneThatCannotIsNot() {
        useContext(8000);

        agent.decide(request("small", true, "a short prompt"));
        assertEquals(fake.messages.size(), 1, "positive control: with room to spare the request goes out");

        assertThrows(ContextOverflowException.class, () -> agent.decide(request("huge", true, "x".repeat(40_000))));
        assertEquals(fake.messages.size(), 1, "the oversized prompt was never sent to the model");
    }

    @Test
    public void aGrowingConversationOverflowsBeforeTheRequestThatWouldNotFit() {
        useContext(8000);
        fake.brain = m -> FakeOpencodeServer.Answer.text("ACTION 1", 3800); // the conversation is now 3800 tokens long

        agent.decide(request("S", true, "short"));
        String sessionId = fake.messages.get(0).sessionId();

        agent.decide(request("S", false, "z".repeat(500)));
        assertEquals(fake.messages.size(), 2, "positive control: a small update still fits");

        assertThrows(ContextOverflowException.class, () -> agent.decide(request("S", false, "y".repeat(8_000))));
        assertEquals(fake.messages.size(), 2, "the oversized update was not sent");
        assertTrue(fake.deletedSessions.contains(sessionId), "the overflowed conversation is discarded");
    }

    @Test
    public void theReportedConversationSizeIsTrustedNotInflatedByEstimates() {
        useContext(8000);
        // each reply says the whole conversation is only 1500 tokens; estimates would add up to far more
        fake.brain = m -> FakeOpencodeServer.Answer.text("ACTION 1", 1500);
        for (int i = 0; i < 8; i++) {
            agent.decide(request("S", i == 0, "u".repeat(3000))); // ~940 tokens each: summed estimates would overflow by the 5th
        }
        assertEquals(fake.messages.size(), 8, "no premature overflow");
    }

    @Test
    public void whenNoTotalIsReportedCachedTokensStillCountTowardsTheConversation() {
        useContext(8000);
        // input 1000 + output 20 + 3400 served from cache = 4420 tokens already in the window
        fake.brain = m -> FakeOpencodeServer.Answer.withoutTotal("ACTION 1", 1000, 3400);
        agent.decide(request("S", true, "short"));
        assertThrows(ContextOverflowException.class, () -> agent.decide(request("S", false, "y".repeat(3_000))));
    }

    @Test
    public void anErrorFromTheModelAboutItsContextBecomesAnOverflow() {
        fake.brain = m -> FakeOpencodeServer.Answer.error("request exceeds the available context size (maximum context length is 4096)");
        assertThrows(ContextOverflowException.class, () -> agent.decide(request("S", true, "full")));
        assertEquals(fake.deletedSessions.size(), 1);
    }

    @Test
    public void opencodesOwnOverflowClassificationIsHonouredWhateverTheWording() {
        fake.brain = m -> FakeOpencodeServer.Answer.error("ContextOverflowError", "input token count exceeds the limit");
        assertThrows(ContextOverflowException.class, () -> agent.decide(request("S", true, "full")));
    }

    @Test
    public void otherModelErrorsAreReportedAsFailures() {
        fake.brain = m -> FakeOpencodeServer.Answer.error("rate limited");
        OpencodeException e = null;
        try {
            agent.decide(request("S", true, "full"));
        } catch (OpencodeException caught) {
            e = caught;
        }
        assertNotNull(e, "expected an OpencodeException");
        assertTrue(e.getMessage().contains("rate limited"), e.getMessage());
    }

    @Test
    public void aLaterRequestForAnUnknownSessionAsksForTheFullContext() {
        assertThrows(ContextOverflowException.class, () -> agent.decide(request("never-opened", false, "update")));
    }

    @Test
    public void theContextWindowIsReadFromOpencodeWhenNotConfigured() {
        fake.providerContextLimit = 32768;
        settings.contextTokens = 0;
        assertEquals(new OpencodeAgent(fake.client(), settings, null).contextTokens(), 32768);
    }

    // ---- lifecycle and summaries -----------------------------------------------------------------------

    @Test
    public void endingASessionDeletesItsConversationUnlessKeepingSessionsWasAsked() {
        agent.decide(request("S", true, "full"));
        agent.endSession("S");
        assertEquals(fake.openSessions.size(), 0);

        settings.keepSessions = true;
        agent = new OpencodeAgent(fake.client(), settings, null);
        agent.decide(request("T", true, "full"));
        agent.endSession("T");
        assertEquals(fake.openSessions.size(), 1, "kept for inspection");
    }

    @Test
    public void summarizingUsesTheToollessSummarizerAgentInAThrowawaySessionAndCarriesThePriorSummary() {
        fake.brain = m -> FakeOpencodeServer.Answer.text("Plan: race in the air.", 400);

        String summary = agent.summarize(new SummaryRequest(SummaryRequest.Kind.TURN, "Alice",
                "T3 Main: Cast Air Elemental (reason: evasion)", "Earlier: opponent is white weenie."));

        assertEquals(summary, "Plan: race in the air.");
        FakeOpencodeServer.Message sent = fake.messages.get(0);
        assertEquals(sent.agent(), OpencodeSettings.SUMMARIZER_NAME, "not the player agent, whose instructions say to answer ACTION <id>");
        assertTrue(sent.text().contains("Earlier: opponent is white weenie."), sent.text());
        assertTrue(sent.text().contains("Cast Air Elemental"), sent.text());
        assertEquals(fake.openSessions.size(), 0, "the summary session is deleted");
    }

    @Test
    public void aHugeTranscriptIsTrimmedToItsTailSoTheSummaryRequestStillFits() {
        useContext(6000);
        String transcript = "OLDEST-LINE\n" + "filler line\n".repeat(5_000) + "NEWEST-LINE";

        agent.summarize(new SummaryRequest(SummaryRequest.Kind.INSTANT_ACTIONS, "Alice", transcript, ""));

        String prompt = fake.messages.get(0).text();
        assertTrue(prompt.contains("NEWEST-LINE"));
        assertFalse(prompt.contains("OLDEST-LINE"));
        assertTrue(OpencodeAgent.estimateTokens(prompt) < 6000 - settings.outputReserveTokens, "estimated " + OpencodeAgent.estimateTokens(prompt));
    }

    @Test
    public void aShortTranscriptWithAVerySmallContextStillSummarizesInsteadOfCrashing() {
        useContext(3000); // less room than the fixed overhead: the transcript budget must not go negative
        String summary = agent.summarize(new SummaryRequest(SummaryRequest.Kind.TURN, "Alice", "T1: played a land", ""));
        assertFalse(summary.isBlank());
    }

    // ---- server interaction ----------------------------------------------------------------------------

    @Test
    public void theClientAuthenticatesAndReportsHealth() {
        assertTrue(fake.client().isHealthy());
        assertFalse(new OpencodeClient(fake.url(), "opencode", "wrong").isHealthy());
        assertNotNull(fake.client().createSession("x"));
    }

    @Test
    public void anAbortReachesTheServerEvenFromAnInterruptedThread() throws InterruptedException {
        // This is the situation after a model call timed out: the calling thread has been interrupted, and the
        // JDK HTTP client would refuse to send anything at all unless the flag is cleared around the call.
        String id = fake.client().createSession("busy");
        java.util.concurrent.atomic.AtomicBoolean stillInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            fake.client().abortQuietly(id);
            fake.client().deleteSession(id);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(10_000);

        assertTrue(stillInterrupted.get(), "the interrupt must not be swallowed");

        assertEquals(fake.abortedSessions, List.of(id), "the abort request was actually sent");
        assertTrue(fake.deletedSessions.contains(id), "and so was the delete");
    }

    @Test
    public void attachingRequiresTheAgentsToBeConfiguredOnTheServer() {
        OpencodeSettings attach = new OpencodeSettings();
        attach.serverUrl = fake.url();
        attach.serverUsername = FakeOpencodeServer.USER;
        attach.serverPassword = FakeOpencodeServer.PASSWORD;
        attach.contextTokens = 16384;

        assertNotNull(OpencodeAgent.connect(attach));

        fake.agents = List.of("build", "plan");
        OpencodeException e = null;
        try {
            OpencodeAgent.connect(attach);
        } catch (OpencodeException caught) {
            e = caught;
        }
        assertNotNull(e, "a server without the forge-player agent would answer as a coding assistant");
        assertTrue(e.getMessage().contains(OpencodeSettings.AGENT_NAME), e.getMessage());
    }
}
