package io.qaplatform.bdd.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.bdd.support.ParameterTypes.Credentials;
import io.qaplatform.ui.pages.DashboardPage;
import io.qaplatform.ui.pages.LoginPage;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class LoginSteps {

    private final ScenarioContext context;

    public LoginSteps(ScenarioContext context) {
        this.context = context;
    }

    @Given("the user is on the login page")
    public void onLoginPage() {
        new LoginPage(context.page()).open();
    }

    @When("the user signs in as {account}")
    public void signIn(Credentials account) {
        new LoginPage(context.page()).signIn(account.username(), account.password());
    }

    @When("the user logs out")
    public void logOut() {
        new DashboardPage(context.page()).header().logout();
    }

    @Then("the dashboard should welcome {string}")
    public void dashboardWelcomes(String displayName) {
        DashboardPage dashboard = new DashboardPage(context.page());
        assertThat(context.page()).hasURL(Pattern.compile(".*/dashboard$"));
        assertThat(dashboard.welcome()).hasText("Welcome, " + displayName);
    }

    @Then("the signed-in user should be shown as {string}")
    public void signedInUserShown(String displayName) {
        assertThat(new DashboardPage(context.page()).header().signedInUser()).hasText(displayName);
    }

    @Then("the login error {string} should be displayed")
    public void loginError(String message) {
        assertThat(new LoginPage(context.page()).error()).hasText(message);
    }

    @Then("the user should be/remain on the login page")
    public void onLoginPageNow() {
        assertThat(context.page()).hasURL(Pattern.compile(".*/login$"));
    }
}
