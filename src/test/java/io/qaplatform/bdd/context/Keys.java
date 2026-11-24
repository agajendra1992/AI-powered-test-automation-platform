package io.qaplatform.bdd.context;

import io.qaplatform.api.models.Payment;
import io.qaplatform.api.models.User;
import io.qaplatform.api.models.UserRequest;
import io.qaplatform.ui.pages.BasePage;

/** All scenario data keys in one place. */
public final class Keys {

    public static final ScenarioContext.Key<User> USER = new ScenarioContext.Key<>("user", User.class);
    public static final ScenarioContext.Key<UserRequest> USER_REQUEST =
            new ScenarioContext.Key<>("userRequest", UserRequest.class);
    /** The page most recently opened by name ("When the user opens the ... page"). */
    public static final ScenarioContext.Key<BasePage> CURRENT_PAGE = new ScenarioContext.Key<>("currentPage", BasePage.class);
    public static final ScenarioContext.Key<Payment> PAYMENT = new ScenarioContext.Key<>("payment", Payment.class);

    private Keys() {
    }
}
