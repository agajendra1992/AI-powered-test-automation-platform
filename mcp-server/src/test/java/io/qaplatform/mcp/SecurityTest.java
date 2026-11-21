package io.qaplatform.mcp;

import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.execution.ExecutionRequest;
import io.qaplatform.mcp.security.Redactor;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.security.TokenStore;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SecurityTest {

    @Test
    public void tokenFileStoresOnlyHashesAndAuthenticatesExactToken() throws Exception {
        String runner = TokenStore.newToken();
        String admin = TokenStore.newToken();
        Path file = Files.createTempFile("tokens", ".json");
        Files.writeString(file, "[{\"name\":\"bot\",\"role\":\"runner\",\"sha256\":\"" + TokenStore.sha256Hex(runner) + "\"},"
                + "{\"name\":\"lead\",\"role\":\"admin\",\"sha256\":\"" + TokenStore.sha256Hex(admin) + "\"}]");
        assertThat(Files.readString(file)).doesNotContain(runner).doesNotContain(admin);

        TokenStore store = TokenStore.load(file);
        assertThat(store.authenticate(runner)).hasValueSatisfying(p -> {
            assertThat(p.name()).isEqualTo("bot");
            assertThat(p.role()).isEqualTo(Role.RUNNER);
        });
        assertThat(store.authenticate(admin)).hasValueSatisfying(p -> assertThat(p.role()).isEqualTo(Role.ADMIN));
        assertThat(store.authenticate(runner + "x")).isEmpty();
        assertThat(store.authenticate("short")).isEmpty();
        assertThat(store.authenticate(null)).isEmpty();
    }

    @Test
    public void tokenFileIsRequiredAndValidated() throws Exception {
        assertThatThrownBy(() -> TokenStore.load(null)).hasMessageContaining("token file");
        Path bad = Files.createTempFile("tokens", ".json");
        Files.writeString(bad, "[{\"name\":\"x\",\"role\":\"runner\",\"sha256\":\"not-a-hash\"}]");
        assertThatThrownBy(() -> TokenStore.load(bad)).hasMessageContaining("64-hex");
    }

    @Test
    public void rolesAreOrdered() {
        assertThat(Role.ADMIN.atLeast(Role.RUNNER)).isTrue();
        assertThat(Role.RUNNER.atLeast(Role.VIEWER)).isTrue();
        assertThat(Role.VIEWER.atLeast(Role.RUNNER)).isFalse();
        assertThatThrownBy(() -> Role.parse("root")).hasMessageContaining("Unknown role");
    }

    @Test
    public void redactorMasksSecretsButKeepsOrdinaryText() {
        String text = "Authorization: Bearer eyJhbGciOi.abc.def password=hunter2 {\"cardNumber\":\"4111111111111111\",\"cvv\":\"123\"} "
                + "token qamcp_AAAAAAAAAAAAAAAAAAAAAAAAAAAA and 4000 0000 0000 0002 ok";
        String redacted = Redactor.redact(text);
        assertThat(redacted).doesNotContain("eyJhbGciOi", "hunter2", "4111111111111111", "\"123\"", "AAAAAAAAAAAAAAAA", "0000 0000 0002");
        assertThat(redacted).contains("ok", "Authorization");
    }

    @Test
    public void redactorKeepsIdsTimestampsAndOrdinaryNumbers() {
        for (String safe : new String[] {"run-20261119-170803-2965f6", "1763572083123", "order 1234567890123 of 2026-11-19",
                                         "duration 18306 ms", "4111 1111 1111 1112"}) {
            assertThat(Redactor.redact(safe)).as(safe).isEqualTo(safe);
        }
        assertThat(Redactor.redact("card 5555-5555-5555-4444 end")).isEqualTo("card ****card**** end");
    }

    @Test
    public void configRejectsUnknownEnvironmentsAndNonMavenCommands() {
        Path project = TestSupport.fakeProject();
        assertThatThrownBy(() -> TestSupport.config(project, Map.of("allowed.envs", "local,prod")))
                .hasMessageContaining("Unknown environments");
        assertThatThrownBy(() -> TestSupport.config(project, Map.of("maven.command", "/bin/sh")))
                .hasMessageContaining("must be mvn or mvnw");
        assertThatThrownBy(() -> McpConfig.from(k -> k.equals("qa.mcp.project.dir") ? "/" : null))
                .hasMessageContaining("framework root");
    }

    @Test
    public void defaultsAreRestrictive() {
        McpConfig config = TestSupport.config(TestSupport.fakeProject(), Map.of());
        assertThat(config.stdioRole()).isEqualTo(Role.VIEWER);
        assertThat(config.httpHost()).isEqualTo("127.0.0.1");
        assertThat(config.requiresApproval("staging")).isTrue();
        assertThat(config.requiresApproval("local")).isFalse();
        assertThat(config.browserOrigins()).containsExactly("http://localhost:8000");
    }

    @Test
    public void mavenArgumentsAreDiscreteArgvEntries() {
        ExecutionRequest request = new ExecutionRequest("tag", "testng-regression.xml", "(@api and not @negative) and not @quarantine",
                java.util.List.of("Pay with a card (valid) $x"), "qa", "firefox", 2, 1);
        assertThat(request.mavenArguments())
                .contains("-DsuiteXmlFile=testng-regression.xml", "-Denv=qa", "-Dbrowser=firefox", "-Dthreads=2", "-Dretry.count=1",
                        "-Dcucumber.filter.tags=(@api and not @negative) and not @quarantine")
                .contains("-Dcucumber.filter.name=^\\QPay with a card (valid) $x\\E$");
        ExecutionRequest several = new ExecutionRequest("plan", "testng-regression.xml", null, java.util.List.of("A", "B|C"),
                "local", "chromium", 4, 0);
        assertThat(several.mavenArguments()).contains("-Dcucumber.filter.name=^(?:\\QA\\E|\\QB|C\\E)$");
    }
}
