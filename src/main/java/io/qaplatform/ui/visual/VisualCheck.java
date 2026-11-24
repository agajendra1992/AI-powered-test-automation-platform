package io.qaplatform.ui.visual;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotCaret;
import com.microsoft.playwright.options.ScreenshotScale;
import io.qameta.allure.Allure;
import io.qameta.allure.AttachmentOptions;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.exceptions.FrameworkException;
import io.qaplatform.core.reporting.AllureAttachments;
import io.qaplatform.core.reporting.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Screenshot comparison against committed baselines (Playwright Java has no built-in
 * {@code toHaveScreenshot}).
 * <p>
 * Baselines live in {@code <visual.baseline.dir>/<browser>-<os>/<name>.png}: fonts and rendering
 * differ by browser and operating system, so each combination has its own set. CI compares against
 * the {@code -linux} sets.
 * <p>
 * Screenshots are taken with animations stopped, the text caret hidden, CSS-pixel scale, and dynamic
 * regions masked. Baselines are written only when {@code visual.update=true} is set explicitly;
 * a normal run never overwrites them.
 */
public final class VisualCheck {

    private VisualCheck() {
    }

    public static VisualResult check(Page page, String name, List<Locator> masks) {
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new FrameworkException("Visual check names are lower-case words joined by '-': " + name);
        }
        return Allure.step("Visual check: " + name, () -> {
            ConfigManager config = ConfigManager.get();
            String set = config.browser() + "-" + os();
            Path baseline = Paths.get(config.get("visual.baseline.dir", "src/test/resources/visual-baselines")).resolve(set).resolve(name + ".png");
            Path out = Paths.get("target", "visual", set);
            Path actualFile = out.resolve(name + "-actual.png");
            double maxRatio = Double.parseDouble(config.get("visual.max.diff.ratio", "0.001"));
            int tolerance = config.getInt("visual.channel.tolerance", 24);

            byte[] png = page.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(true)
                    .setAnimations(ScreenshotAnimations.DISABLED)
                    .setCaret(ScreenshotCaret.HIDE)
                    .setScale(ScreenshotScale.CSS)
                    .setMask(masks));

            if (config.getBool("visual.update", false)) {
                write(baseline, png);
                AllureAttachments.png("Visual baseline (updated): " + name, png);
                return new VisualResult(name, VisualResult.Status.BASELINE_UPDATED, 0, maxRatio, baseline, baseline);
            }
            write(actualFile, png);
            if (!Files.exists(baseline)) {
                AllureAttachments.png("Visual actual (no baseline): " + name, png);
                return new VisualResult(name, VisualResult.Status.BASELINE_MISSING, 1, maxRatio, baseline, actualFile);
            }
            byte[] expectedPng = Files.readAllBytes(baseline);
            ImageDiff.Result diff = ImageDiff.compare(decode(expectedPng), decode(png), tolerance);
            VisualResult.Status status = diff.sizeMismatch() ? VisualResult.Status.SIZE_CHANGED
                    : diff.ratio() > maxRatio ? VisualResult.Status.DIFFERENT : VisualResult.Status.MATCH;
            if (status != VisualResult.Status.MATCH) {
                byte[] diffPng = encode(diff.diffImage());
                write(out.resolve(name + "-diff.png"), diffPng);
                attachDiff(name, expectedPng, png, diffPng);
            }
            return new VisualResult(name, status, diff.ratio(), maxRatio, baseline, actualFile);
        });
    }

    /** Allure's screen-diff view (expected / actual / diff with a slider) plus the three images on their own. */
    private static void attachDiff(String name, byte[] expected, byte[] actual, byte[] diff) {
        String json = Json.write(Map.of("expected", dataUri(expected), "actual", dataUri(actual), "diff", dataUri(diff)));
        Allure.attachment("Visual diff: " + name, "application/vnd.allure.image.diff", json, AttachmentOptions.withFileExtension(".imagediff"));
        AllureAttachments.png("Visual expected: " + name, expected);
        AllureAttachments.png("Visual actual: " + name, actual);
        AllureAttachments.png("Visual diff (red = changed): " + name, diff);
    }

    private static String os() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? "mac" : os.contains("win") ? "windows" : "linux";
    }

    private static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private static BufferedImage decode(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                throw new FrameworkException("Not a readable PNG image");
            }
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] encode(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
