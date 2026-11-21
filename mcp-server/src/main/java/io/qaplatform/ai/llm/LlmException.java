package io.qaplatform.ai.llm;

/** Any failure to get a usable answer from a model. Callers fall back to deterministic results. */
public class LlmException extends RuntimeException {

    public enum Kind { DISABLED, BUDGET_EXCEEDED, TIMEOUT, PROVIDER_ERROR, REFUSED, BAD_OUTPUT, PROMPT_TOO_LARGE }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
