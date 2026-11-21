package io.qaplatform.bdd.support;

import io.qaplatform.api.models.User;
import io.qaplatform.api.models.UserRequest;
import io.qaplatform.bdd.context.Keys;
import io.qaplatform.bdd.context.ScenarioContext;

/**
 * API-based test data setup with guaranteed cleanup. Scenario-scoped (injected by PicoContainer).
 */
public class UserFixtures {

    private final ScenarioContext context;

    public UserFixtures(ScenarioContext context) {
        this.context = context;
    }

    /** Creates a unique user through the API, stores it in the context and deletes it after the scenario. */
    public User createUser(UserRequest request) {
        User user = context.users().createUserOrFail(request);
        context.put(Keys.USER, user);
        context.put(Keys.USER_REQUEST, request);
        context.onCleanup(() -> context.users().deleteUser(user.id()));
        return user;
    }
}
