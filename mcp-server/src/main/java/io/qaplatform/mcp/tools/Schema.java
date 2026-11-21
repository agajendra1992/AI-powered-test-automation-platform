package io.qaplatform.mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Small builder for the JSON Schema of a tool's input (always a closed object). */
public final class Schema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    public static Schema object() {
        return new Schema();
    }

    public Schema string(String name, String description, boolean isRequired, int maxLength) {
        return prop(name, Map.of("type", "string", "description", description, "maxLength", maxLength), isRequired);
    }

    public Schema choice(String name, String description, Collection<String> values, String defaultValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", List.copyOf(new TreeSet<>(values)));
        if (defaultValue != null) {
            p.put("default", defaultValue);
        }
        return prop(name, p, defaultValue == null);
    }

    public Schema integer(String name, String description, int defaultValue, int min, int max) {
        return prop(name, Map.of("type", "integer", "description", description, "default", defaultValue,
                "minimum", min, "maximum", max), false);
    }

    public Schema bool(String name, String description, boolean defaultValue) {
        return prop(name, Map.of("type", "boolean", "description", description, "default", defaultValue), false);
    }

    private Schema prop(String name, Map<String, Object> schema, boolean isRequired) {
        properties.put(name, schema);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    public List<String> names() {
        return List.copyOf(properties.keySet());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
