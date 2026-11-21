package io.qaplatform.ai.recommend;

import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.tools.ToolException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Missing-coverage report. Deterministic part: per-domain balance (positive/negative, UI/API,
 * smoke) and API endpoints from the application's OpenAPI spec that no API automation calls.
 * Optional model part: scenario ideas for the requirements given, checked against existing
 * scenario names for near-duplicates. Suggestions only; nothing is created.
 */
public final class CoverageAdvisor {

    private static final Set<String> NON_DOMAIN = Set.of("@smoke", "@regression", "@sanity", "@critical", "@ui", "@api", "@e2e",
            "@positive", "@negative", "@quarantine", "@noretry");
    private static final Pattern CONSTANT = Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"(/[^\"]*)\"");
    private static final Pattern CALL = Pattern.compile("ApiRequest\\.(get|post|put|patch|delete)\\(\\s*(\"(/[^\"]*)\"|(\\w+))");

    private final McpConfig config;
    private final FeatureIndex features;
    private final AiClient ai;
    private final JsonMapper json = JsonMapper.builder().build();

    public CoverageAdvisor(McpConfig config, FeatureIndex features, AiClient ai) {
        this.config = config;
        this.features = features;
        this.ai = ai;
    }

    public Map<String, Object> suggest(String domain, String requirements, String apiSpec, boolean fetchOpenApi, boolean useAi) {
        String domainTag = domain == null ? null : "@" + domain.replace("@", "").toLowerCase(Locale.ROOT);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domainBalance", domainBalance(domainTag));
        String spec = apiSpec;
        if (spec == null && fetchOpenApi) {
            spec = fetchOpenApi();
        }
        if (spec != null) {
            result.put("apiEndpointCoverage", endpointCoverage(spec));
        }
        if (requirements != null && !requirements.isBlank()) {
            result.put("requirementCoverage", requirementCoverage(domainTag, requirements));
        }
        if (useAi && requirements != null && !requirements.isBlank()) {
            result.put("aiScenarioIdeas", ai.enabled() ? ideas(domainTag, requirements)
                    : Map.of("available", false, "reason", "AI provider not configured"));
        }
        result.put("note", "Suggestions for review. To turn one into a scenario, use generate_feature_file (AI) or write it and run validate_feature_file.");
        return result;
    }

    // ------------------------------------------------------------------ deterministic

    private List<Map<String, Object>> domainBalance(String only) {
        Map<String, List<ScenarioInfo>> byDomain = new TreeMap<>();
        for (ScenarioInfo s : features.scenarios()) {
            s.tags().stream().filter(t -> !NON_DOMAIN.contains(t)).forEach(t -> byDomain.computeIfAbsent(t, k -> new ArrayList<>()).add(s));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byDomain.forEach((tag, list) -> {
            if (only != null && !only.equals(tag)) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain", tag);
            row.put("scenarios", list.size());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String t : List.of("@positive", "@negative", "@ui", "@api", "@e2e", "@smoke")) {
                counts.put(t, list.stream().filter(s -> s.tags().contains(t)).count());
            }
            row.put("counts", counts);
            List<String> gaps = new ArrayList<>();
            if (counts.get("@negative") == 0) {
                gaps.add("no @negative scenario: error handling and validation are untested");
            }
            if (counts.get("@smoke") == 0) {
                gaps.add("no @smoke scenario: this area is not checked on every pull request");
            }
            if (counts.get("@api") == 0) {
                gaps.add("no @api scenario: business rules are only checked through the browser (slower, less precise)");
            }
            if (counts.get("@ui") == 0 && counts.get("@e2e") == 0) {
                gaps.add("no @ui or @e2e scenario: the user-facing flow is untested");
            }
            list.stream().filter(s -> !s.examples().isEmpty() && s.runCount() == 1)
                    .forEach(s -> gaps.add("outline '" + s.name() + "' has a single example row: add boundary values"));
            row.put("gaps", gaps);
            rows.add(row);
        });
        if (only != null && rows.isEmpty()) {
            throw ToolException.notFound("No scenarios carry " + only);
        }
        return rows;
    }

    Map<String, Object> endpointCoverage(String spec) {
        JsonNode root;
        try {
            root = json.readTree(spec);
        } catch (JacksonException e) {
            throw ToolException.invalid("api_spec must be an OpenAPI document in JSON");
        }
        Set<String> called = calledEndpoints();
        List<String> covered = new ArrayList<>();
        List<String> notCalled = new ArrayList<>();
        root.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            String method = op.getKey().toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                return;
            }
            String key = method + " " + normalize(path.getKey());
            (called.contains(key) ? covered : notCalled).add(method + " " + path.getKey());
        }));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoints", covered.size() + notCalled.size());
        m.put("calledByApiAutomation", covered);
        m.put("notCalledByApiAutomation", notCalled);
        m.put("note", "'Not called' means no API service or step builds this request. UI scenarios may still reach it through the browser.");
        return m;
    }

    /** METHOD + normalised path for every ApiRequest.<method>(path) in the framework, resolving String constants. */
    Set<String> calledEndpoints() {
        Set<String> called = new HashSet<>();
        for (Path root : List.of(config.projectDir().resolve("src/main/java"), config.projectDir().resolve("src/test/java"))) {
            for (Path file : javaFiles(root)) {
                String source = read(file);
                Map<String, String> constants = new LinkedHashMap<>();
                Matcher c = CONSTANT.matcher(source);
                while (c.find()) {
                    constants.put(c.group(1), c.group(2));
                }
                Matcher m = CALL.matcher(source);
                while (m.find()) {
                    String path = m.group(3) != null ? m.group(3) : constants.get(m.group(4));
                    if (path != null) {
                        called.add(m.group(1).toUpperCase(Locale.ROOT) + " " + normalize(path));
                    }
                }
            }
        }
        return called;
    }

    private String fetchOpenApi() {
        Path local = config.projectDir().resolve("src/test/resources/config/local.properties");
        Properties props = new Properties();
        try (java.io.BufferedReader reader = Files.newBufferedReader(local, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw ToolException.notFound("Cannot read local.properties to find the API base URL");
        }
        String base = props.getProperty("api.base.url", props.getProperty("base.url"));
        if (base == null || !config.browserOrigins().contains(McpConfig.origin(base))) {
            throw ToolException.forbidden("The local API base URL is not an allowed origin");
        }
        try {
            HttpResponse<String> r = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build().send(
                    HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + "/openapi.json")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new ToolException("unavailable", "openapi.json returned HTTP " + r.statusCode());
            }
            return r.body();
        } catch (IOException e) {
            throw new ToolException("unavailable", "Could not fetch openapi.json from " + base + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("unavailable", "Interrupted");
        }
    }

    /**
     * Each requirement / acceptance criterion (one per line or numbered item) mapped to the most
     * scenario whose name and steps contain most of its words. A lexical heuristic: "likely covered" needs a
     * human check; "no matching scenario" is a strong hint of a gap.
     */
    List<Map<String, Object>> requirementCoverage(String domainTag, String requirements) {
        List<ScenarioInfo> pool = domainTag == null ? features.scenarios()
                : features.scenarios().stream().filter(s -> s.tags().contains(domainTag)).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String raw : requirements.split("\\R|(?<=\\.)\\s+(?=\\d+[.)]\\s)")) {
            String item = raw.replaceFirst("^\\s*(\\d+[.)]|[-*•])\\s*", "").strip();
            if (item.length() < 8) {
                continue;
            }
            ScenarioInfo best = null;
            double bestScore = 0;
            double bestNameScore = -1;
            for (ScenarioInfo s : pool) {
                String text = s.name() + " " + String.join(" ", s.steps().stream().map(st -> st.text()).toList());
                double score = containment(item, text);
                double nameScore = containment(item, s.name());
                // Ties are broken by how well the scenario's name alone matches.
                if (score > bestScore || (score == bestScore && score > 0 && nameScore > bestNameScore)) {
                    bestScore = score;
                    bestNameScore = nameScore;
                    best = s;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requirement", item);
            if (best != null && bestScore >= 0.5) {
                row.put("status", "LIKELY COVERED");
                row.put("scenario", best.id() + " " + best.name());
            } else {
                row.put("status", "NO MATCHING SCENARIO");
            }
            row.put("matchScore", Math.round(bestScore * 100) / 100.0);
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ AI

    private Map<String, Object> ideas(String domainTag, String requirements) {
        List<ScenarioInfo> existing = domainTag == null ? features.scenarios()
                : features.scenarios().stream().filter(s -> s.tags().contains(domainTag)).toList();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("domain", domainTag == null ? "(all)" : domainTag);
        values.put("existing", existing.stream().map(s -> "- " + s.name() + " " + s.tags()).reduce("", (a, b) -> a + b + "\n"));
        values.put("requirements", PromptManager.untrusted("requirements", requirements, 8000));
        try {
            JsonNode j = ai.json("missing-scenarios", values).json();
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode idea : j.path("suggestions")) {
                String title = idea.path("title").asString("");
                if (title.isBlank()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", title);
                m.put("type", idea.path("type").asString(""));
                m.put("layer", idea.path("layer").asString(""));
                m.put("rationale", idea.path("rationale").asString(""));
                existing.stream().filter(s -> similarity(s.name(), title) >= 0.6).findFirst()
                        .ifPresent(s -> m.put("possiblyCoveredBy", s.id() + " " + s.name()));
                out.add(m);
            }
            return Map.of("status", "SUGGESTIONS (unreviewed)", "ideas", out);
        } catch (LlmException e) {
            return Map.of("available", false, "reason", e.kind() + ": " + e.getMessage());
        }
    }

    /** Share of the requirement's significant words that appear in the scenario text. */
    static double containment(String requirement, String scenarioText) {
        Set<String> r = words(requirement);
        if (r.isEmpty()) {
            return 0;
        }
        Set<String> s = words(scenarioText);
        return (double) r.stream().filter(s::contains).count() / r.size();
    }

    static double similarity(String a, String b) {
        Set<String> x = words(a);
        Set<String> y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    private static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() > 2 && !Set.of("the", "and", "with", "for", "are", "can", "should", "after", "then", "when", "given").contains(t)) {
                w.add(stem(t));
            }
        }
        return w;
    }

    /** Light stemming so "signs"/"sign", "refunded"/"refund", "locking"/"lock" match. */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) {
            return w.substring(0, w.length() - 3);
        }
        if (w.length() > 4 && w.endsWith("ed")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}").replaceAll("/$", "");
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
