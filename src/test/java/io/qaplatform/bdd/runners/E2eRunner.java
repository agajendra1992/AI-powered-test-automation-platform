package io.qaplatform.bdd.runners;

import io.cucumber.testng.CucumberOptions;

/** Hybrid UI + API business flows. Tags can be overridden at run time with -Dcucumber.filter.tags="...". */
@CucumberOptions(
        features = "src/test/resources/features",
        glue = "io.qaplatform.bdd",
        tags = "@e2e and not @quarantine",
        plugin = {
            "summary",
            "html:target/cucumber-reports/e2e.html",
            "json:target/cucumber-reports/e2e.json",
            "rerun:target/cucumber-reports/e2e-rerun.txt",
            "io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm"
        },
        monochrome = true)
public class E2eRunner extends BaseCucumberRunner {
}
