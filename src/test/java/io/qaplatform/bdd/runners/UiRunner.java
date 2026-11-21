package io.qaplatform.bdd.runners;

import io.cucumber.testng.CucumberOptions;

/** Browser-only scenarios. Tags can be overridden at run time with -Dcucumber.filter.tags="...". */
@CucumberOptions(
        features = "src/test/resources/features",
        glue = "io.qaplatform.bdd",
        tags = "@ui and not @e2e and not @quarantine",
        plugin = {
            "summary",
            "html:target/cucumber-reports/ui.html",
            "json:target/cucumber-reports/ui.json",
            "rerun:target/cucumber-reports/ui-rerun.txt",
            "io.qameta.allure.cucumber8jvm.AllureCucumber8Jvm"
        },
        monochrome = true)
public class UiRunner extends BaseCucumberRunner {
}
