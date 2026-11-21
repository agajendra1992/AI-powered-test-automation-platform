package io.qaplatform.unit;

import io.qaplatform.core.data.TestDataManager;
import io.qaplatform.core.exceptions.FrameworkException;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDataManagerTest {

    @Test
    public void loadsExistingFile() {
        assertThat(TestDataManager.load("payment_cards").isMissingNode()).isFalse();
    }

    @Test
    public void missingFileRaisesFrameworkException() {
        assertThatThrownBy(() -> TestDataManager.load("does-not-exist"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("Test data not found")
                .hasMessageContaining("does-not-exist.json");
    }

    @Test
    public void malformedJsonRaisesFrameworkException() {
        assertThatThrownBy(() -> TestDataManager.load("unit-malformed"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("Invalid JSON in test data file testdata/unit-malformed.json");
    }
}
