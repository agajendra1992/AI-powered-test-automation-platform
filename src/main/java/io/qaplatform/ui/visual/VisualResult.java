package io.qaplatform.ui.visual;

import java.nio.file.Path;

/**
 * Outcome of one visual check. {@link Status#BASELINE_MISSING} fails: a missing baseline is never
 * treated as a pass, and the actual screenshot is saved so it can be reviewed and committed.
 */
public record VisualResult(String name, Status status, double diffRatio, double maxDiffRatio, Path baseline, Path actual) {

    public enum Status { MATCH, DIFFERENT, SIZE_CHANGED, BASELINE_MISSING, BASELINE_UPDATED }

    public boolean passed() {
        return status == Status.MATCH || status == Status.BASELINE_UPDATED;
    }

    public String message() {
        return switch (status) {
            case MATCH -> String.format("'%s' matches its baseline (%.4f%% of pixels differ, limit %.4f%%)",
                    name, diffRatio * 100, maxDiffRatio * 100);
            case DIFFERENT -> String.format("'%s' differs from its baseline: %.4f%% of pixels differ, limit %.4f%%. "
                    + "Baseline %s, actual %s. If the change is intended, review the diff in the report and "
                    + "update the baseline (docs/VISUAL_TESTING.md).", name, diffRatio * 100, maxDiffRatio * 100, baseline, actual);
            case SIZE_CHANGED -> String.format("'%s' changed size compared with its baseline %s (actual %s)", name, baseline, actual);
            case BASELINE_MISSING -> String.format("No baseline for '%s' at %s. The actual screenshot was saved to %s; "
                    + "review it, then create baselines with -Dvisual.update=true (docs/VISUAL_TESTING.md).", name, baseline, actual);
            case BASELINE_UPDATED -> String.format("Baseline for '%s' written to %s (visual.update=true): review before committing",
                    name, baseline);
        };
    }
}
