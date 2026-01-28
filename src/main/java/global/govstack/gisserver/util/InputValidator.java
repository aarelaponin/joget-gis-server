package global.govstack.gisserver.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Input validation utilities for GIS API.
 *
 * Per spec sections 7.2 and 8.2:
 * - Vertex count: Maximum 10,000 vertices per geometry
 * - Payload size: Maximum 5 MB request body
 * - Coordinates: Must be valid WGS84 range (-180 to 180 longitude, -90 to 90 latitude)
 */
public class InputValidator {

    // Limits per spec sections 7.2 and 8.2
    public static final int MAX_VERTICES = 10_000;
    public static final int MAX_REQUEST_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    public static final int MAX_RESPONSE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    // WGS84 coordinate bounds
    public static final double MIN_LONGITUDE = -180.0;
    public static final double MAX_LONGITUDE = 180.0;
    public static final double MIN_LATITUDE = -90.0;
    public static final double MAX_LATITUDE = 90.0;

    /**
     * Validation result containing error details.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorCode;
        private final String message;
        private final String field;
        private final String reason;
        private final String suggestion;

        private ValidationResult(boolean valid, String errorCode, String message,
                                  String field, String reason, String suggestion) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.message = message;
            this.field = field;
            this.reason = reason;
            this.suggestion = suggestion;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null, null, null, null, null);
        }

        public static ValidationResult error(String errorCode, String message,
                                              String field, String reason, String suggestion) {
            return new ValidationResult(false, errorCode, message, field, reason, suggestion);
        }

        public boolean isValid() { return valid; }
        public String getErrorCode() { return errorCode; }
        public String getMessage() { return message; }
        public String getField() { return field; }
        public String getReason() { return reason; }
        public String getSuggestion() { return suggestion; }

        public JSONObject toDetailsJson() {
            if (valid) return null;
            JSONObject details = new JSONObject();
            if (field != null) details.put("field", field);
            if (reason != null) details.put("reason", reason);
            if (suggestion != null) details.put("suggestion", suggestion);
            return details.length() > 0 ? details : null;
        }
    }

    /**
     * Validate request body size.
     *
     * @param requestBody The request body string
     * @return ValidationResult
     */
    public static ValidationResult validateRequestSize(String requestBody) {
        if (requestBody == null) {
            return ValidationResult.success();
        }

        int sizeBytes = requestBody.getBytes().length;
        if (sizeBytes > MAX_REQUEST_SIZE_BYTES) {
            double sizeMB = sizeBytes / (1024.0 * 1024.0);
            return ValidationResult.error(
                "PAYLOAD_TOO_LARGE",
                String.format("Request body size (%.2f MB) exceeds maximum allowed (5 MB)", sizeMB),
                "body",
                String.format("Request body is %d bytes, maximum is %d bytes", sizeBytes, MAX_REQUEST_SIZE_BYTES),
                "Reduce the size of the geometry or split into multiple requests"
            );
        }

        return ValidationResult.success();
    }

    /**
     * Validate vertex count of a JTS geometry.
     *
     * @param geometry The JTS geometry
     * @return ValidationResult
     */
    public static ValidationResult validateVertexCount(Geometry geometry) {
        if (geometry == null) {
            return ValidationResult.success();
        }

        int vertexCount = geometry.getNumPoints();
        if (vertexCount > MAX_VERTICES) {
            return ValidationResult.error(
                "TOO_MANY_VERTICES",
                String.format("Geometry has %d vertices, maximum allowed is %d", vertexCount, MAX_VERTICES),
                "geometry",
                String.format("Vertex count %d exceeds limit of %d", vertexCount, MAX_VERTICES),
                "Use the /gis/simplify endpoint to reduce vertex count before processing"
            );
        }

        return ValidationResult.success();
    }

