package com.skbingegalaxy.common.context;

/**
 * Thread-local holder for the currently-selected Binge ID.
 * Populated by BingeContextFilter in each service; cleared after every request.
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
}
