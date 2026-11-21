package io.qaplatform.ai.generation;

import io.qaplatform.mcp.util.PathNames;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.tools.ToolException;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Proves generated Java compiles against the real framework without touching it: the framework's
 * main and test sources plus the generated class are compiled in memory (javax.tools, in-process)
 * into a temporary directory. The dependency classpath comes from
 * {@code mvn dependency:build-classpath}, run once per pom.xml version and cached.
 */
public final class JavaCompileCheck {

    public record Problem(String file, long line, String message) {
    }

    private final McpConfig config;

    public JavaCompileCheck(McpConfig config) {
        this.config = config;
    }

    /** @param extra relative path (e.g. src/test/java/.../FooSteps.java) → source text */
    public List<Problem> compile(Map<String, String> extra) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ToolException("unavailable", "No Java compiler in this runtime (run the MCP server on a JDK, not a JRE)");
        }
        Path out;
        try {
            out = Files.createTempDirectory("qa-compile-check");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<JavaFileObject> units = new ArrayList<>();
            for (Path root : List.of(config.projectDir().resolve("src/main/java"), config.projectDir().resolve("src/test/java"))) {
                List<Path> sources = javaFiles(root).stream()
                        .filter(p -> !extra.containsKey(config.projectDir().relativize(p).toString().replace('\\', '/')))
                        .toList();
                files.getJavaFileObjectsFromPaths(sources).forEach(units::add);
            }
            extra.forEach((rel, source) -> units.add(new InMemorySource(rel, source)));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:none", "-nowarn",
                    "-classpath", classpath(), "-d", out.toString());
            compiler.getTask(null, files, diagnostics, options, null, units).call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(out);
        }
        List<Problem> problems = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                String file = d.getSource() == null ? "?" : d.getSource().getName().replace(config.projectDir().toString() + "/", "");
                problems.add(new Problem(file, d.getLineNumber(), d.getMessage(null)));
            }
        }
        return problems;
    }

    /** Framework test classpath (dependencies only), cached in the state directory per pom.xml hash. */
    String classpath() {
        try {
            String pomHash = sha(Files.readString(config.projectDir().resolve("pom.xml")));
            Path cache = config.stateDir().resolve("classpath-" + pomHash.substring(0, 12) + ".txt");
            if (!Files.isRegularFile(cache)) {
                Files.createDirectories(PathNames.parent(cache));
                Process p = new ProcessBuilder(config.mavenCommand(), "-B", "-q", "dependency:build-classpath",
                        "-Dmdep.includeScope=test", "-Dmdep.outputFile=" + cache)
                        .directory(config.projectDir().toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                    p.destroyForcibly();
                    throw new ToolException("unavailable", "Could not resolve the framework classpath (mvn dependency:build-classpath)");
                }
            }
            return Files.readString(cache).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("unavailable", "Interrupted while resolving the classpath");
        }
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

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    p.toFile().deleteOnExit();
                }
            });
        } catch (IOException e) {
            // Temporary directory; the OS cleans it up if this fails.
            dir.toFile().deleteOnExit();
        }
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String source;

        InMemorySource(String relativePath, String source) {
            super(URI.create("string:///" + relativePath), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
