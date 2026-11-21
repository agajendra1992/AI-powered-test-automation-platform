package io.qaplatform.mcp.discovery;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;
import io.cucumber.tagexpressions.Expression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads every {@code .feature} file under {@code src/test/resources/features} with Cucumber's own
 * Gherkin parser, so discovery sees exactly the scenarios, tags and outlines Cucumber will run.
 * Re-parses automatically when a feature file is added, removed or changed.
 */
public final class FeatureIndex {

    public record FeatureFile(String file, String name, List<String> tags, int scenarios, int testCases) {
    }

    public record ParseProblem(String file, String message) {
    }

    private record Snapshot(long fingerprint, List<ScenarioInfo> scenarios, List<FeatureFile> features, List<ParseProblem> problems) {
    }

    private final Path featuresRoot;
    private volatile Snapshot snapshot;

    public FeatureIndex(Path projectDir) {
        this.featuresRoot = projectDir.resolve("src/test/resources/features").normalize();
    }

    public Path featuresRoot() {
        return featuresRoot;
    }

    public List<ScenarioInfo> scenarios() {
        return current().scenarios();
    }

    public List<FeatureFile> features() {
        return current().features();
    }

    public List<ParseProblem> problems() {
        return current().problems();
    }

    public List<ScenarioInfo> select(Expression tagExpression) {
        if (tagExpression == null) {
            return scenarios();
        }
        return scenarios().stream()
                .filter(s -> s.tagSetsPerTestCase().stream().anyMatch(tagExpression::evaluate))
                .toList();
    }

