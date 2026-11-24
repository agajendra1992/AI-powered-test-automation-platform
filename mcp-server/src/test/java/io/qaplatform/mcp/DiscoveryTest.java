package io.qaplatform.mcp;

import io.cucumber.tagexpressions.TagExpressionParser;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discovery against the real framework sources. Expected counts are the ones the framework's own
 * runs report (regression 46, API 20, UI 24, E2E 2, smoke 5), so discovery and execution agree.
 */
public class DiscoveryTest {

    private FeatureIndex features;
    private StepDefinitionIndex steps;

    @BeforeClass
    public void index() {
        features = new FeatureIndex(TestSupport.frameworkDir());
        steps = new StepDefinitionIndex(TestSupport.frameworkDir());
    }

    private int testCases(String expression) {
        return features.select(TagExpressionParser.parse(expression)).stream().mapToInt(ScenarioInfo::runCount).sum();
    }

    @Test
    public void featureFilesParseWithoutErrors() {
        assertThat(features.problems()).isEmpty();
        assertThat(features.features()).extracting(FeatureIndex.FeatureFile::file)
                .contains("login/login.feature", "api/user_api.feature", "e2e/customer_registration.feature");
    }

    @Test
    public void testCaseCountsMatchTheFrameworkSuites() {
        assertThat(testCases("not @quarantine")).isEqualTo(46);
        // Same expressions as ApiRunner / UiRunner (hybrid @e2e scenarios belong to the E2E suite)
        assertThat(testCases("@api and not @e2e and not @quarantine")).isEqualTo(20);
        assertThat(testCases("@ui and not @e2e and not @quarantine")).isEqualTo(24);
        assertThat(testCases("@e2e and not @quarantine")).isEqualTo(2);
        assertThat(testCases("@smoke and not @quarantine")).isEqualTo(5);
    }

    @Test
    public void scenarioIdsAreStableAndResolvable() {
        ScenarioInfo first = features.scenarios().get(0);
        assertThat(first.id()).matches("[a-z_/]+\\.feature:\\d+");
        assertThat(features.byId(first.id())).contains(first);
        assertThat(features.byName(first.name())).contains(first);
    }

    @Test
    public void everyStepInEveryFeatureBindsToExactlyOneDefinition() {
        assertThat(steps.problems()).isEmpty();
        assertThat(steps.definitions()).isNotEmpty();
        assertThat(steps.definitions()).filteredOn(d -> d.method().equals("applicationIsAvailable")).hasSize(2);
        assertThat(steps.parameterTypes()).extracting(StepDefinitionIndex.CustomParameterType::name).contains("account", "card", "auth");
        List<String> unbound = new ArrayList<>();
        for (ScenarioInfo s : features.scenarios()) {
            Stream.concat(s.backgroundSteps().stream(), s.steps().stream())
                    .flatMap(st -> concrete(s, st).stream())
                    .filter(text -> steps.match(text).size() != 1)
                    .forEach(text -> unbound.add(s.id() + " -> " + text + " (" + steps.match(text).size() + " matches)"));
        }
        assertThat(unbound).as("undefined or ambiguous steps").isEmpty();
    }

    @Test
    public void unknownStepIsReportedAsUndefined() {
        assertThat(steps.match("the moon is made of cheese")).isEmpty();
    }

    private static List<String> concrete(ScenarioInfo s, ScenarioInfo.StepInfo st) {
        if (s.examples().isEmpty() || !st.text().contains("<")) {
            return List.of(st.text());
        }
        List<String> texts = new ArrayList<>();
        for (ScenarioInfo.ExamplesInfo e : s.examples()) {
            for (List<String> row : e.rows()) {
                String text = st.text();
                for (int i = 0; i < e.header().size(); i++) {
                    text = text.replace("<" + e.header().get(i) + ">", row.get(i));
                }
                texts.add(text);
            }
        }
        return texts;
    }
}