    /**
     * Validate that all coordinates in a JTS geometry are within WGS84 bounds.
     *
     * @param geometry The JTS geometry
     * @return ValidationResult
     */
    public static ValidationResult validateCoordinateRange(Geometry geometry) {
        if (geometry == null) {
            return ValidationResult.success();
        }

        Coordinate[] coordinates = geometry.getCoordinates();
        List<String> invalidCoords = new ArrayList<>();

        for (int i = 0; i < coordinates.length; i++) {
            Coordinate coord = coordinates[i];
            double lon = coord.x;
            double lat = coord.y;

            if (lon < MIN_LONGITUDE || lon > MAX_LONGITUDE) {
                invalidCoords.add(String.format("coordinate[%d]: longitude %.6f out of range [-180, 180]", i, lon));
            }
            if (lat < MIN_LATITUDE || lat > MAX_LATITUDE) {
                invalidCoords.add(String.format("coordinate[%d]: latitude %.6f out of range [-90, 90]", i, lat));
            }

            // Only report first few errors
            if (invalidCoords.size() >= 3) {
                break;
            }
        }

        if (!invalidCoords.isEmpty()) {
            return ValidationResult.error(
                "INVALID_COORDINATES",
                "Geometry contains coordinates outside valid WGS84 range",
                "geometry.coordinates",
                String.join("; ", invalidCoords),
                "Ensure all coordinates use longitude [-180, 180] and latitude [-90, 90] in [lon, lat] order"
            );
        }

        return ValidationResult.success();
    }

    /**
     * Validate coordinates from GeoJSON before parsing.
     * This allows checking coordinates even if JTS parsing fails.
     *
     * @param geoJson The GeoJSON string
     * @return ValidationResult
     */
    public static ValidationResult validateGeoJsonCoordinates(String geoJson) {
        if (geoJson == null || geoJson.isEmpty()) {
            return ValidationResult.success();
        }

        try {
            JSONObject json = new JSONObject(geoJson);
            return validateGeoJsonObject(json);
        } catch (Exception e) {
            // Let JTS handle parsing errors
            return ValidationResult.success();
        }
    }

    private static ValidationResult validateGeoJsonObject(JSONObject json) {
        String type = json.optString("type", "");

        // Handle Feature wrapper
        if ("Feature".equals(type)) {
            JSONObject geometry = json.optJSONObject("geometry");
            if (geometry != null) {
                return validateGeoJsonObject(geometry);
            }
            return ValidationResult.success();
        }

        // Handle FeatureCollection
        if ("FeatureCollection".equals(type)) {
            JSONArray features = json.optJSONArray("features");
            if (features != null) {
                for (int i = 0; i < features.length(); i++) {
                    ValidationResult result = validateGeoJsonObject(features.getJSONObject(i));
                    if (!result.isValid()) {
                        return result;
                    }
                }
            }
            return ValidationResult.success();
        }

        // Validate coordinates array
        JSONArray coordinates = json.optJSONArray("coordinates");
        if (coordinates != null) {
            return validateCoordinatesArray(coordinates, type);
        }

        return ValidationResult.success();
    }

    private static ValidationResult validateCoordinatesArray(JSONArray coords, String type) {
        if (coords == null || coords.length() == 0) {
            return ValidationResult.success();
        }

        // Check if this is a coordinate pair [lon, lat]
        Object first = coords.get(0);
        if (first instanceof Number) {
            // This is a coordinate pair
            if (coords.length() >= 2) {
                double lon = coords.getDouble(0);
                double lat = coords.getDouble(1);

                if (lon < MIN_LONGITUDE || lon > MAX_LONGITUDE) {
                    return ValidationResult.error(
                        "INVALID_COORDINATES",
                        String.format("Longitude %.6f is outside valid range [-180, 180]", lon),
                        "geometry.coordinates",
                        String.format("Longitude value %.6f is invalid", lon),
                        "Ensure coordinates are in [longitude, latitude] order with valid WGS84 values"
                    );
                }
                if (lat < MIN_LATITUDE || lat > MAX_LATITUDE) {
                    return ValidationResult.error(
                        "INVALID_COORDINATES",
                        String.format("Latitude %.6f is outside valid range [-90, 90]", lat),
                        "geometry.coordinates",
                        String.format("Latitude value %.6f is invalid", lat),
                        "Ensure coordinates are in [longitude, latitude] order with valid WGS84 values"
                    );
                }
            }
            return ValidationResult.success();
        }

        // Recurse into nested arrays
        for (int i = 0; i < coords.length(); i++) {
            Object item = coords.get(i);
            if (item instanceof JSONArray) {
                ValidationResult result = validateCoordinatesArray((JSONArray) item, type);
                if (!result.isValid()) {
                    return result;
                }
            }
        }

        return ValidationResult.success();
    }