    public Optional<ScenarioInfo> byId(String id) {
        return scenarios().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public List<ScenarioInfo> byName(String name) {
        return scenarios().stream().filter(s -> s.name().equals(name)).toList();
    }

    private Snapshot current() {
        List<Path> files = featureFiles();
        long fingerprint = fingerprint(files);
        Snapshot s = snapshot;
        if (s == null || s.fingerprint() != fingerprint) {
            synchronized (this) {
                s = snapshot;
                if (s == null || s.fingerprint() != fingerprint) {
                    s = parse(files, fingerprint);
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private List<Path> featureFiles() {
        if (!Files.isDirectory(featuresRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(featuresRoot)) {
            return walk.filter(p -> p.toString().endsWith(".feature") && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list feature files", e);
        }
    }

    private static long fingerprint(List<Path> files) {
        long h = files.size();
        for (Path p : files) {
            try {
                h = 31 * h + p.toString().hashCode();
                h = 31 * h + Files.getLastModifiedTime(p).toMillis();
                h = 31 * h + Files.size(p);
            } catch (IOException e) {
                h = 31 * h + 1;
            }
        }
        return h;
    }

    /** Result of parsing one feature text that is not (yet) on disk. */
    public record ParsedText(FeatureFile feature, List<ScenarioInfo> scenarios, List<ParseProblem> problems) {
    }

    /** Parses Gherkin text with Cucumber's parser exactly as if it were {@code features/<relativePath>}. */
    public static ParsedText parseText(String relativePath, String text) {
        GherkinParser parser = GherkinParser.builder().includeSource(false).includePickles(false).build();
        List<ParseProblem> problems = new ArrayList<>();
        List<ScenarioInfo> scenarios = new ArrayList<>();
        FeatureFile feature = null;
        List<Envelope> envelopes;
        try (Stream<Envelope> stream = parser.parse(relativePath, text.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            envelopes = stream.toList();
        }
        envelopes.forEach(e -> e.getParseError().ifPresent(err -> problems.add(new ParseProblem(relativePath, err.getMessage()))));
        for (Envelope e : envelopes) {
            Optional<Feature> f = e.getGherkinDocument().flatMap(GherkinDocument::getFeature);
            if (f.isPresent()) {
                List<ScenarioInfo> fromText = scenariosOf(relativePath, f.get());
                scenarios.addAll(fromText);
                feature = new FeatureFile(relativePath, f.get().getName(), names(f.get().getTags()), fromText.size(),
                        fromText.stream().mapToInt(ScenarioInfo::runCount).sum());
            }
        }
        if (feature == null && problems.isEmpty()) {
            problems.add(new ParseProblem(relativePath, "No Feature: found"));
        }
        return new ParsedText(feature, List.copyOf(scenarios), List.copyOf(problems));
    }

    private Snapshot parse(List<Path> files, long fingerprint) {
        GherkinParser parser = GherkinParser.builder().includeSource(false).includePickles(false).build();
        List<ScenarioInfo> scenarios = new ArrayList<>();
        List<FeatureFile> features = new ArrayList<>();
        List<ParseProblem> problems = new ArrayList<>();
        for (Path file : files) {
            String rel = featuresRoot.relativize(file).toString().replace('\\', '/');
            List<Envelope> envelopes;
            try (Stream<Envelope> stream = parser.parse(file)) {
                envelopes = stream.toList();
            } catch (IOException e) {
                problems.add(new ParseProblem(rel, "Cannot read: " + e.getMessage()));
                continue;
            }
            envelopes.forEach(e -> e.getParseError().ifPresent(err -> problems.add(new ParseProblem(rel, err.getMessage()))));
            envelopes.stream().map(Envelope::getGherkinDocument).flatMap(Optional::stream)
                    .map(GherkinDocument::getFeature).flatMap(Optional::stream)
                    .forEach(feature -> {
                        List<ScenarioInfo> fromFile = scenariosOf(rel, feature);
                        scenarios.addAll(fromFile);
                        features.add(new FeatureFile(rel, feature.getName(), names(feature.getTags()), fromFile.size(),
                                fromFile.stream().mapToInt(ScenarioInfo::runCount).sum()));
                    });
        }
        scenarios.sort(Comparator.comparing(ScenarioInfo::file).thenComparingInt(ScenarioInfo::line));
        return new Snapshot(fingerprint, List.copyOf(scenarios), List.copyOf(features), List.copyOf(problems));
    }

    private static List<ScenarioInfo> scenariosOf(String file, Feature feature) {
        List<ScenarioInfo> result = new ArrayList<>();
        List<String> featureTags = names(feature.getTags());
        List<ScenarioInfo.StepInfo> background = List.of();
        for (FeatureChild child : feature.getChildren()) {
            if (child.getBackground().isPresent()) {
                background = steps(child.getBackground().get());
            }
            if (child.getScenario().isPresent()) {
                result.add(scenario(file, feature.getName(), null, featureTags, background, child.getScenario().get()));
            }
            if (child.getRule().isPresent()) {
                Rule rule = child.getRule().get();
                List<String> ruleTags = concat(featureTags, names(rule.getTags()));
                List<ScenarioInfo.StepInfo> ruleBackground = new ArrayList<>(background);
                for (RuleChild rc : rule.getChildren()) {
                    rc.getBackground().ifPresent(b -> ruleBackground.addAll(steps(b)));
                    if (rc.getScenario().isPresent()) {
                        result.add(scenario(file, feature.getName(), rule.getName(), ruleTags, List.copyOf(ruleBackground), rc.getScenario().get()));
                    }
                }
            }
        }
        return result;
    }

    private static ScenarioInfo scenario(String file, String feature, String rule, List<String> inheritedTags,
                                         List<ScenarioInfo.StepInfo> background, Scenario s) {
        List<ScenarioInfo.ExamplesInfo> examples = new ArrayList<>();
        for (Examples ex : s.getExamples()) {
            List<String> header = ex.getTableHeader().map(FeatureIndex::cells).orElse(List.of());
            examples.add(new ScenarioInfo.ExamplesInfo(line(ex.getLocation().getLine()), ex.getName(), names(ex.getTags()), header,
                    ex.getTableBody().stream().map(FeatureIndex::cells).toList()));
        }
        int runCount = examples.isEmpty() ? 1 : examples.stream().mapToInt(e -> e.rows().size()).sum();
        int line = line(s.getLocation().getLine());
        return new ScenarioInfo(file + ":" + line, file, line, feature, rule, s.getKeyword().trim(), s.getName(),
                concat(inheritedTags, names(s.getTags())), background, steps(s.getSteps()), List.copyOf(examples), runCount);
    }

    private static List<ScenarioInfo.StepInfo> steps(Background background) {
        return steps(background.getSteps());
    }

    private static List<ScenarioInfo.StepInfo> steps(List<Step> steps) {
        return steps.stream()
                .map(st -> new ScenarioInfo.StepInfo(line(st.getLocation().getLine()), st.getKeyword().trim(), st.getText(),
                        st.getDataTable().isPresent(), st.getDocString().isPresent()))
                .toList();
    }

    private static List<String> cells(TableRow row) {
        return row.getCells().stream().map(TableCell::getValue).toList();
    }

    private static List<String> names(List<Tag> tags) {
        return tags.stream().map(Tag::getName).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        b.stream().filter(t -> !all.contains(t)).forEach(all::add);
        return List.copyOf(all);
    }

    private static int line(Long value) {
        return value == null ? 0 : value.intValue();
    }
}
