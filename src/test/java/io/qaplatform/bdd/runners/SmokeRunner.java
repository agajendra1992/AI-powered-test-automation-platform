package io.qaplatform.bdd.runners;

import io.cucumber.testng.CucumberOptions;

/** Fast, critical-path checks for every pull request. Tags can be overridden at run time with -Dcucumber.filter.tags="...". */
@CucumberOptions(
        features = "src/test/resources/features",
        glue = "io.qaplatform.bdd",
        tags = "@smoke and not @quarantine",
        plugin = {
            "summary",
            "html:target/cucumber-reports/smoke.html",
            "json:target/cucumber-reports/smoke.json",
            "rerun:target/cucumber-reports/smoke-rerun.txt",
            "io.qameta.allure.cucumber8jvm.AllureCucumber8Jvm"
        },
        monochrome = true)
public class SmokeRunner extends BaseCucumberRunner {
}
