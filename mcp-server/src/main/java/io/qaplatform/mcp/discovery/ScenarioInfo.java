package io.qaplatform.mcp.discovery;

import java.util.List;

/**
 * A scenario or scenario outline as written in a feature file.
 *
 * @param id           stable id {@code <path relative to features/>:<line>}, e.g. {@code login/login.feature:12}
 * @param tags         effective tags (feature + rule + scenario; outline examples add their own per row)
 * @param runCount     how many test cases Cucumber generates (1, or the number of example rows)
 */
public record ScenarioInfo(
        String id,
        String file,
        int line,
        String feature,
        String rule,
        String keyword,
        String name,
        List<String> tags,
        List<StepInfo> backgroundSteps,
        List<StepInfo> steps,
        List<ExamplesInfo> examples,
        int runCount) {

    public record StepInfo(int line, String keyword, String text, boolean hasDataTable, boolean hasDocString) {
    }

    public record ExamplesInfo(int line, String name, List<String> tags, List<String> header, List<List<String>> rows) {
    }

    /** Step text as Cucumber will match it: outline placeholders replaced, once per example row. */
    public List<String> concreteTexts(StepInfo step) {
        if (examples.isEmpty() || !step.text().contains("<")) {
            return List.of(step.text());
        }
        List<String> texts = new java.util.ArrayList<>();
        for (ExamplesInfo e : examples) {
            for (List<String> row : e.rows()) {
                String text = step.text();
                for (int i = 0; i < e.header().size() && i < row.size(); i++) {
                    text = text.replace("<" + e.header().get(i) + ">", row.get(i));
                }
                texts.add(text);
            }
        }
        return texts;
    }

    /**
     * Steps normalised for duplicate detection: lower case, quoted values, outline placeholders and
     * numbers replaced, whitespace collapsed.
     */
    public List<String> normalizedSteps() {
        return steps.stream()
                .map(st -> st.text().toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("\"[^\"]*\"", "\"_\"")
                        .replaceAll("<[^>]+>", "<_>")
                        .replaceAll("\\d+(\\.\\d+)?", "#")
                        .replaceAll("\\s+", " ").trim())
                .toList();
    }

    /** All tags that could select at least one test case of this scenario (outline example tags included). */
    public List<List<String>> tagSetsPerTestCase() {
        if (examples.isEmpty()) {
            return List.of(tags);
        }
        return examples.stream()
                .flatMap(e -> e.rows().stream().map(r -> {
                    List<String> all = new java.util.ArrayList<>(tags);
                    all.addAll(e.tags());
                    return List.copyOf(all);
                }))
                .toList();
    }
}
