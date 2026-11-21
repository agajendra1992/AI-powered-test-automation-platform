package io.qaplatform.unit;

import io.qaplatform.core.exceptions.FrameworkException;
import io.qaplatform.core.execution.Sharding;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ShardingTest {

    private final List<String> items = IntStream.range(0, 200).mapToObj(i -> "features/x.feature:" + i).toList();

    @Test
    public void shardsArePartitionWithNoOverlapOrGap() {
        List<String> union = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            union.addAll(Sharding.select(items, i, 4, Function.identity()));
        }
        assertThat(union).hasSize(items.size()).containsExactlyInAnyOrderElementsOf(items);
    }

    @Test
    public void singleShardReturnsEverything() {
        assertThat(Sharding.select(items, 0, 1, Function.identity())).isEqualTo(items);
    }

    @Test
    public void invalidShardIsRejected() {
        assertThatThrownBy(() -> Sharding.select(items, 4, 4, Function.identity())).isInstanceOf(FrameworkException.class);
    }
}
