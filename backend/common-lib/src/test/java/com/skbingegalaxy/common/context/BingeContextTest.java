package com.skbingegalaxy.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BingeContextTest {

    @AfterEach
    void cleanup() {
        BingeContext.clear();
    }

    @Test
    void setBingeId_and_getBingeId_roundtrip() {
        BingeContext.setBingeId(42L);
        assertThat(BingeContext.getBingeId()).isEqualTo(42L);
    }

    @Test
    void getBingeId_returnsNull_whenNotSet() {
        assertThat(BingeContext.getBingeId()).isNull();
    }

    @Test
    void requireBingeId_throws_whenNotSet() {
        assertThatThrownBy(BingeContext::requireBingeId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BingeContext not set");
    }

    @Test
    void requireBingeId_returnsId_whenSet() {
        BingeContext.setBingeId(7L);
        assertThat(BingeContext.requireBingeId()).isEqualTo(7L);
    }

    @Test
    void clear_removesValue() {
        BingeContext.setBingeId(99L);
        BingeContext.clear();
        assertThat(BingeContext.getBingeId()).isNull();
    }

    @Test
    void threadLocal_isIsolatedPerThread() throws InterruptedException {
        BingeContext.setBingeId(1L);

        Thread other = new Thread(() -> {
            assertThat(BingeContext.getBingeId()).isNull();
            BingeContext.setBingeId(2L);
            assertThat(BingeContext.getBingeId()).isEqualTo(2L);
            BingeContext.clear();
        });
        other.start();
        other.join();

        assertThat(BingeContext.getBingeId()).isEqualTo(1L);
    }
}
