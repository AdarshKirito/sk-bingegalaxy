package com.skbingegalaxy.booking.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoUtilsTest {

    @Test
    void haversine_samePoint_isZero() {
        assertThat(GeoUtils.haversineKm(12.9716, 77.5946, 12.9716, 77.5946)).isZero();
    }

    @Test
    void haversine_knownDistance_bangaloreToMysore() {
        // Bengaluru (MG Road) -> Mysuru (Palace): great-circle ~127 km.
        double km = GeoUtils.haversineKm(12.9716, 77.5946, 12.2958, 76.6394);
        assertThat(km).isCloseTo(127.0, org.assertj.core.data.Offset.offset(3.0));
    }

    @Test
    void haversine_isSymmetric() {
        double ab = GeoUtils.haversineKm(40.7128, -74.0060, 51.5074, -0.1278);
        double ba = GeoUtils.haversineKm(51.5074, -0.1278, 40.7128, -74.0060);
        assertThat(ab).isCloseTo(ba, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void haversine_rejectsOutOfRangeLatitude() {
        assertThatThrownBy(() -> GeoUtils.haversineKm(91, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void haversine_rejectsOutOfRangeLongitude() {
        assertThatThrownBy(() -> GeoUtils.haversineKm(0, 181, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundKm_keepsOneDecimal() {
        assertThat(GeoUtils.roundKm(2.3456)).isEqualTo(2.3);
        assertThat(GeoUtils.roundKm(2.35)).isEqualTo(2.4);
    }

    @Test
    void validators_handleNullAndRange() {
        assertThat(GeoUtils.isValidLatitude(null)).isFalse();
        assertThat(GeoUtils.isValidLatitude(45.0)).isTrue();
        assertThat(GeoUtils.isValidLatitude(95.0)).isFalse();
        assertThat(GeoUtils.isValidLatitude(Double.NaN)).isFalse();
        assertThat(GeoUtils.isValidLongitude(null)).isFalse();
        assertThat(GeoUtils.isValidLongitude(-120.0)).isTrue();
        assertThat(GeoUtils.isValidLongitude(200.0)).isFalse();
    }
}
