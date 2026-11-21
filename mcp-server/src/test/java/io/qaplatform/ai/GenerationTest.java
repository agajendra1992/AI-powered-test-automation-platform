package io.qaplatform.ai;

import io.qaplatform.ai.generation.DraftAcceptor;
import io.qaplatform.ai.generation.DraftStore;
import io.qaplatform.ai.generation.FeatureGenerator;
import io.qaplatform.ai.generation.GherkinValidator;
import io.qaplatform.ai.generation.JavaCompileCheck;
import io.qaplatform.ai.generation.ProjectContext;
import io.qaplatform.ai.generation.StepDefinitionGenerator;
import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.ai.llm.TokenUsageTracker;
import io.qaplatform.mcp.TestSupport;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.ToolException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature and step generation end to end with a scripted model, against a COPY of the real
 * framework (acceptance writes files, so the real project is never touched).
 */
public class GenerationTest {

    static final String VALID_FEATURE = """
            @api @payment
            Feature: Payment lookup

              @regression @positive
              Scenario: Retrieve an approved payment by its id
                Given the payment API is available
                And an approved payment of 25.00 GBP exists
                When I retrieve the payment by its id
                Then the response status code should be 200
                And the payment status should be "APPROVED"
            """;

    static final String GOOD_JAVA = """
            package io.qaplatform.bdd.steps;

            import io.cucumber.java.en.When;
            import io.qaplatform.bdd.context.Keys;
            import io.qaplatform.bdd.context.ScenarioContext;

            public class PaymentLookupSteps {

                private final ScenarioContext context;

                public PaymentLookupSteps(ScenarioContext context) {
                    this.context = context;
                }

                @When("I retrieve the payment by its id")
                public void retrieve() {
                    context.lastResponse(context.payments().get(context.get(Keys.PAYMENT).id()));
                }
            }
            """;

    private Path project;
    private GherkinValidator realValidator;

