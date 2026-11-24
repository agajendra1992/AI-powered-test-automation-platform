# Visual regression testing

Pages are compared, pixel by pixel, with approved screenshots ("baselines"). Playwright Java has no
`toHaveScreenshot`, so the comparison is in the framework: `io.qaplatform.ui.visual`.

## What runs

| Feature | Pages | Tags |
|---|---|---|
| `features/visual/visual.feature` | login, dashboard, payment, files | `@visual @regression @ui` |

The user-management page is left out: its table grows as other scenarios create users, which changes
the page height.

## How a check works

1. `WaitUtils.stableRender`: network idle and web fonts loaded.
2. Full-page screenshot with animations stopped, text caret hidden, CSS-pixel scale, and the page
   object's `dynamicRegions()` masked (the dashboard's user count).
3. Compared with `src/test/resources/visual-baselines/<browser>-<os>/<name>.png` by `ImageDiff`:
   a pixel differs when a colour channel differs by more than `visual.channel.tolerance` (24 of 255,
   absorbs anti-aliasing). The check fails when more than `visual.max.diff.ratio` (0.1%) of
   pixels differ, or when the size changed.
4. On failure the report shows Allure's expected / actual / diff view, plus the three PNGs. Files are
   also written to `target/visual/` and uploaded with the CI failure evidence.

A missing baseline **fails** the check (it is never a silent pass); the actual screenshot is saved for review.

## Baselines

Fonts and rendering differ by browser and operating system, so each `<browser>-<os>` pair has its own set.
Only the **Linux** sets are committed (CI runs on Ubuntu 24.04); `*-mac` and `*-windows` sets are git-ignored
and stay on the machine that made them.

Baselines are written only when asked:

```bash
# Local set for your machine (not committed)
mvn test -DsuiteXmlFile=testng-regression.xml -Dcucumber.filter.tags="@visual" -Dvisual.update=true
```

To change the committed Linux baselines after an intended UI change:

1. Push the change; the regression (or cross-browser) run fails the visual scenarios.
2. Open the Allure report, check each diff is what you intended.
3. Download the `failure-evidence-*` artifact, copy `target/visual/<browser>-linux/<name>-actual.png`
   to `src/test/resources/visual-baselines/<browser>-linux/<name>.png`, and commit it in the same PR,
   so the reviewer sees the image change next to the code change.

A normal run never overwrites a baseline.

## Verification (2026-11-24)

* With no baselines, all 4 scenarios failed with "No baseline …" and saved the actual screenshots.
* After `-Dvisual.update=true`, the compare run passed 4/4.
* Changing the header colour in the demo app failed dashboard, payment and files (6% of pixels) and
  produced diff images and the Allure diff view; login (no header) still matched. The demo app was restored.
* Full regression on chromium: 46/46.
