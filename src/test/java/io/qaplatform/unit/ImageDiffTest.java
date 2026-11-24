package io.qaplatform.unit;

import io.qaplatform.ui.visual.ImageDiff;
import org.testng.annotations.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

public class ImageDiffTest {

    private static BufferedImage filled(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    @Test
    public void identicalImagesMatch() {
        ImageDiff.Result r = ImageDiff.compare(filled(10, 10, 0x336699), filled(10, 10, 0x336699), 0);
        assertThat(r.differentPixels()).isZero();
        assertThat(r.ratio()).isZero();
        assertThat(r.sizeMismatch()).isFalse();
    }

    @Test
    public void countsChangedPixelsAndMarksThemRed() {
        BufferedImage actual = filled(10, 10, 0xFFFFFF);
        actual.setRGB(3, 4, 0x000000);
        actual.setRGB(5, 6, 0x000000);
        ImageDiff.Result r = ImageDiff.compare(filled(10, 10, 0xFFFFFF), actual, 10);
        assertThat(r.differentPixels()).isEqualTo(2);
        assertThat(r.ratio()).isEqualTo(0.02);
        assertThat(r.diffImage().getRGB(3, 4)).isEqualTo(0xFFFF0000);
        assertThat(r.diffImage().getRGB(0, 0)).isNotEqualTo(0xFFFF0000);
    }

    @Test
    public void toleranceAbsorbsAntiAliasingNoise() {
        ImageDiff.Result r = ImageDiff.compare(filled(4, 4, 0x808080), filled(4, 4, 0x8A8A8A), 24);
        assertThat(r.differentPixels()).isZero();
        assertThat(ImageDiff.compare(filled(4, 4, 0x808080), filled(4, 4, 0x8A8A8A), 5).differentPixels()).isEqualTo(16);
    }

    @Test
    public void differentSizesNeverMatch() {
        ImageDiff.Result r = ImageDiff.compare(filled(10, 10, 0), filled(10, 12, 0), 255);
        assertThat(r.sizeMismatch()).isTrue();
        assertThat(r.ratio()).isEqualTo(1.0);
    }
}