    @BeforeClass
    public void copyFramework() throws IOException {
        Path real = TestSupport.frameworkDir();
        realValidator = new GherkinValidator(new FeatureIndex(real), new StepDefinitionIndex(real));
        project = Files.createTempDirectory("qa-framework-copy");
        for (String item : List.of("pom.xml", "src")) {
            Path source = real.resolve(item);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path to = project.resolve(real.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ validator

    @Test
    public void everyExistingFeatureFilePassesTheValidator() throws IOException {
        Path root = TestSupport.frameworkDir().resolve("src/test/resources/features");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".feature")).toList()) {
                // validated as if it were a new file elsewhere, so "duplicates" would flag itself: use its own path
                GherkinValidator.Report r = realValidator.validate(root.relativize(f).toString(), Files.readString(f));
                assertThat(r.valid()).as(f + " " + r.details().get("errors") + " " + r.details().get("tagIssues")).isTrue();
                assertThat(r.readyToRun()).as(f + " undefined " + r.details().get("undefinedSteps")).isTrue();
            }
        }
    }

    @Test
    public void validatorCatchesEachKindOfProblem() {
        assertThat(realValidator.validate("x/a.feature", "Feature: broken\n  Scenario: x\n    Given\n   | a |\n bad line").valid()).isFalse();

        GherkinValidator.Report undefined = realValidator.validate("x/b.feature", VALID_FEATURE);
        assertThat(undefined.valid()).isTrue();
        assertThat(undefined.readyToRun()).isFalse();
        assertThat(undefined.details().get("undefinedSteps")).asString().contains("I retrieve the payment by its id");

        GherkinValidator.Report tags = realValidator.validate("x/c.feature", VALID_FEATURE.replace("@regression @positive", "@Regression"));
        assertThat(tags.details().get("tagIssues")).asString().contains("scope tag", "lower-case");

        GherkinValidator.Report duplicate = realValidator.validate("x/d.feature", """
                @api @payment
                Feature: Copy
                  @regression @negative
                  Scenario: A declined card returns 402
                    When I submit a declined card payment of 10.00 USD
                    Then the response status code should be 402
                """);
        assertThat(duplicate.valid()).isFalse();
        assertThat(duplicate.details().get("duplicates")).asString().contains("same name");

        GherkinValidator.Report secret = realValidator.validate("x/e.feature", VALID_FEATURE.replace("25.00 GBP exists",
                "25.00 GBP exists\n    And the card \"4111 1111 1111 1111\" is used"));
        assertThat(secret.details().get("errors")).asString().contains("literal credential");
    }

    // ------------------------------------------------------------------ generation flow

    @Test
    public void generateValidateRepairGenerateStepsCompileAndAccept() throws Exception {
        McpConfig config = TestSupport.config(project, Map.of());
        FeatureIndex features = new FeatureIndex(project);
        StepDefinitionIndex steps = new StepDefinitionIndex(project);
        GherkinValidator validator = new GherkinValidator(features, steps);
        ProjectContext context = new ProjectContext(project, features, steps);
        DraftStore drafts = new DraftStore(config.stateDir());
        ScriptedLlm llm = new ScriptedLlm()
                // 1st feature attempt: missing scope tag and an invented step that has an existing equivalent
                .answer("```gherkin\n@api @payment\nFeature: Payment lookup\n  Scenario: Retrieve it\n    Given the payment API is available\n"
                        + "    When I fetch payment number 5\n    Then the response status code should be 200\n```")
                // repair attempt: valid
                .answer(VALID_FEATURE)
                // 1st Java attempt: breaks conventions (hard sleep)
                // (string split so the project's own Checkstyle rule does not flag this test)
                .answer(GOOD_JAVA.replace("context.lastResponse(", "Thread." + "sleep(10);\n        context.lastResponse("))
                // repair attempt: good class
                .answer(GOOD_JAVA);
        AiClient ai = new AiClient(llm, new PromptManager(), new TokenUsageTracker(config.stateDir().resolve("u.jsonl"), 1_000_000), 2000);
        Principal alice = new Principal("alice", Role.RUNNER, "test");

        Map<String, Object> feature = new FeatureGenerator(ai, validator, context, drafts)
                .generate(alice, "As a support agent I want to look up a payment by id", "Given an approved payment, retrieving it returns APPROVED",
                        null, "payment", "api");
        String draftId = (String) feature.get("draftId");
        assertThat(feature).containsEntry("status", "PENDING_REVIEW").containsEntry("repairRounds", 1);
        assertThat(feature.get("feature")).asString().contains("Retrieve an approved payment by its id");
        assertThat(feature.get("validation")).asString().contains("I retrieve the payment by its id");
        String prompt = llm.requests.get(0).messages().get(0).content();
        assertThat(prompt).contains("I submit a {card} card payment of {bigdecimal} {word}", "<data source=\"user story\">");

        StepDefinitionGenerator stepGenerator = new StepDefinitionGenerator(ai, validator, steps, features, context, new JavaCompileCheck(config), drafts);
        Map<String, Object> java = stepGenerator.generate(alice, draftId);
        assertThat(java.get("className")).isEqualTo("PaymentLookupSteps");
        assertThat(java.get("checks")).asString().contains("passed=true");

        // Nothing has been written to the project yet
        assertThat(project.resolve("src/test/java/io/qaplatform/bdd/steps/PaymentLookupSteps.java")).doesNotExist();

        AuditLog audit = new AuditLog(config.stateDir().resolve("audit.log"));
        DraftAcceptor acceptor = new DraftAcceptor(drafts, validator, stepGenerator, steps, project, audit);
        Map<String, Object> accepted = acceptor.accept(draftId, new Principal("lead", Role.ADMIN, "test"), "reviewed in QA-123");
        assertThat(accepted.get("writtenFiles")).asString().contains("PaymentLookupSteps.java", "payment/payment_lookup.feature");
        assertThat(project.resolve("src/test/java/io/qaplatform/bdd/steps/PaymentLookupSteps.java")).content().isEqualTo(GOOD_JAVA);
        assertThat(Files.readString(audit.file())).contains("draft_accepted", "QA-123");
        assertThatThrownBy(() -> acceptor.accept(draftId, new Principal("lead", Role.ADMIN, "test"), "again"))
                .isInstanceOf(ToolException.class).hasMessageContaining("ACCEPTED");
    }

    @Test
    public void compileCheckRejectsCodeThatDoesNotCompileOrBreaksConventions() {
        McpConfig config = TestSupport.config(project, Map.of());
        FeatureIndex features = new FeatureIndex(project);
        StepDefinitionIndex steps = new StepDefinitionIndex(project);
        StepDefinitionGenerator generator = new StepDefinitionGenerator(null, new GherkinValidator(features, steps), steps, features,
                new ProjectContext(project, features, steps), new JavaCompileCheck(config), new DraftStore(config.stateDir()));
        String rel = "src/test/java/io/qaplatform/bdd/steps/LookupSteps.java";
        List<String> undefined = List.of("I retrieve the payment by its id");

        Map<String, Object> wrongMethod = generator.check("LookupSteps", rel,
                GOOD_JAVA.replace("PaymentLookupSteps", "LookupSteps").replace("payments().get(", "payments().fetchById("),
                "x.feature", VALID_FEATURE, undefined);
        assertThat(wrongMethod.get("passed")).isEqualTo(false);
        assertThat(wrongMethod.get("compileErrors")).asString().contains("fetchById");

        Map<String, Object> ambiguous = generator.check("LookupSteps", rel,
                GOOD_JAVA.replace("PaymentLookupSteps", "LookupSteps").replace("I retrieve the payment by its id", "the payment API is available"),
                "x.feature", VALID_FEATURE, undefined);
        assertThat(ambiguous.get("problems")).asString().contains("ambiguous");

        Map<String, Object> banned = generator.check("LookupSteps", rel,
                GOOD_JAVA.replace("PaymentLookupSteps", "LookupSteps").replace("import io.cucumber.java.en.When;",
                        "import io.cucumber.java.en.When;\nimport org.openqa.selenium.WebDriver;"), "x.feature", VALID_FEATURE, undefined);
        assertThat(banned.get("problems")).asString().contains("Selenium");
    }
}
