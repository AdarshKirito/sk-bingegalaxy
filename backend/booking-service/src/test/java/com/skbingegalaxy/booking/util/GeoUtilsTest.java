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
    void boundingBox_normalCase_isLongitudeBounded_andContainsRadius() {
        // Bengaluru, 50 km radius.
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(12.9716, 77.5946, 50);
        assertThat(box.isLongitudeBounded()).isTrue();
        // ~0.45 deg of latitude for 50 km.
        assertThat(box.maxLat() - box.minLat()).isCloseTo(0.90, org.assertj.core.data.Offset.offset(0.05));
        // The point itself is inside the box.
        assertThat(12.9716).isBetween(box.minLat(), box.maxLat());
        assertThat(77.5946).isBetween(box.minLng(), box.maxLng());
    }

    @Test
    void boundingBox_containsVenuesAtTheRadiusEdge_noFalseNegatives() {
        // The whole point of the box: it must never exclude a venue that is actually
        // within the radius. Probe the four cardinal points at ~radius distance and the
        // box-corner latitude (where the longitude span is widest) and assert each is
        // inside the box. A linear approximation would fail the east/west probes.
        double lat = 28.6139, lng = 77.2090, radius = 200; // Delhi, higher latitude
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(lat, lng, radius);
        assertThat(box.isLongitudeBounded()).isTrue();

        // Probe at 99.5% of the radius: comfortably inside (so no float-equality flake at
        // the exact edge) yet tight enough to catch the ~2% under-coverage the old linear
        // approximation had — those probes would have fallen OUTSIDE the linear box.
        double probe = 0.995 * radius;
        double dLatDeg = Math.toDegrees(probe / GeoUtils.EARTH_RADIUS_KM);
        assertThat(lat + dLatDeg).isBetween(box.minLat(), box.maxLat()); // due north
        assertThat(lat - dLatDeg).isBetween(box.minLat(), box.maxLat()); // due south

        double dLngDeg = Math.toDegrees(Math.asin(
            Math.sin(probe / GeoUtils.EARTH_RADIUS_KM) / Math.cos(Math.toRadians(lat))));
        assertThat(lng + dLngDeg).isBetween(box.minLng(), box.maxLng()); // due east
        assertThat(lng - dLngDeg).isBetween(box.minLng(), box.maxLng()); // due west
        // Sanity: the east probe really is ~radius away (great-circle).
        assertThat(GeoUtils.haversineKm(lat, lng, lat, lng + dLngDeg))
            .isCloseTo(probe, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    void boundingBox_nearPole_dropsLongitudeBound() {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(89.999, 10, 50);
        assertThat(box.isLongitudeBounded()).isFalse();
        assertThat(box.minLng()).isNull();
        assertThat(box.maxLng()).isNull();
    }

    @Test
    void boundingBox_acrossAntimeridian_dropsLongitudeBound() {
        // Near +180 longitude, a 50 km box would spill past 180 -> latitude-band fallback.
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(0.0, 179.9, 50);
        assertThat(box.isLongitudeBounded()).isFalse();
    }

    @Test
    void boundingBox_clampsLatitudeToPoles() {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(89.0, 0.0, 500);
        assertThat(box.maxLat()).isLessThanOrEqualTo(90.0);
        assertThat(box.minLat()).isGreaterThanOrEqualTo(-90.0);
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
