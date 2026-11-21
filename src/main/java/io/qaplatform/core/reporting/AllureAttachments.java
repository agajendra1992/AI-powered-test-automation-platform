package io.qaplatform.core.reporting;

import io.qameta.allure.Allure;

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
        Allure.addAttachment(name, "text/plain", content == null ? "" : content, ".txt");
    }

    public static void json(String name, String content) {
        Allure.addAttachment(name, "application/json", content == null ? "" : content, ".json");
    }

    public static void png(String name, byte[] bytes) {
        if (bytes != null) {
            Allure.addAttachment(name, "image/png", new ByteArrayInputStream(bytes), ".png");
        }
    }

    public static void file(String name, String mime, Path path, String extension) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Allure.addAttachment(name, mime, Files.newInputStream(path), extension);
        } catch (IOException e) {
            text(name + " (unavailable)", e.getMessage());
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
