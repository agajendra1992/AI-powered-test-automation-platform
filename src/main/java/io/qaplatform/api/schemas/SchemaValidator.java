package io.qaplatform.api.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** JSON Schema (draft 2020-12) validation against schemas in {@code src/test/resources/schemas}. */
public final class SchemaValidator {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private SchemaValidator() {
    }

    /** @return human-readable violations; empty when valid */
    public static Set<String> validate(String schemaName, JsonNode json) {
        JsonSchema schema = CACHE.computeIfAbsent(schemaName, SchemaValidator::loadSchema);
        Set<ValidationMessage> errors = schema.validate(json);
        return errors.stream().map(ValidationMessage::getMessage).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static JsonSchema loadSchema(String name) {
        String resource = "schemas/" + name;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Schema not found on classpath: " + resource);
            }
            return FACTORY.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read schema " + resource, e);
        }
    }
}
