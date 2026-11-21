package io.qaplatform.api.models;

import java.util.List;

public record UserPage(int page, int size, int total, List<User> data) {
}
