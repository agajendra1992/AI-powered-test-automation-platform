package io.qaplatform.ai.insights;

import io.qaplatform.mcp.tools.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Changed files from Git: {@code git diff --name-only <ref>} (committed and uncommitted changes since
 * the ref) plus untracked files. Fixed argument lists, no shell, validated ref, 30 s timeout.
 */
public final class GitChanges {

    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/~^-]{0,99}");

    private GitChanges() {
    }

    public static List<String> since(Path projectDir, String ref) {
        if (!REF.matcher(ref).matches() || ref.contains("..")) {
            throw ToolException.invalid("changed_since must be a branch, tag or commit (e.g. origin/main, HEAD~3, a1b2c3d)");
        }
        if (!Files.exists(projectDir.resolve(".git"))) {
            throw ToolException.invalid("The project is not a Git repository; pass changed_files instead");
        }
        Set<String> files = new LinkedHashSet<>(run(projectDir, "git", "diff", "--name-only", ref, "--"));
        files.addAll(run(projectDir, "git", "ls-files", "--others", "--exclude-standard"));
        return List.copyOf(files);
    }

    private static List<String> run(Path dir, String... command) {
        try {
            Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ToolException("unavailable", "git timed out");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw ToolException.invalid("git failed: " + out.lines().findFirst().orElse("exit " + p.exitValue()));
            }
            return out.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        } catch (IOException e) {
            throw new ToolException("unavailable", "git is not available: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("unavailable", "Interrupted");
        }
    }
}
