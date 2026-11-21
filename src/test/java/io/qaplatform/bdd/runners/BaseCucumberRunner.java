package io.qaplatform.bdd.runners;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.PickleWrapper;
import io.qaplatform.core.execution.Sharding;
import io.qaplatform.ui.browser.BrowserPool;
import org.testng.ITestContext;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;

import java.util.Arrays;

/**
 * TestNG runs Cucumber: each scenario is one row of a parallel DataProvider.
 * Parallelism = TestNG {@code data-provider-thread-count} (suite XML, or -Dthreads via Maven).
 * Optional CI sharding ({@code -Dshard.index/-Dshard.total}) splits scenarios deterministically.
 */
public abstract class BaseCucumberRunner extends AbstractTestNGCucumberTests {

    @Override
    @DataProvider(parallel = true)
    public Object[][] scenarios(ITestContext context) {
        Object[][] all = super.scenarios(context);
        int total = Integer.getInteger("shard.total", 1);
        int index = Integer.getInteger("shard.index", 0);
        return Sharding.select(Arrays.asList(all), index, total, row -> {
            PickleWrapper p = (PickleWrapper) row[0];
            return p.getPickle().getUri() + ":" + p.getPickle().getScenarioLine();
        }).toArray(Object[][]::new);
    }

    /** Closes every worker's browsers once all scenarios of this runner are done. */
    @AfterClass(alwaysRun = true)
    public void shutdownBrowsers() {
        BrowserPool.shutdownAll();
    }
}
