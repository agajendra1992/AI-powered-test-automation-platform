package io.qaplatform.bdd.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.qaplatform.api.models.User;
import io.qaplatform.api.models.UserRequest;
import io.qaplatform.bdd.context.Keys;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.bdd.support.UserFixtures;
import io.qaplatform.ui.pages.LoginPage;
import io.qaplatform.ui.pages.UserProfilePage;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hybrid UI + API flows: data set up through the API, behaviour verified in the browser,
 * and UI changes verified back through the API. The customer's own login is done through
 * the UI because sign-in is the business flow under test.
 */
public class CustomerJourneySteps {

    private final ScenarioContext context;
    private final UserFixtures fixtures;

    public CustomerJourneySteps(ScenarioContext context, UserFixtures fixtures) {
        this.context = context;
        this.fixtures = fixtures;
    }

    @Given("a customer is created through the API")
    public void customerCreated() {
        fixtures.createUser(UserRequest.uniqueWithLogin("Customer"));
    }

    @When("the customer logs into the application")
    public void customerLogsIn() {
        UserRequest customer = context.get(Keys.USER_REQUEST);
        new LoginPage(context.page()).open().signIn(customer.email(), customer.password());
        assertThat(context.page()).hasURL(Pattern.compile(".*/dashboard$"));
    }

    @Then("the customer profile should be displayed")
    public void profileDisplayed() {
        UserProfilePage profile = new UserProfilePage(context.page(), context.get(Keys.USER).id()).open();
        assertThat(profile.heading()).hasText("User profile");
    }

    @Then("the profile details should match the API response")
    public void profileMatchesApi() {
        User api = context.get(Keys.USER);
        UserProfilePage profile = new UserProfilePage(context.page(), api.id());
        assertThat(profile.name()).hasText(api.name());
        assertThat(profile.email()).hasText(api.email());
        assertThat(profile.job()).hasText(api.job());
    }

    @When("the administrator changes the customer's job to {string} in the UI")
    public void changeJobInUi(String job) {
        User user = context.get(Keys.USER);
        UserProfilePage profile = new UserProfilePage(context.page(), user.id()).open();
        assertThat(profile.name()).hasText(user.name());
        profile.update(user.name(), job);
        assertThat(profile.saveStatus()).hasText("Profile saved");
    }

    @Then("the API should return job {string} for the customer")
    public void apiReturnsJob(String job) {
        User fromApi = context.users().getUser(context.get(Keys.USER).id()).getUser();
        assertThat(fromApi.job()).isEqualTo(job);
    }
}
