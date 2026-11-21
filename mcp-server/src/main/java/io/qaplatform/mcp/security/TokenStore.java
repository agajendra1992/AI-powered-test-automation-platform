package io.qaplatform.mcp.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Bearer-token authentication for the HTTP transport.
 *
 * <p>The token file holds only SHA-256 hashes, never tokens:
 * <pre>[{"name": "ci-bot", "role": "runner", "sha256": "3f1c..."}]</pre>
 * Create entries with {@code java -jar qa-mcp-server.jar new-token <name> <role>}.
 * Comparison is constant-time.
 */
public final class TokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(String name, Role role, byte[] hash) {
    }

    private final List<Entry> entries;

    private TokenStore(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static TokenStore load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalStateException("HTTP transport needs a token file (qa.mcp.tokens.file / QA_MCP_TOKENS_FILE): " + file);
        }
        JsonNode root = JsonMapper.builder().build().readTree(file.toFile());
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : root) {
            String name = node.path("name").asString("");
            String hash = node.path("sha256").asString("");
            if (name.isBlank() || !hash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalStateException("Invalid token entry (need name and 64-hex sha256) in " + file);
            }
            entries.add(new Entry(name, Role.parse(node.path("role").asString("viewer")), HexFormat.of().parseHex(hash)));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("Token file has no entries: " + file);
        }
        return new TokenStore(entries);
    }

    public Optional<Principal> authenticate(String token) {
        if (token == null || token.length() < 20 || token.length() > 200) {
            return Optional.empty();
        }
        byte[] presented = sha256(token);
        Entry match = null;
        for (Entry entry : entries) {
            // Evaluate every entry so timing does not reveal which one matched.
            if (MessageDigest.isEqual(entry.hash(), presented)) {
                match = entry;
            }
        }
        return Optional.ofNullable(match).map(e -> new Principal(e.name(), e.role(), "http"));
    }

    public int size() {
        return entries.size();
    }

    /** New random token (256 bits, URL-safe). Shown once to the operator; only its hash is stored. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "qamcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
