package io.qaplatform.mcp.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * A validated request to run the framework. Every field has already passed an allow-list check;
 * {@link #mavenArguments()} turns it into discrete argv entries (never a shell string).
 *
 * @param kind          tool that created it (smoke, regression, tag, scenario, suite, ...)
 * @param suite         TestNG suite file name, e.g. testng-regression.xml
 * @param tagExpression Cucumber tag expression replacing the runner's own tags (nullable)
 * @param scenarioNames exact scenario names, passed as one anchored regex of quoted literals (nullable)
 */
public record ExecutionRequest(
        String kind,
        String suite,
        String tagExpression,
        java.util.List<String> scenarioNames,
        String environment,
        String browser,
        int threads,
        int retries) {

    public List<String> mavenArguments() {
        List<String> args = new ArrayList<>(List.of(
                "-B", "-ntp", "clean", "test",
                "-DsuiteXmlFile=" + suite,
                "-Denv=" + environment,
                "-Dbrowser=" + browser,
                "-Dthreads=" + threads,
                "-Dretry.count=" + retries));
        if (tagExpression != null) {
            args.add("-Dcucumber.filter.tags=" + tagExpression);
        }
        if (scenarioNames != null && !scenarioNames.isEmpty()) {
            // Cucumber treats the name filter as a Java regex: quote each name so it matches literally.
            String names = scenarioNames.stream().map(java.util.regex.Pattern::quote)
                    .collect(java.util.stream.Collectors.joining("|"));
            args.add("-Dcucumber.filter.name=^" + (scenarioNames.size() == 1 ? names : "(?:" + names + ")") + "$");
        }
        return args;
    }
}
