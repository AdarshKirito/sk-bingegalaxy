package com.skbingegalaxy.booking.util;

/**
 * Geospatial helpers for venue-proximity ranking.
 *
 * <p>Distances use the <b>Haversine</b> great-circle formula on a spherical-Earth
 * model (mean radius 6371.0088 km). At city/metro scale this is accurate to a few
 * tenths of a percent — more than enough to rank "nearest venues" and far simpler
 * (and dependency-free) than pulling in PostGIS. If the venue count per deployment
 * ever grows into the tens of thousands, the upgrade path is a PostGIS {@code
 * geography} column + GiST index doing the filtering in-database; the bounding-box
 * pre-filter in {@code BingeRepository} already mirrors that access pattern.
 */
public final class GeoUtils {

    /** Mean Earth radius in kilometres (IUGG mean radius R1). */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoUtils() {}

    /**
     * Great-circle distance between two WGS-84 points, in kilometres.
     *
     * @throws IllegalArgumentException if any coordinate is out of range or non-finite
     */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        validateLatitude(lat1);
        validateLongitude(lon1);
        validateLatitude(lat2);
        validateLongitude(lon2);

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double a = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return EARTH_RADIUS_KM * c;
    }

    /** Round a kilometre distance to one decimal place for stable client display. */
    public static double roundKm(double km) {
        return Math.round(km * 10.0) / 10.0;
    }

    /** Approximate kilometres per degree of latitude (constant; meridians are ~uniform). */
    public static final double KM_PER_DEGREE_LAT = 111.32;

    /**
     * A latitude/longitude bounding box used as the cheap, index-friendly first stage
     * of a proximity query: the DB returns only rows inside the box (an index range
     * scan), then the caller refines with the exact {@link #haversineKm} distance. This
     * is the same "filter by box, then refine" pattern PostGIS/Elasticsearch/H3 use
     * under the hood — done here without any extra infrastructure.
     *
     * <p>{@code minLng}/{@code maxLng} are nullable: when the box cannot be cleanly
     * bounded in longitude (near a pole, a radius large enough to wrap, or a box that
     * crosses the antimeridian) they are {@code null} and the caller should filter by the
     * latitude band only. The latitude band alone already eliminates the vast majority of
     * rows, and the exact Haversine refine still guarantees correctness.
     */
    public record BoundingBox(double minLat, double maxLat, Double minLng, Double maxLng) {
        public boolean isLongitudeBounded() {
            return minLng != null && maxLng != null;
        }
    }

    /**
     * Compute the bounding box that fully contains the circle of {@code radiusKm} around
     * ({@code lat},{@code lng}). Latitude is always bounded (and clamped to the poles).
     * Longitude is bounded only when it is safe to do so (see {@link BoundingBox}).
     */
    public static BoundingBox boundingBox(double lat, double lng, double radiusKm) {
        validateLatitude(lat);
        validateLongitude(lng);
        double safeRadius = Math.max(0.0, radiusKm);
        double dLat = safeRadius / KM_PER_DEGREE_LAT;
        double minLat = Math.max(-90.0, lat - dLat);
        double maxLat = Math.min(90.0, lat + dLat);

        double cosLat = Math.cos(Math.toRadians(lat));
        // Near a pole the longitude span explodes — don't bound longitude.
        if (cosLat < 1e-6) {
            return new BoundingBox(minLat, maxLat, null, null);
        }
        double dLng = safeRadius / (KM_PER_DEGREE_LAT * cosLat);
        // Radius wide enough to span half the globe in longitude — no useful bound.
        if (dLng >= 180.0) {
            return new BoundingBox(minLat, maxLat, null, null);
        }
        double minLng = lng - dLng;
        double maxLng = lng + dLng;
        // Box crosses the antimeridian (±180): fall back to a latitude-band scan rather
        // than emit an inverted range. Correct, just slightly broader before the refine.
        if (minLng < -180.0 || maxLng > 180.0) {
            return new BoundingBox(minLat, maxLat, null, null);
        }
        return new BoundingBox(minLat, maxLat, minLng, maxLng);
    }

    public static boolean isValidLatitude(Double lat) {
        return lat != null && Double.isFinite(lat) && lat >= -90.0 && lat <= 90.0;
    }

    public static boolean isValidLongitude(Double lon) {
        return lon != null && Double.isFinite(lon) && lon >= -180.0 && lon <= 180.0;
    }

    private static void validateLatitude(double lat) {
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude out of range [-90, 90]: " + lat);
        }
    }

    private static void validateLongitude(double lon) {
        if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("Longitude out of range [-180, 180]: " + lon);
        }
    }
}
