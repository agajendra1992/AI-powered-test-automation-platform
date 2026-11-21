package io.qaplatform.ai.llm;

/** Default provider: AI features report "disabled" and tools return their deterministic results. */
public final class DisabledLlmProvider implements LlmProvider {

    private final String reason;

    public DisabledLlmProvider(String reason) {
        this.reason = reason;
    }

    @Override
    public String name() {
        return "none";
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String disabledReason() {
        return reason;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        throw new LlmException(LlmException.Kind.DISABLED, reason);
    }
}
