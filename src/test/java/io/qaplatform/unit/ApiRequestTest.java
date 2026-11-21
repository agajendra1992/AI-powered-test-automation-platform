package io.qaplatform.unit;

import io.qaplatform.api.requests.ApiRequest;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ApiRequestTest {

    @Test
    public void resolvesAndEncodesPathParams() {
        ApiRequest r = ApiRequest.get("/api/users/{id}/orders/{order}").pathParam("id", "a b").pathParam("order", 7).build();
        assertThat(r.resolvedPath()).isEqualTo("/api/users/a+b/orders/7");
        assertThat(r.pathTemplate()).isEqualTo("/api/users/{id}/orders/{order}");
    }

    @Test
    public void unresolvedPlaceholderFailsFast() {
        ApiRequest r = ApiRequest.delete("/api/users/{id}").build();
        assertThatThrownBy(r::resolvedPath).hasMessageContaining("Unresolved path parameter");
    }

    @Test
    public void requestIsImmutableSnapshotOfBuilder() {
        ApiRequest.Builder b = ApiRequest.post("/x").header("A", "1");
        ApiRequest r = b.build();
        b.header("B", "2");
        assertThat(r.headers()).containsOnlyKeys("A");
    }
}
