package global.govstack.gisserver.model;

import org.json.JSONObject;

/**
 * Represents a validation error or warning with structured details.
 *
 * Per spec section 3.5 and 4.2, validation errors include:
 * - code: Error code (e.g., SELF_INTERSECTION, TOO_FEW_VERTICES)
 * - message: Human-readable message
 * - severity: ERROR or WARNING
 * - location: Optional GeoJSON Point indicating where the error occurred
 */
public class ValidationError {

    /** Error codes as defined in spec section 3.7 */
    public static final String INVALID_GEOJSON = "INVALID_GEOJSON";
    public static final String INVALID_GEOMETRY_TYPE = "INVALID_GEOMETRY_TYPE";
    public static final String EMPTY_GEOMETRY = "EMPTY_GEOMETRY";
    public static final String TOO_FEW_VERTICES = "TOO_FEW_VERTICES";
    public static final String TOO_MANY_VERTICES = "TOO_MANY_VERTICES";
    public static final String SELF_INTERSECTION = "SELF_INTERSECTION";
    public static final String INVALID_RING = "INVALID_RING";
    public static final String AREA_ZERO = "AREA_ZERO";
    public static final String AREA_NEGATIVE = "AREA_NEGATIVE";
    public static final String AREA_EXCEEDS_MAXIMUM = "AREA_EXCEEDS_MAXIMUM";
    public static final String AREA_BELOW_MINIMUM = "AREA_BELOW_MINIMUM";
    public static final String VERTICES_NEAR_LIMIT = "VERTICES_NEAR_LIMIT";
    public static final String DUPLICATE_VERTICES = "DUPLICATE_VERTICES";
    public static final String SPIKE_DETECTED = "SPIKE_DETECTED";
    public static final String HOLES_NOT_ALLOWED = "HOLES_NOT_ALLOWED";

    /** Severity levels */
    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARNING = "WARNING";

    private String code;
    private String message;
    private String severity;
    private JSONObject location; // GeoJSON Point, optional

    public ValidationError() {
    }

    public ValidationError(String code, String message, String severity) {
        this.code = code;
        this.message = message;
        this.severity = severity;
    }

    public ValidationError(String code, String message, String severity, double longitude, double latitude) {
        this.code = code;
        this.message = message;
        this.severity = severity;
        setLocation(longitude, latitude);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public JSONObject getLocation() {
        return location;
    }

    public void setLocation(JSONObject location) {
        this.location = location;
    }

    /**
     * Set location as a GeoJSON Point.
     */
    public void setLocation(double longitude, double latitude) {
        this.location = new JSONObject();
        this.location.put("type", "Point");
        org.json.JSONArray coords = new org.json.JSONArray();
        coords.put(longitude);
        coords.put(latitude);
        this.location.put("coordinates", coords);
    }

    /**
     * Convert to JSON object for API response.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("code", code);
        json.put("message", message);
        json.put("severity", severity);
        if (location != null) {
            json.put("location", location);
        }
        return json;
    }

    /**
     * Factory method for creating errors.
     */
    public static ValidationError error(String code, String message) {
        return new ValidationError(code, message, SEVERITY_ERROR);
    }

    /**
     * Factory method for creating errors with location.
     */
    public static ValidationError errorAt(String code, String message, double longitude, double latitude) {
        return new ValidationError(code, message, SEVERITY_ERROR, longitude, latitude);
    }

    /**
     * Factory method for creating warnings.
     */
    public static ValidationError warning(String code, String message) {
        return new ValidationError(code, message, SEVERITY_WARNING);
    }

    /**
     * Factory method for creating warnings with location.
     */
    public static ValidationError warningAt(String code, String message, double longitude, double latitude) {
        return new ValidationError(code, message, SEVERITY_WARNING, longitude, latitude);
    }

    public boolean isError() {
        return SEVERITY_ERROR.equals(severity);
    }

    public boolean isWarning() {
        return SEVERITY_WARNING.equals(severity);
    }
}
