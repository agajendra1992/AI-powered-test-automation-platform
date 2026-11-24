package io.qaplatform.ui.visual;

import java.awt.image.BufferedImage;

/**
 * Pixel comparison of two screenshots, no external library.
 * <p>
 * A pixel differs when any RGB channel differs by more than {@code channelTolerance} (0-255);
 * the tolerance absorbs anti-aliasing noise. Images of different size never match.
 * The diff image shows the actual screenshot faded, with differing pixels in solid red.
 */
public final class ImageDiff {

    private static final int RED = 0xFFFF0000;

    private ImageDiff() {
    }

    public record Result(int width, int height, long differentPixels, boolean sizeMismatch, BufferedImage diffImage) {

        public long totalPixels() {
            return (long) width * height;
        }

        /** Share of differing pixels, 0.0 to 1.0; 1.0 when the sizes differ. */
        public double ratio() {
            return sizeMismatch || totalPixels() == 0 ? 1.0 : (double) differentPixels / totalPixels();
        }
    }

    public static Result compare(BufferedImage expected, BufferedImage actual, int channelTolerance) {
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            return new Result(actual.getWidth(), actual.getHeight(), (long) actual.getWidth() * actual.getHeight(), true, actual);
        }
        int w = actual.getWidth();
        int h = actual.getHeight();
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        long different = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = expected.getRGB(x, y);
                int b = actual.getRGB(x, y);
                if (differs(a, b, channelTolerance)) {
                    different++;
                    diff.setRGB(x, y, RED);
                } else {
                    diff.setRGB(x, y, faded(b));
                }
            }
        }
        return new Result(w, h, different, false, diff);
    }

    private static boolean differs(int a, int b, int tolerance) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) > tolerance
                || Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) > tolerance
                || Math.abs((a & 0xFF) - (b & 0xFF)) > tolerance;
    }

    /** Light grey version of the pixel, so red differences stand out. */
    private static int faded(int rgb) {
        int grey = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
        int light = 200 + grey * 55 / 255;
        return 0xFF000000 | (light << 16) | (light << 8) | light;
    }
}
