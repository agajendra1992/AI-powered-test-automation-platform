package io.qaplatform.bdd.runners;

import io.cucumber.testng.CucumberOptions;

/** API-only scenarios (no browser is started). Tags can be overridden at run time with -Dcucumber.filter.tags="...". */
@CucumberOptions(
        features = "src/test/resources/features",
        glue = "io.qaplatform.bdd",
        tags = "@api and not @e2e and not @quarantine",
        plugin = {
            "summary",
            "html:target/cucumber-reports/api.html",
            "json:target/cucumber-reports/api.json",
            "rerun:target/cucumber-reports/api-rerun.txt",
            "io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm"
        },
        monochrome = true)
public class ApiRunner extends BaseCucumberRunner {
}
