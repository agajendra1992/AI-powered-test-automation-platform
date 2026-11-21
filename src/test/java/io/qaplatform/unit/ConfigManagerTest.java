package io.qaplatform.unit;

import io.qaplatform.core.config.ConfigManager;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigManagerTest {

    @Test
    public void systemPropertyOverridesFile() {
        System.setProperty("unit.test.key", "from-sysprop");
        try {
            assertThat(ConfigManager.get().get("unit.test.key", "default")).isEqualTo("from-sysprop");
        } finally {
            System.clearProperty("unit.test.key");
        }
    }

    @Test
    public void readsLocalEnvironmentFile() {
        assertThat(ConfigManager.get().baseUrl()).startsWith("http");
    }

    @Test
    public void snapshotMasksSecrets() {
        assertThat(ConfigManager.get().safeSnapshot()).containsEntry("auth.password", "****");
    }

    @Test
    public void traceModeParsesHyphenatedValues() {
        assertThat(ConfigManager.get().traceMode()).isNotNull();
    }
}
