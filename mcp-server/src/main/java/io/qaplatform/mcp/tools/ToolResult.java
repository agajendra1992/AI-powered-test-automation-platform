package io.qaplatform.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * What a tool handler returns: structured data (sent as both structuredContent and pretty JSON
 * text, so every client can read it) plus optional images (base64 PNG).
 */
public record ToolResult(Map<String, Object> data, List<Image> images) {

    public record Image(String base64, String mimeType) {
    }

    public static ToolResult of(Map<String, Object> data) {
        return new ToolResult(data, List.of());
    }

    public static ToolResult withImage(Map<String, Object> data, String base64Png) {
        return new ToolResult(data, List.of(new Image(base64Png, "image/png")));
    }
}
