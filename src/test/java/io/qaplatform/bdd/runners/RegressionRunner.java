package io.qaplatform.bdd.runners;

import io.cucumber.testng.CucumberOptions;

/** Full regression: every scenario except quarantined ones. Tags can be overridden at run time with -Dcucumber.filter.tags="...". */
@CucumberOptions(
        features = "src/test/resources/features",
        glue = "io.qaplatform.bdd",
        tags = "not @quarantine",
        plugin = {
            "summary",
            "html:target/cucumber-reports/regression.html",
            "json:target/cucumber-reports/regression.json",
            "rerun:target/cucumber-reports/regression-rerun.txt",
            "io.qameta.allure.cucumber8jvm.AllureCucumber8Jvm"
        },
        monochrome = true)
public class RegressionRunner extends BaseCucumberRunner {
}
