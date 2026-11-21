package io.qaplatform.core.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared, thread-safe Jackson configuration. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
    }

    /** Typed conversion (e.g. a JSON node to a record). The shared mapper itself is never handed out, so nobody can reconfigure it. */
    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static <T> T convert(Object value, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static com.fasterxml.jackson.databind.JsonNode readTree(java.io.InputStream in) throws java.io.IOException {
        return MAPPER.readTree(in);
    }

    public static String write(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise " + value.getClass(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot parse JSON as " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }
}
