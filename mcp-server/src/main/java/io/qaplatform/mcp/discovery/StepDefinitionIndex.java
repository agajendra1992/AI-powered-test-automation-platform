package io.qaplatform.mcp.discovery;

import io.qaplatform.mcp.util.PathNames;
import io.cucumber.cucumberexpressions.CucumberExpressionException;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds Java step definitions ({@code @Given/@When/@Then/@And/@But}) and custom
 * {@code @ParameterType}s in the framework's test sources and matches Gherkin step text against
 * them with Cucumber's own expression engine, the same way Cucumber binds steps at run time.
 *
 * <p>Sources are read as text (not loaded), so the MCP server never executes framework code.
 */
public final class StepDefinitionIndex {

    public record StepDefinition(String keyword, String expression, String className, String method, String file, int line) {
    }

    public record Match(StepDefinition definition, List<String> arguments) {
    }

    public record CustomParameterType(String name, String regex, String file) {
    }

    private record Compiled(StepDefinition definition, Expression expression) {
    }

    private record Snapshot(long fingerprint, List<Compiled> definitions, List<CustomParameterType> parameterTypes, List<String> problems) {
    }

    /** One step annotation; several may stack on one method, so each is matched on its own. */
    private static final Pattern STEP = Pattern.compile(
            "@(Given|When|Then|And|But)\\(\\s*(?:value\\s*=\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");
    /** The next public method declaration after an annotation (Cucumber step methods must be public). */
    private static final Pattern METHOD = Pattern.compile("\\bpublic\\s+(?:static\\s+)?[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*\\(");
    private static final Pattern PARAMETER_TYPE = Pattern.compile(
            "@ParameterType\\(\\s*(?:value\\s*=\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"(?:\\s*,\\s*name\\s*=\\s*\"(\\w+)\")?[^)]*\\)\\s*"
                    + "(?:public\\s+)?[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*\\(");

    private final Path projectDir;
    private final Path sourceRoot;
    private volatile Snapshot snapshot;

    public StepDefinitionIndex(Path projectDir) {
        this.projectDir = projectDir;
        this.sourceRoot = projectDir.resolve("src/test/java").normalize();
    }

    public List<StepDefinition> definitions() {
        return current().definitions().stream().map(Compiled::definition).toList();
    }

    public List<CustomParameterType> parameterTypes() {
        return current().parameterTypes();
    }

    /** Problems found while indexing (e.g. an expression Cucumber would also reject). */
    public List<String> problems() {
        return current().problems();
    }

    /** All definitions whose expression matches the step text. 0 = undefined, &gt;1 = ambiguous. */
    public List<Match> match(String stepText) {
        return match(stepText, current().definitions());
    }

    /**
     * Matches against the project's definitions plus extra Java source that is not on disk yet
     * (a generated step class), using the project's parameter types.
     */
    public Function<String, List<Match>> withExtraSource(String javaSource, String relativePath) {
        Snapshot base = current();
        List<Compiled> all = new ArrayList<>(base.definitions());
        ExpressionFactory factory = factory(base.parameterTypes(), new ArrayList<>());
        for (StepDefinition d : parseSteps(javaSource, relativePath)) {
            all.add(new Compiled(d, factory.createExpression(d.expression())));
        }
        return text -> match(text, all);
    }

    /** Step definitions declared in one Java source text. */
    public static List<StepDefinition> parseSteps(String source, String relativePath) {
        List<StepDefinition> defs = new ArrayList<>();
        String className = relativePath.replaceAll(".*/", "").replaceFirst("\\.java$", "");
        Matcher m = STEP.matcher(source);
        while (m.find()) {
            Matcher method = METHOD.matcher(source);
            String methodName = method.find(m.end()) ? method.group(1) : "?";
            defs.add(new StepDefinition(m.group(1), unescape(m.group(2)), className, methodName, relativePath, lineOf(source, m.start())));
        }
        return defs;
    }

    private static List<Match> match(String stepText, List<Compiled> definitions) {
        List<Match> matches = new ArrayList<>();
        for (Compiled c : definitions) {
            var args = c.expression().match(stepText);
            if (args != null) {
                matches.add(new Match(c.definition(), args.stream().map(a -> a.getGroup() == null ? null : a.getGroup().getValue()).toList()));
            }
        }
        return matches;
    }

    private Snapshot current() {
        List<Path> files = javaFiles();
        long fingerprint = fingerprint(files);
        Snapshot s = snapshot;
        if (s == null || s.fingerprint() != fingerprint) {
            synchronized (this) {
                s = snapshot;
                if (s == null || s.fingerprint() != fingerprint) {
                    s = build(files, fingerprint);
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private Snapshot build(List<Path> files, long fingerprint) {
        List<StepDefinition> raw = new ArrayList<>();
        List<CustomParameterType> types = new ArrayList<>();
        for (Path file : files) {
            String source = read(file);
            String rel = projectDir.relativize(file).toString().replace('\\', '/');
            String className = PathNames.name(file).replaceFirst("\\.java$", "");
            Matcher m = STEP.matcher(source);
            while (m.find()) {
                Matcher method = METHOD.matcher(source);
                String methodName = method.find(m.end()) ? method.group(1) : "?";
                raw.add(new StepDefinition(m.group(1), unescape(m.group(2)), className, methodName, rel, lineOf(source, m.start())));
            }
            Matcher p = PARAMETER_TYPE.matcher(source);
            while (p.find()) {
                String name = p.group(2) != null ? p.group(2) : p.group(3);
                types.add(new CustomParameterType(name, unescape(p.group(1)), rel));
            }
        }
        List<String> problems = new ArrayList<>();
        ExpressionFactory factory = factory(types, problems);
        List<Compiled> compiled = new ArrayList<>();
        for (StepDefinition d : raw) {
            try {
                compiled.add(new Compiled(d, factory.createExpression(d.expression())));
            } catch (CucumberExpressionException e) {
                problems.add("Step '" + d.expression() + "' in " + d.file() + ":" + d.line() + ": " + e.getMessage());
            }
        }
        compiled.sort(Comparator.comparing((Compiled c) -> c.definition().file()).thenComparingInt(c -> c.definition().line()));
        return new Snapshot(fingerprint, List.copyOf(compiled), List.copyOf(types), List.copyOf(problems));
    }

    private static ExpressionFactory factory(List<CustomParameterType> types, List<String> problems) {
        ParameterTypeRegistry registry = new ParameterTypeRegistry(Locale.ENGLISH);
        for (CustomParameterType t : types) {
            try {
                registry.defineParameterType(new ParameterType<>(t.name(), t.regex(), String.class, (String s) -> s));
            } catch (CucumberExpressionException e) {
                problems.add("Parameter type {" + t.name() + "} in " + t.file() + ": " + e.getMessage());
            }
        }
        return new ExpressionFactory(registry);
    }

    private List<Path> javaFiles() {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list Java sources", e);
        }
    }

    private static long fingerprint(List<Path> files) {
        long h = files.size();
        for (Path p : files) {
            try {
                h = 31 * h + p.toString().hashCode();
                h = 31 * h + Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                h = 31 * h + 1;
            }
        }
        return h;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Java string literal to its value (the escapes that appear in step expressions). */
    static String unescape(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '\\' && i + 1 < literal.length()) {
                char n = literal.charAt(++i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\'' -> out.append('\'');
                    case '\\' -> out.append('\\');
                    default -> out.append('\\').append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
