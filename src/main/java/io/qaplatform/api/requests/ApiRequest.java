package io.qaplatform.api.requests;

import io.qaplatform.api.auth.AuthProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable description of one HTTP call. Build with {@code ApiRequest.get("/api/users/{id}").pathParam("id", 1)}. */
public final class ApiRequest {

    public enum Method { GET, POST, PUT, PATCH, DELETE }

    private final Method method;
    private final String pathTemplate;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final Object body;
    private final AuthProvider authOverride;

    private ApiRequest(Builder b) {
        this.method = b.method;
        this.pathTemplate = b.path;
        this.pathParams = Map.copyOf(b.pathParams);
        this.queryParams = Map.copyOf(b.queryParams);
        this.headers = Map.copyOf(b.headers);
        this.body = b.body;
        this.authOverride = b.auth;
    }

    public static Builder get(String path) { return new Builder(Method.GET, path); }
    public static Builder post(String path) { return new Builder(Method.POST, path); }
    public static Builder put(String path) { return new Builder(Method.PUT, path); }
    public static Builder patch(String path) { return new Builder(Method.PATCH, path); }
    public static Builder delete(String path) { return new Builder(Method.DELETE, path); }

    public Method method() { return method; }
    public String pathTemplate() { return pathTemplate; }
    public Map<String, String> queryParams() { return queryParams; }
    public Map<String, String> headers() { return headers; }
    public Object body() { return body; }
    public AuthProvider authOverride() { return authOverride; }

    /** Path with {@code {placeholders}} replaced (URL-encoded). */
    public String resolvedPath() {
        String p = pathTemplate;
        for (var e : pathParams.entrySet()) {
            p = p.replace("{" + e.getKey() + "}",
                    java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (p.contains("{")) {
            throw new IllegalArgumentException("Unresolved path parameter in " + p);
        }
        return p;
    }

    @Override
    public String toString() {
        return method + " " + resolvedPath();
    }

    public static final class Builder {
        private final Method method;
        private final String path;
        private final Map<String, String> pathParams = new LinkedHashMap<>();
        private final Map<String, String> queryParams = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Object body;
        private AuthProvider auth;

        private Builder(Method method, String path) {
            this.method = Objects.requireNonNull(method);
            this.path = Objects.requireNonNull(path);
        }

        public Builder pathParam(String name, Object value) { pathParams.put(name, String.valueOf(value)); return this; }
        public Builder query(String name, Object value) { queryParams.put(name, String.valueOf(value)); return this; }
        public Builder header(String name, String value) { headers.put(name, value); return this; }
        /** POJO/Map (serialised as JSON) or a raw String body. */
        public Builder body(Object body) { this.body = body; return this; }
        /** Overrides the client's default auth for this call only (e.g. negative tests with no/invalid auth). */
        public Builder auth(AuthProvider auth) { this.auth = auth; return this; }
        public ApiRequest build() { return new ApiRequest(this); }
    }
}