    /**
     * Count vertices in GeoJSON before parsing.
     *
     * @param geoJson The GeoJSON string
     * @return Estimated vertex count, or -1 if cannot determine
     */
    public static int estimateVertexCount(String geoJson) {
        if (geoJson == null || geoJson.isEmpty()) {
            return 0;
        }

        try {
            JSONObject json = new JSONObject(geoJson);
            return countVerticesInGeoJson(json);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int countVerticesInGeoJson(JSONObject json) {
        String type = json.optString("type", "");

        if ("Feature".equals(type)) {
            JSONObject geometry = json.optJSONObject("geometry");
            if (geometry != null) {
                return countVerticesInGeoJson(geometry);
            }
            return 0;
        }

        if ("FeatureCollection".equals(type)) {
            JSONArray features = json.optJSONArray("features");
            if (features != null) {
                int total = 0;
                for (int i = 0; i < features.length(); i++) {
                    total += countVerticesInGeoJson(features.getJSONObject(i));
                }
                return total;
            }
            return 0;
        }

        JSONArray coordinates = json.optJSONArray("coordinates");
        if (coordinates != null) {
            return countCoordinates(coordinates);
        }

        return 0;
    }

    private static int countCoordinates(JSONArray coords) {
        if (coords == null || coords.length() == 0) {
            return 0;
        }

        Object first = coords.get(0);
        if (first instanceof Number) {
            // This is a single coordinate pair
            return 1;
        }

        int count = 0;
        for (int i = 0; i < coords.length(); i++) {
            Object item = coords.get(i);
            if (item instanceof JSONArray) {
                count += countCoordinates((JSONArray) item);
            }
        }
        return count;
    }

    // ==========================================================================
    // FILTER CONDITION VALIDATION (SQL Injection Prevention)
    // ==========================================================================

    // Pattern allowing only safe characters for SQL filter conditions
    private static final Pattern SAFE_FILTER_PATTERN = Pattern.compile(
        "^[\\w\\s=<>!?.,()'\"-]+$", Pattern.CASE_INSENSITIVE);

    // SQL keywords that should never appear in filter conditions
    private static final String[] BLOCKED_SQL_PATTERNS = {
        "DROP", "DELETE", "INSERT", "UPDATE", "TRUNCATE", "ALTER",
        "CREATE", "EXEC", "EXECUTE", "UNION", "INTO", "--", "/*", "*/"
    };

    private static final int MAX_FILTER_LENGTH = 500;

    /**
     * Validate a filter condition string to prevent SQL injection.
     *
     * @param filterCondition The filter condition to validate
     * @return ValidationResult
     */
    public static ValidationResult validateFilterCondition(String filterCondition) {
        if (filterCondition == null || filterCondition.trim().isEmpty()) {
            return ValidationResult.success();
        }

        String trimmed = filterCondition.trim();

        // Check length
        if (trimmed.length() > MAX_FILTER_LENGTH) {
            return ValidationResult.error(
                "FILTER_TOO_LONG",
                String.format("Filter condition exceeds %d characters", MAX_FILTER_LENGTH),
                "filterCondition",
                "Length: " + trimmed.length(),
                "Simplify the filter condition"
            );
        }

        // Check for invalid characters
        if (!SAFE_FILTER_PATTERN.matcher(trimmed).matches()) {
            return ValidationResult.error(
                "INVALID_FILTER",
                "Filter condition contains invalid characters",
                "filterCondition",
                "Only alphanumeric characters and basic SQL operators are allowed",
                "Remove special characters from the filter"
            );
        }

        // Check for blocked SQL keywords
        String upper = trimmed.toUpperCase();
        for (String blocked : BLOCKED_SQL_PATTERNS) {
            if (upper.contains(blocked)) {
                return ValidationResult.error(
                    "INVALID_FILTER",
                    "Filter contains disallowed SQL keyword: " + blocked,
                    "filterCondition",
                    "Dangerous SQL pattern detected",
                    "Remove SQL DDL/DML keywords from the filter"
                );
            }
        }

        return ValidationResult.success();
    }
}
