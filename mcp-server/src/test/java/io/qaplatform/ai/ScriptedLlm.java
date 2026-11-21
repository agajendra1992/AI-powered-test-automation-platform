package io.qaplatform.ai;

import io.qaplatform.ai.llm.LlmProvider;
import io.qaplatform.ai.llm.LlmRequest;
import io.qaplatform.ai.llm.LlmResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test double: returns scripted answers in order and records every request it receives. */
public final class ScriptedLlm implements LlmProvider {

    private final Deque<String> answers = new ArrayDeque<>();
    public final List<LlmRequest> requests = new ArrayList<>();

    public ScriptedLlm answer(String text) {
        answers.add(text);
        return this;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public String model() {
        return "scripted-model";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        String text = answers.isEmpty() ? "{}" : answers.poll();
        return new LlmResponse("scripted", "scripted-model", text, 100, 50, "end_turn", false, 1);
    }
}
