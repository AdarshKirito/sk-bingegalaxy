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
