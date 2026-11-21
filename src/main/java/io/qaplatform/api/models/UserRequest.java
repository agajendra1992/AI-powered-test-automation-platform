package io.qaplatform.api.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.qaplatform.core.data.TestDataManager;

/**
 * Payload for create (POST), replace (PUT) and partial update (PATCH: null fields are omitted).
 * {@code password} is optional and only sent when the user must be able to sign in.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserRequest(String name, String email, String job, String password) {

    public UserRequest(String name, String email, String job) {
        this(name, email, job, null);
    }

    /** Valid user with a unique email, safe for parallel runs. */
    public static UserRequest unique(String job) {
        String suffix = TestDataManager.uniqueSuffix();
        return new UserRequest("Auto User " + suffix, TestDataManager.uniqueEmail("auto"), job);
    }

    /** Same as {@link #unique(String)} plus a generated password, for users who log into the UI. */
    public static UserRequest uniqueWithLogin(String job) {
        UserRequest u = unique(job);
        return new UserRequest(u.name(), u.email(), u.job(), TestDataManager.generatedPassword());
    }

    public UserRequest withName(String newName) { return new UserRequest(newName, email, job, password); }
    public UserRequest withEmail(String newEmail) { return new UserRequest(name, newEmail, job, password); }
    public UserRequest withJob(String newJob) { return new UserRequest(name, email, newJob, password); }
    public UserRequest withoutPassword() { return new UserRequest(name, email, job, null); }

    /** Never print the password. */
    @Override
    public String toString() {
        return "UserRequest[name=" + name + ", email=" + email + ", job=" + job + ", password=" + (password == null ? "none" : "****") + "]";
    }
}
