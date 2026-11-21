package io.qaplatform.mcp.util;

import java.nio.file.Path;

/** Null-safe access to a path's file name and parent (both are null for root paths). */
public final class PathNames {

    private PathNames() {
    }

    public static String name(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    public static Path parent(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Path has no parent directory: " + path);
        }
        return parent;
    }
}
