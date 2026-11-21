package io.qaplatform.mcp;

import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.security.AuditLog;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Evidence lookup in Allure output, including attachments that Cucumber hooks add via containers. */
public class ReportReaderTest {

    @Test
    public void findsHookAttachmentsThroughAllureContainers() throws Exception {
        Path project = TestSupport.fakeProject();
        Path results = project.resolve("target/allure-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("r1-result.json"), """
                {"uuid":"r1","name":"Pay with a valid card","status":"failed","stop":2,
                 "steps":[{"name":"step","attachments":[{"name":"Request","type":"text/plain","source":"a0-attachment.txt"}]}]}
                """);
        Files.writeString(results.resolve("r0-result.json"), """
                {"uuid":"r0","name":"Pay with a valid card","status":"failed","stop":1}
                """);
        Files.writeString(results.resolve("c1-container.json"), """
                {"uuid":"c1","children":["r1"],
                 "afters":[{"name":"finishScenario","attachments":[
                    {"name":"Failure screenshot","type":"image/png","source":"a1-attachment.png"},
                    {"name":"Playwright trace","type":"application/zip","source":"a2-attachment.zip"},
                    {"name":"Escapes","type":"image/png","source":"../../outside.png"}]}]}
                """);
        Files.writeString(results.resolve("c0-container.json"), """
                {"uuid":"c0","children":["r0"],"afters":[{"attachments":[{"type":"image/png","source":"old-attachment.png"}]}]}
                """);
        for (String name : List.of("a0-attachment.txt", "a1-attachment.png", "a2-attachment.zip", "old-attachment.png")) {
            Files.writeString(results.resolve(name), "x");
        }
        Files.writeString(project.resolve("outside.png"), "x");

        McpConfig config = TestSupport.config(project, Map.of());
        ExecutionService executions = new ExecutionService(config, r -> List.of("true"), new AuditLog(config.stateDir().resolve("a.log")));
        ReportReader reader = new ReportReader(project, executions);
        ReportReader.Source source = reader.resolve("workspace");

        JsonNode latest = reader.allureResult(source, "Pay with a valid card").orElseThrow();
        assertThat(latest.path("uuid").asString()).as("last attempt wins").isEqualTo("r1");
        assertThat(reader.attachments(source, latest, "image/png")).extracting(p -> p.getFileName().toString())
                .containsExactly("a1-attachment.png");
        assertThat(reader.attachments(source, latest, "application/zip")).hasSize(1);
        assertThat(reader.attachments(source, latest, "text/plain")).hasSize(1);
        executions.close();
    }
}
