package io.qaplatform.ai.generation;

import io.qaplatform.mcp.util.PathNames;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Facts about the framework, read from its sources, that generation prompts need. */
public final class ProjectContext {

    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "^\\s*public\\s+(?!class|record|interface|enum)(?:static\\s+)?(?:<[^>]+>\\s+)?([\\w.<>\\[\\], ?]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.MULTILINE);

    private final Path projectDir;
    private final FeatureIndex features;
    private final StepDefinitionIndex steps;

    public ProjectContext(Path projectDir, FeatureIndex features, StepDefinitionIndex steps) {
        this.projectDir = projectDir;
        this.features = features;
        this.steps = steps;
    }

    /** Every step expression, grouped by class, so the model reuses steps instead of inventing them. */
    public String stepCatalog() {
        return steps.definitions().stream()
                .collect(Collectors.groupingBy(StepDefinitionIndex.StepDefinition::className, java.util.TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> e.getKey() + ":\n" + e.getValue().stream().map(d -> "  " + d.keyword() + " " + d.expression())
                        .collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n"));
    }

    public String parameterTypes() {
        List<String> lines = new ArrayList<>();
        for (StepDefinitionIndex.CustomParameterType t : steps.parameterTypes()) {
            lines.add("{" + t.name() + "} accepts exactly one of: " + String.join(" | ", t.regex().split("\\|")));
        }
        lines.add("Built-in: {string} = text in double quotes, {int}, {word}, {float}");
        return String.join("\n", lines);
    }

    /** An existing feature file of the same domain (or the login feature) as a style example. */
    public String exampleFeature(String domainTag) {
        String tag = domainTag == null ? null : (domainTag.startsWith("@") ? domainTag : "@" + domainTag).toLowerCase(Locale.ROOT);
        Path chosen = features.features().stream()
                .filter(f -> tag == null || f.tags().contains(tag) || features.scenarios().stream()
                        .anyMatch(s -> s.file().equals(f.file()) && s.tags().contains(tag)))
                .map(f -> features.featuresRoot().resolve(f.file()))
                .findFirst()
                .orElse(features.featuresRoot().resolve("login/login.feature"));
        return read(chosen);
    }

    public String scenarioContextApi() {
        return signatures(projectDir.resolve("src/test/java/io/qaplatform/bdd/context/ScenarioContext.java"));
    }

    /** Public methods of page objects, components and API services (what steps should call). */
    public String pageAndServiceApis() {
        StringBuilder sb = new StringBuilder();
        for (String dir : List.of("src/main/java/io/qaplatform/ui/pages", "src/main/java/io/qaplatform/ui/components",
                "src/main/java/io/qaplatform/api/services")) {
            for (Path file : javaFiles(projectDir.resolve(dir))) {
                String sigs = signatures(file);
                if (!sigs.isBlank()) {
                    sb.append(PathNames.name(file).replace(".java", "")).append(" (").append(dir.replace("src/main/java/", "")
                            .replace('/', '.')).append("):\n").append(sigs).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** An existing step class as the style reference: chosen by domain, default the user API steps. */
    public String exampleStepClass(String domainTag) {
        String domain = domainTag == null ? "" : domainTag.replace("@", "").toLowerCase(Locale.ROOT);
        String file = switch (domain) {
            case "payment" -> "PaymentApiSteps.java";
            case "login" -> "LoginSteps.java";
            case "user" -> "UserManagementSteps.java";
            default -> "UserApiSteps.java";
        };
        return read(projectDir.resolve("src/test/java/io/qaplatform/bdd/steps/" + file));
    }

    public List<String> existingStepClassNames() {
        return javaFiles(projectDir.resolve("src/test/java/io/qaplatform/bdd/steps")).stream()
                .map(p -> PathNames.name(p).replace(".java", "")).toList();
    }

    private static String signatures(Path file) {
        String source = read(file);
        Matcher m = PUBLIC_METHOD.matcher(source);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add("  " + m.group(1).trim() + " " + m.group(2) + "(" + m.group(3).replaceAll("\\s+", " ").trim() + ")");
        }
        return String.join("\n", out);
    }

    private static List<Path> javaFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
