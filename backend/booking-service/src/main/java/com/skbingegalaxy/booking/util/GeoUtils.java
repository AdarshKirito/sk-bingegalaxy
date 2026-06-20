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

    /**
     * A latitude/longitude bounding box used as the cheap, index-friendly first stage
     * of a proximity query: the DB returns only rows inside the box (an index range
     * scan), then the caller refines with the exact {@link #haversineKm} distance. This
     * is the same "filter by box, then refine" pattern PostGIS/Elasticsearch/H3 use
     * under the hood — done here without any extra infrastructure.
     *
     * <p>{@code minLng}/{@code maxLng} are nullable: when the box cannot be cleanly
     * bounded in longitude (the circle reaches a pole, or a box that would cross the
     * antimeridian) they are {@code null} and the caller should filter by the latitude
     * band only. The latitude band alone already eliminates the vast majority of rows,
     * and the exact Haversine refine still guarantees correctness.
     */
    public record BoundingBox(double minLat, double maxLat, Double minLng, Double maxLng) {
        public boolean isLongitudeBounded() {
            return minLng != null && maxLng != null;
        }
    }

    /**
     * Exact spherical bounding box that fully contains the circle of {@code radiusKm}
     * around ({@code lat},{@code lng}) — the box is always a strict <b>superset</b> of
     * the circle, so the pre-filter can never drop a venue that is actually within range
     * (no false negatives at the edge).
     *
     * <p>Uses the standard "points within a distance" construction on the same sphere as
     * {@link #haversineKm} (radius {@link #EARTH_RADIUS_KM}), so box and refine agree:
     * <pre>
     *   Δlat = r/R                                (exact)
     *   Δlng = asin( sin(r/R) / cos(lat) )        (exact; widest longitude offset)
     * </pre>
     * A naive linear {@code r/111.32} / {@code …/cos(lat)} under-estimates both spans
     * (the meridian degree is shorter near the equator; the circle is widest in
     * longitude at its poleward edge), which would silently exclude edge venues.
     *
     * <p>When the circle reaches a pole (so longitude is unbounded) or the box would
     * cross the antimeridian, longitude is left null and the caller scans the latitude
     * band only.
     */
    public static BoundingBox boundingBox(double lat, double lng, double radiusKm) {
        validateLatitude(lat);
        validateLongitude(lng);
        double angularRadius = Math.max(0.0, radiusKm) / EARTH_RADIUS_KM; // radians
        double latRad = Math.toRadians(lat);
        double lngRad = Math.toRadians(lng);

        double minLatRad = latRad - angularRadius;
        double maxLatRad = latRad + angularRadius;

        double minLat = Math.max(-90.0, Math.toDegrees(minLatRad));
        double maxLat = Math.min(90.0, Math.toDegrees(maxLatRad));

        // Circle stays clear of both poles -> longitude has a finite, exact bound.
        if (minLatRad > MIN_LAT_RAD && maxLatRad < MAX_LAT_RAD) {
            double deltaLng = Math.asin(Math.min(1.0, Math.sin(angularRadius) / Math.cos(latRad)));
            double minLng = Math.toDegrees(lngRad - deltaLng);
            double maxLng = Math.toDegrees(lngRad + deltaLng);
            // Box crosses the antimeridian (±180): fall back to a latitude-band scan
            // rather than emit an inverted range. Correct, just slightly broader.
            if (minLng < -180.0 || maxLng > 180.0) {
                return new BoundingBox(minLat, maxLat, null, null);
            }
            return new BoundingBox(minLat, maxLat, minLng, maxLng);
        }
        // A pole is within the circle -> every longitude is in range.
        return new BoundingBox(minLat, maxLat, null, null);
    }

    private static final double MIN_LAT_RAD = Math.toRadians(-90.0);
    private static final double MAX_LAT_RAD = Math.toRadians(90.0);

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
