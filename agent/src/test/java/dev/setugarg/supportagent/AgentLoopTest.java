package dev.setugarg.supportagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.setugarg.supportagent.agent.SupportAgent;
import dev.setugarg.supportagent.llm.Llm;
import dev.setugarg.supportagent.llm.LlmClient;
import dev.setugarg.supportagent.rag.KnowledgeBase;
import dev.setugarg.supportagent.store.InMemoryStores;
import dev.setugarg.supportagent.store.SeedData;
import dev.setugarg.supportagent.tools.Tools;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the orchestration loop with a scripted fake model: zero cost, fully deterministic.
 * Model QUALITY is measured by the eval harness, not by unit tests.
 */
class AgentLoopTest {

    static final ObjectMapper M = new ObjectMapper();

    /** Returns pre-baked responses in order and records every request it receives. */
    static final class ScriptedLlm implements LlmClient {
        final Deque<Llm.Response> script = new ArrayDeque<>();
        final List<Llm.Request> requests = new ArrayList<>();

        ScriptedLlm then(Llm.StopReason stop, Llm.Block... blocks) {
            script.add(new Llm.Response(new Llm.Message(Llm.Role.ASSISTANT, List.of(blocks), null), stop,
                    new Llm.Usage(1000, 100, 0, 0), 5, "claude-haiku-5-5"));
            return this;
        }

        @Override public Llm.Response complete(Llm.Request r) {
            requests.add(r);
            if (script.isEmpty()) throw new IllegalStateException("script exhausted");
            return script.poll();
        }

        @Override public String modelId() { return "claude-haiku-5-5"; }
    }

    SupportAgent agent(ScriptedLlm llm, InMemoryStores.Sessions sessions, int maxSteps) {
        var orders = new InMemoryStores.Orders(SeedData.orders(LocalDate.now()));
        return new SupportAgent(llm, Tools.all(KnowledgeBase.fromClasspath(), orders, new InMemoryStores.Tickets()),
                sessions, Clock.systemUTC(), maxSteps, 1024);
    }

    @Test
    void runsToolThenAnswers() throws Exception {
        ScriptedLlm llm = new ScriptedLlm()
                .then(Llm.StopReason.TOOL_USE, new Llm.ToolUse("tu_1", "get_order", M.readTree("{\"order_id\":\"A1002\"}")))
                .then(Llm.StopReason.END_TURN, new Llm.Text("Your bottle is on its way with USPS."));

        var r = agent(llm, new InMemoryStores.Sessions(), 6).run("sess-12345678", "maya@example.com", "Maya", "where is A1002?");

        assertEquals("Your bottle is on its way with USPS.", r.answer());
        assertEquals(List.of("get_order"), r.toolsCalled());
        assertEquals(2, r.modelCalls());
        // second request must carry the tool result back to the model
        var last = llm.requests.get(1).messages().getLast();
        assertInstanceOf(Llm.ToolResult.class, last.blocks().getFirst());
        assertTrue(((Llm.ToolResult) last.blocks().getFirst()).content().contains("SHIPPED"));
        assertTrue(r.costUsd() > 0);
    }

    @Test
    void stepBudgetStopsRunawayLoops() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < 10; i++) {
            llm.then(Llm.StopReason.TOOL_USE, new Llm.ToolUse("tu_" + i, "list_my_orders", M.readTree("{}")));
        }
        var r = agent(llm, new InMemoryStores.Sessions(), 3).run("sess-12345678", "maya@example.com", "Maya", "loop");
        assertEquals(3, r.modelCalls());
        assertEquals("max_steps", r.stopReason());
        assertFalse(r.answer().isBlank(), "user still gets a graceful answer");
    }

    @Test
    void unknownToolAndBadInputBecomeErrorResultsNotCrashes() throws Exception {
        ScriptedLlm llm = new ScriptedLlm()
                .then(Llm.StopReason.TOOL_USE,
                        new Llm.ToolUse("a", "delete_database", M.readTree("{}")),
                        new Llm.ToolUse("b", "get_order", M.readTree("{}")))
                .then(Llm.StopReason.END_TURN, new Llm.Text("Sorry about that."));
        var r = agent(llm, new InMemoryStores.Sessions(), 6).run("sess-12345678", "maya@example.com", "Maya", "hi");
        assertTrue(r.steps().stream().filter(s -> s.type().equals("tool")).allMatch(SupportAgent.Step::isError));
        // both results returned together in ONE user message
        assertEquals(2, llm.requests.get(1).messages().getLast().blocks().size());
    }

    @Test
    void memoryCarriesTextTurnsAcrossRequests() {
        var sessions = new InMemoryStores.Sessions();
        ScriptedLlm llm = new ScriptedLlm()
                .then(Llm.StopReason.END_TURN, new Llm.Text("Hi Maya!"))
                .then(Llm.StopReason.END_TURN, new Llm.Text("You asked about greetings."));
        SupportAgent a = agent(llm, sessions, 6);
        a.run("sess-12345678", "maya@example.com", "Maya", "hello");
        a.run("sess-12345678", "maya@example.com", "Maya", "what did I just say?");
        var msgs = llm.requests.get(1).messages();
        assertEquals(3, msgs.size()); // user, assistant, new user
        assertEquals("hello", msgs.getFirst().text());
    }

    @Test
    void refusalProducesSafeMessage() {
        ScriptedLlm llm = new ScriptedLlm().then(Llm.StopReason.REFUSAL);
        var r = agent(llm, new InMemoryStores.Sessions(), 6).run("sess-12345678", "maya@example.com", "Maya", "x");
        assertEquals("refusal", r.stopReason());
        assertTrue(r.answer().startsWith("Sorry"));
    }
}
