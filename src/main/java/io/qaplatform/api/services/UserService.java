package io.qaplatform.api.services;

import io.qameta.allure.Step;
import io.qaplatform.api.client.ApiClient;
import io.qaplatform.api.models.User;
import io.qaplatform.api.models.UserPage;
import io.qaplatform.api.models.UserRequest;
import io.qaplatform.api.requests.ApiRequest;
import io.qaplatform.api.responses.ApiResponse;
import io.qaplatform.api.responses.UserResponse;
import io.qaplatform.core.exceptions.FrameworkException;

import java.util.List;

/** Business-level operations on the /api/users resource. */
public class UserService {

    private static final String USERS = "/api/users";
    private static final String USER = "/api/users/{id}";

    private final ApiClient client;

    public UserService(ApiClient client) {
        this.client = client;
    }

    @Step("Create user")
    public UserResponse createUser(UserRequest request) {
        return new UserResponse(client.send(ApiRequest.post(USERS).body(request).build()));
    }

    /** Creates a user and fails fast if the API rejects it; for test setup, not for testing creation itself. */
    public User createUserOrFail(UserRequest request) {
        UserResponse r = createUser(request);
        if (r.getStatusCode() != 201) {
            throw new FrameworkException("Test data setup failed: create user returned " + r.getStatusCode());
        }
        return r.getUser();
    }

    public UserResponse getUser(String id) {
        return new UserResponse(client.send(ApiRequest.get(USER).pathParam("id", id).build()));
    }

    public ApiResponse listUsers(int page, int size) {
        return client.send(ApiRequest.get(USERS).query("page", page).query("size", size).build());
    }

    /** Users whose name or email contains the text (used for cleanup of UI-created data). */
    public List<User> findByEmail(String email) {
        ApiResponse r = client.send(ApiRequest.get(USERS).query("q", email).query("size", 100).build());
        return r.as(UserPage.class).data().stream().filter(u -> u.email().equalsIgnoreCase(email)).toList();
    }

    public UserResponse replaceUser(String id, UserRequest request) {
        return new UserResponse(client.send(ApiRequest.put(USER).pathParam("id", id).body(request).build()));
    }

    public UserResponse patchUser(String id, UserRequest partial) {
        return new UserResponse(client.send(ApiRequest.patch(USER).pathParam("id", id).body(partial).build()));
    }

    public ApiResponse deleteUser(String id) {
        return client.send(ApiRequest.delete(USER).pathParam("id", id).build());
    }

    /** Escape hatch for negative tests that need a hand-built request. */
    public ApiResponse send(ApiRequest request) {
        return client.send(request);
    }
}
