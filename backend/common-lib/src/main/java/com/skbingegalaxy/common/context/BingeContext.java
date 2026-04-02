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

    public static void clear() {
        BINGE_ID.remove();
    }
}
