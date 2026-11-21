package io.qaplatform.core.reporting;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run-wide API timing statistics per endpoint (method + path template), exported in the
 * execution summary. Thread-safe; values are aggregates only, never request data.
 */
public final class ApiMetrics {

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();

    private ApiMetrics() {
    }

    public static void record(String endpoint, long ms) {
        STATS.computeIfAbsent(endpoint, k -> new Stat()).add(ms);
    }

    public static Map<String, Map<String, Long>> snapshot() {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        STATS.forEach((k, s) -> out.put(k, s.toMap()));
        return out;
    }

    private static final class Stat {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong max = new AtomicLong();

        void add(long ms) {
            count.incrementAndGet();
            total.addAndGet(ms);
            max.accumulateAndGet(ms, Math::max);
        }

        Map<String, Long> toMap() {
            long c = count.get();
            return Map.of("count", c, "avgMs", c == 0 ? 0 : total.get() / c, "maxMs", max.get());
        }
    }
}
