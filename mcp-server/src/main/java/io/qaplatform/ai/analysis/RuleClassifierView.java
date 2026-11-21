package io.qaplatform.ai.analysis;

import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.tools.ToolException;

/** Cheap per-scenario rule classification (no AI), used by run summaries. */
public final class RuleClassifierView {

    private final FailureAnalyzer analyzer;
    private final RuleClassifier rules = new RuleClassifier();

    public RuleClassifierView(FailureAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public String categoryFor(ReportReader.Source source, String scenario) {
        try {
            RuleClassifier.Classification c = rules.classify(analyzer.collect(source, scenario));
            return c.category() + " (" + c.rule() + ", " + c.confidence() + ")";
        } catch (ToolException e) {
            return "UNKNOWN (no failure record)";
        }
    }
}
