package com.skbingegalaxy.common.context;

import java.util.function.Supplier;

/**
 * Thread-local holder for the currently-selected Binge ID.
 * <p>
 * Populated by BingeContextFilter in each service; cleared after every request.
 * For non-servlet code paths (Kafka listeners, async tasks, etc.) use
 * {@link #execute(Long, Runnable)} or {@link #supply(Long, Supplier)} to
 * guarantee cleanup via try-finally.
 */
public final class BingeContext {

    private static final ThreadLocal<Long> BINGE_ID = new ThreadLocal<>();

    private BingeContext() {}

    public static void setBingeId(Long bingeId) {
        BINGE_ID.set(bingeId);
    }

    public static Long getBingeId() {
        return BINGE_ID.get();
    }

    /**
     * Returns the Binge ID or throws if not set.
     * Use in code paths where a binge context is mandatory.
     */
    public static Long requireBingeId() {
        Long id = BINGE_ID.get();
        if (id == null) {
            throw new IllegalStateException("BingeContext not set — X-Binge-Id header missing or BingeContextFilter not applied");
        }
        return id;
    }

    public static void clear() {
        BINGE_ID.remove();
    }

    /**
     * Run {@code action} with the given binge ID set, guaranteeing cleanup
     * even if the action throws. Use for Kafka listeners, async tasks, and
     * any code path not covered by BingeContextFilter.
     */
    public static void execute(Long bingeId, Runnable action) {
        BINGE_ID.set(bingeId);
        try {
            action.run();
        } finally {
            BINGE_ID.remove();
        }
    }

    /**
     * Run {@code action} with the given binge ID set and return its result,
     * guaranteeing cleanup even if the action throws.
     */
    public static <T> T supply(Long bingeId, Supplier<T> action) {
        BINGE_ID.set(bingeId);
        try {
            return action.get();
        } finally {
            BINGE_ID.remove();
        }
    }
}
