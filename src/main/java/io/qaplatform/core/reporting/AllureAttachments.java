package io.qaplatform.core.reporting;

import io.qameta.allure.Allure;
import io.qameta.allure.AttachmentOptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Thin, null-safe wrapper over Allure attachments so callers never need try/catch. */
public final class AllureAttachments {

    private AllureAttachments() {
    }

    public static void text(String name, String content) {
        Allure.attachment(name, "text/plain", content == null ? "" : content, AttachmentOptions.withFileExtension(".txt"));
    }

    public static void json(String name, String content) {
        Allure.attachment(name, "application/json", content == null ? "" : content, AttachmentOptions.withFileExtension(".json"));
    }

    public static void png(String name, byte[] bytes) {
        if (bytes != null) {
            Allure.attachment(name, "image/png", new ByteArrayInputStream(bytes), AttachmentOptions.withFileExtension(".png"));
        }
    }

    public static void file(String name, String mime, Path path, String extension) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var in = Files.newInputStream(path)) {
            Allure.attachment(name, mime, in, AttachmentOptions.withFileExtension(extension));
        } catch (IOException e) {
            text(name + " (unavailable)", e.getMessage());
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
