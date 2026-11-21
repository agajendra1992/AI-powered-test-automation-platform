package io.qaplatform.ai.generation;

import io.qaplatform.mcp.util.PathNames;
import io.qaplatform.mcp.tools.ToolException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generated drafts live in {@code <state>/generated/<draftId>/}, outside the source tree. Nothing
 * generated reaches src/ until an admin accepts it, and acceptance never overwrites a file.
 */
public final class DraftStore {

    public static final Pattern ID = Pattern.compile("draft-\\d{8}-\\d{6}-[0-9a-f]{6}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final Path root;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final SecureRandom random = new SecureRandom();

    public DraftStore(Path stateDir) {
        this.root = stateDir.resolve("generated");
    }

    public String newId() {
        byte[] b = new byte[3];
        random.nextBytes(b);
        return "draft-" + TIME.format(Instant.now()) + "-" + HexFormat.of().formatHex(b);
    }

    public Path dir(String id) {
        if (!ID.matcher(id).matches()) {
            throw ToolException.invalid("draft_id must look like draft-20261119-101500-a1b2c3");
        }
        return root.resolve(id);
    }

    public void write(String id, String fileName, String content) {
        if (!fileName.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Bad draft file name " + fileName);
        }
        try {
            Files.createDirectories(dir(id));
            // Source files end with a newline (model answers are trimmed).
            String text = (fileName.endsWith(".java") || fileName.endsWith(".feature")) && !content.endsWith("\n") ? content + "\n" : content;
            Files.writeString(dir(id).resolve(fileName), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeJson(String id, String fileName, Object value) {
        write(id, fileName, json.writeValueAsString(value));
    }

    public Map<String, Object> meta(String id) {
        Path file = dir(id).resolve("meta.json");
        if (!Files.isRegularFile(file)) {
            throw ToolException.notFound("No draft " + id);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = json.readValue(file.toFile(), LinkedHashMap.class);
        return meta;
    }

    public String read(String id, String fileName) {
        try {
            return Files.readString(dir(id).resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ToolException.notFound("Draft " + id + " has no " + fileName);
        }
    }

    public boolean has(String id, String fileName) {
        return Files.isRegularFile(dir(id).resolve(fileName));
    }

    public List<Map<String, Object>> list(int limit) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(d -> ID.matcher(PathNames.name(d)).matches() && Files.isRegularFile(d.resolve("meta.json")))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .limit(limit)
                    .map(d -> meta(PathNames.name(d)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
