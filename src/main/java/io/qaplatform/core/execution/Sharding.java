package io.qaplatform.core.execution;

import io.qaplatform.core.exceptions.FrameworkException;

import java.util.List;
import java.util.function.Function;

/**
 * Deterministic sharding: an item belongs to shard {@code hash(key) mod total}.
 * The key (feature URI + line) is stable across machines, so CI shards never overlap or miss items.
 */
public final class Sharding {

    private Sharding() {
    }

    public static <T> List<T> select(List<T> items, int index, int total, Function<T, String> key) {
        if (total < 1 || index < 0 || index >= total) {
            throw new FrameworkException("Invalid shard " + index + "/" + total + " (need 0 <= index < total)");
        }
        if (total == 1) {
            return items;
        }
        return items.stream().filter(i -> Math.floorMod(key.apply(i).hashCode(), total) == index).toList();
    }
}
