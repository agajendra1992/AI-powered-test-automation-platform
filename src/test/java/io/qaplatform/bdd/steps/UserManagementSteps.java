package io.qaplatform.bdd.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.qaplatform.api.models.User;
import io.qaplatform.api.models.UserRequest;
import io.qaplatform.bdd.context.Keys;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.bdd.support.UserFixtures;
import io.qaplatform.core.data.TestDataManager;
import io.qaplatform.ui.components.ModalComponent;
import io.qaplatform.ui.network.NetworkInterceptor;
import io.qaplatform.ui.pages.DashboardPage;
import io.qaplatform.ui.pages.FilesPage;
import io.qaplatform.ui.pages.UserManagementPage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

public class UserManagementSteps {

    private final ScenarioContext context;
    private final UserFixtures fixtures;

    public UserManagementSteps(ScenarioContext context, UserFixtures fixtures) {
        this.context = context;
        this.fixtures = fixtures;
    }

    @Given("a user exists in the system")
    public void userExists() {
        fixtures.createUser(UserRequest.unique("Analyst"));
    }

    @Given("the administrator is on the user management page")
    public void onUserManagement() {
        new UserManagementPage(context.page()).open();
    }

    /** DataTable gives name/job (and optionally email); a unique email is generated when absent. */
    @When("the administrator adds a user with:")
    public void addsUser(Map<String, String> fields) {
        String email = fields.getOrDefault("email", TestDataManager.uniqueEmail("ui"));
        String name = fields.get("name") + ("not-an-email".equals(email) ? "" : " " + TestDataManager.uniqueSuffix());
        UserRequest request = new UserRequest(name, email, fields.getOrDefault("job", ""));
        context.put(Keys.USER_REQUEST, request);
        new UserManagementPage(context.page()).addUser(request.name(), request.email(), request.job());
        context.onCleanup(() -> context.users().findByEmail(request.email())
                .forEach(u -> context.users().deleteUser(u.id())));
    }

    @Then("the new user should be listed with job {string}")
    public void userListed(String job) {
        UserRequest request = context.get(Keys.USER_REQUEST);
        UserManagementPage page = new UserManagementPage(context.page());
        var row = page.table().rowContaining(request.email());
        assertThat(row).hasCount(1);
        assertThat(page.table().cell(row, "Name")).hasText(request.name());
        assertThat(page.table().cell(row, "Job")).hasText(job);
    }

    @Then("the add user form should show an error containing {string}")
    public void addUserError(String text) {
        assertThat(new ModalComponent(context.page(), "Add user").error()).containsText(text);
    }

    @When("the administrator searches for that user")
    public void searchesForUser() {
        new UserManagementPage(context.page()).searchFor(context.get(Keys.USER).email());
    }

    @Then("exactly {int} user(s) should be listed")
    public void usersListed(int count) {
        assertThat(new UserManagementPage(context.page()).table().rows()).hasCount(count);
    }

    @When("the administrator deletes that user and confirms")
    public void deletesUser() {
        new UserManagementPage(context.page()).deleteUser(context.get(Keys.USER).name());
    }

    @Then("the user list should be empty")
    public void listEmpty() {
        UserManagementPage page = new UserManagementPage(context.page());
        assertThat(page.emptyState()).isVisible();
        assertThat(page.table().rows()).hasCount(0);
    }

    @Given("the user service is failing")
    public void userServiceFailing() {
        context.page(); // start the browser so the route applies to it
        new NetworkInterceptor(context.browser().context())
                .stubJson("**/api/users**", 503, "{\"detail\":\"Service unavailable\"}");
    }

    @When("the administrator opens the dashboard")
    public void opensDashboard() {
        new DashboardPage(context.page()).open();
    }

    @Then("the dashboard should show a data loading error")
    public void dashboardError() {
        DashboardPage dashboard = new DashboardPage(context.page());
        assertThat(dashboard.loadError()).isVisible();
        assertThat(dashboard.welcome()).hasText(Pattern.compile("^Welcome, "));
    }

    @When("the administrator uploads the file {string}")
    public void uploads(String fileName) throws URISyntaxException {
        Path file = Path.of(getClass().getClassLoader().getResource("testdata/" + fileName).toURI());
        new FilesPage(context.page()).open().upload(file);
    }

    @Then("the upload should be confirmed for {string}")
    public void uploadConfirmed(String fileName) {
        // Playwright evaluates regexes in JavaScript: no Java-only syntax such as \Q...\E.
        var result = new FilesPage(context.page()).uploadResult();
        assertThat(result).hasText(Pattern.compile("^Uploaded .+ \\(\\d+ bytes\\)$"));
        assertThat(result).containsText(fileName);
    }

    @Then("the downloaded users report should contain that user")
    public void reportContainsUser() throws IOException {
        User user = context.get(Keys.USER);
        Path report = new FilesPage(context.page()).downloadUsersReport();
        assertThat(Files.readString(report)).contains(user.id()).contains(user.email());
    }
}
