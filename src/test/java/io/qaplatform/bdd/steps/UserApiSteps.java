package io.qaplatform.bdd.steps;

import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.qaplatform.api.auth.AuthProvider;
import io.qaplatform.api.auth.BasicAuth;
import io.qaplatform.api.auth.BearerTokenAuth;
import io.qaplatform.api.auth.NoAuth;
import io.qaplatform.api.auth.OAuth2ClientCredentialsAuth;
import io.qaplatform.api.models.User;
import io.qaplatform.api.models.UserRequest;
import io.qaplatform.api.requests.ApiRequest;
import io.qaplatform.api.responses.UserResponse;
import io.qaplatform.bdd.context.Keys;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.bdd.support.UserFixtures;
import io.qaplatform.core.config.ConfigManager;

import static org.assertj.core.api.Assertions.assertThat;

public class UserApiSteps {

    private final ScenarioContext context;
    private final UserFixtures fixtures;

    public UserApiSteps(ScenarioContext context, UserFixtures fixtures) {
        this.context = context;
        this.fixtures = fixtures;
    }

    @ParameterType("no authentication|an invalid token|basic authentication|an OAuth2 token")
    public AuthProvider auth(String kind) {
        ConfigManager c = ConfigManager.get();
        return switch (kind) {
            case "no authentication" -> new NoAuth();
            case "an invalid token" -> BearerTokenAuth.of("invalid-" + System.nanoTime());
            case "basic authentication" -> new BasicAuth(c.get("auth.username"), c.get("auth.password"));
            case "an OAuth2 token" -> new OAuth2ClientCredentialsAuth(c.get("oauth.token.url"),
                    c.get("oauth.client.id"), c.get("oauth.client.secret"), "users:read");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @When("I send a POST request to create a user")
    public void createUser() {
        UserRequest request = UserRequest.unique("QA Engineer");
        context.put(Keys.USER_REQUEST, request);
        UserResponse response = context.users().createUser(request);
        context.lastResponse(response.raw());
        if (response.getStatusCode() == 201) {
            String id = response.getUser().id();
            context.onCleanup(() -> context.users().deleteUser(id));
        }
    }

    @When("I send a POST request to create a user with name {string} and email {string}")
    public void createInvalidUser(String name, String email) {
        context.lastResponse(context.users().createUser(new UserRequest(name, email, "Tester")).raw());
    }

    @Given("an existing user is available")
    public void existingUser() {
        fixtures.createUser(UserRequest.unique("Developer"));
    }

    @When("I send a GET request for the user")
    public void getUser() {
        context.lastResponse(context.users().getUser(context.get(Keys.USER).id()).raw());
    }

    @When("I request the user with id {string}")
    public void getUserById(String id) {
        context.lastResponse(context.users().getUser(id).raw());
    }

    @Then("the response should contain the user ID")
    public void containsId() {
        assertThat(context.lastResponse().at("/id")).as("user id in %s", context.lastResponse().body()).isPresent();
    }

    @Then("the user details should be correct")
    public void detailsCorrect() {
        User actual = context.lastResponse().as(User.class);
        UserRequest expected = context.get(Keys.USER_REQUEST);
        assertThat(actual).usingRecursiveComparison()
                .comparingOnlyFields("name", "email", "job")
                .isEqualTo(expected);
        assertThat(actual.id()).isEqualTo(context.get(Keys.USER).id());
    }

    @When("I replace the user's job with {string}")
    public void replaceJob(String job) {
        User u = context.get(Keys.USER);
        context.lastResponse(context.users().replaceUser(u.id(), new UserRequest(u.name(), u.email(), job)).raw());
    }

    @When("I patch the user's name to {string}")
    public void patchName(String name) {
        context.lastResponse(context.users().patchUser(context.get(Keys.USER).id(), new UserRequest(name, null, null)).raw());
    }

    @Then("the user should have name {string} and job {string}")
    public void userHas(String name, String job) {
        User actual = context.users().getUser(context.get(Keys.USER).id()).getUser();
        assertThat(actual.name()).isEqualTo(name);
        assertThat(actual.job()).isEqualTo(job);
    }

    @When("I delete the user")
    public void deleteUser() {
        context.lastResponse(context.users().deleteUser(context.get(Keys.USER).id()));
    }

    @When("I list users with page {int} and size {int}")
    public void listUsers(int page, int size) {
        context.lastResponse(context.users().listUsers(page, size));
    }

    @When("I create another user with the same email")
    public void duplicateEmail() {
        String email = context.get(Keys.USER).email();
        context.lastResponse(context.users().createUser(UserRequest.unique("Dup").withEmail(email)).raw());
    }

    @When("I request the user list with {auth}")
    public void listWithAuth(AuthProvider auth) {
        context.lastResponse(context.apiClient(auth).send(ApiRequest.get("/api/users").build()));
    }
}
