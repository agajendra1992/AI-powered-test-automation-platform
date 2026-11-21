package io.qaplatform.mcp;

import io.qaplatform.mcp.config.McpConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Helpers shared by the MCP unit tests. */
public final class TestSupport {

    private TestSupport() {
    }

    /** The real framework root (set by surefire). */
    public static Path frameworkDir() {
        return Path.of(System.getProperty("qa.mcp.project.dir", "..")).toAbsolutePath().normalize();
    }

    /** Minimal fake project: enough for McpConfig validation and for fake runs to write target/ outputs. */
    public static Path fakeProject() {
        try {
            Path dir = Files.createTempDirectory("qa-mcp-project");
            Files.writeString(dir.resolve("pom.xml"), "<project/>");
            Files.createDirectories(dir.resolve("src/test/resources/features"));
            Files.createDirectories(dir.resolve("src/test/resources/config"));
            Files.writeString(dir.resolve("src/test/resources/config/local.properties"), "base.url=http://localhost:8000\n");
            Files.writeString(dir.resolve("testng-smoke.xml"), "<suite/>");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static McpConfig config(Path project, Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>();
        values.put("qa.mcp.project.dir", project.toString());
        try {
            values.put("qa.mcp.state.dir", Files.createTempDirectory("qa-mcp-state").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        overrides.forEach((k, v) -> values.put("qa.mcp." + k, v));
        return McpConfig.from(values::get);
    }
}
