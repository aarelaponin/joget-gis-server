package global.govstack.gisserver.lib;

import global.govstack.gisserver.engine.GeometryEngine;
import global.govstack.gisserver.engine.GeometryEngine.GeometryParseException;
import global.govstack.gisserver.model.CalculateResult;
import global.govstack.gisserver.model.OverlapResult;
import global.govstack.gisserver.model.ValidationError;
import global.govstack.gisserver.model.ValidationResult;
import global.govstack.gisserver.service.GeocodingService;
import global.govstack.gisserver.service.GeocodingService.GeocodingResult;
import global.govstack.gisserver.service.GeocodingService.ReverseGeocodingResult;
import global.govstack.gisserver.service.NearbyParcelsService;
import global.govstack.gisserver.service.OverlapService;
import global.govstack.gisserver.model.NearbyParcel;
import global.govstack.gisserver.model.NearbyParcelsResult;
import global.govstack.gisserver.util.InputValidator;
import global.govstack.gisserver.util.RateLimiter;
import org.joget.api.annotations.Operation;
import org.joget.api.annotations.Param;
import org.joget.api.annotations.Response;
import org.joget.api.annotations.Responses;
import org.joget.api.model.ApiPluginAbstract;
import org.joget.api.model.ApiResponse;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.property.model.PropertyEditable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GIS API Provider - REST API for geometry operations.
 *
 * Endpoints:
 * - POST /gis/calculate     - Calculate area, perimeter, centroid
 * - POST /gis/validate      - Validate geometry against rules
 * - POST /gis/simplify      - Simplify geometry (reduce vertices)
 * - POST /gis/checkOverlap  - Check for overlapping parcels
 * - GET  /gis/nearbyParcels - Get nearby parcels for display
 * - GET  /gis/health        - Health check endpoint
 *
 * Base URL: /jw/api/gis/gis/...
 */
public class GisApiProvider extends ApiPluginAbstract implements PropertyEditable {

    private static final String CLASS_NAME = "global.govstack.gisserver.lib.GisApiProvider";
    private static final int MAX_BATCH_SIZE = 100;

    // Services (lazy initialized with volatile for thread safety)
    private volatile GeometryEngine geometryEngine;
    private volatile OverlapService overlapService;
    private volatile GeocodingService geocodingService;
    private volatile NearbyParcelsService nearbyParcelsService;

    @Override
    public String getName() {
        return "gis";
    }

    @Override
    public String getVersion() {
        return "8.1-SNAPSHOT";
    }

    @Override
    public String getDescription() {
        return "GIS Geometry API - Calculate, validate, simplify polygons and check overlaps";
    }

    @Override
    public String getTag() {
        return "gis";
    }

    @Override
    public String getIcon() {
        return "<i class=\"fas fa-map-marked-alt\"></i>";
    }

    @Override
    public String getLabel() {
        return "GIS Geometry API";
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(
            getClass().getName(),
            "/properties/GisApiProvider.json",
            null,
            true,
            null
        );
    }

    /**
     * Get geometry engine (lazy initialization with double-checked locking).
     */
    private GeometryEngine getGeometryEngine() {
        GeometryEngine local = geometryEngine;
        if (local == null) {
            synchronized (this) {
                local = geometryEngine;
                if (local == null) {
                    geometryEngine = local = new GeometryEngine();
                }
            }
        }
        return local;
    }

    /**
     * Get overlap service (lazy initialization with double-checked locking).
     */
    private OverlapService getOverlapService() {
        OverlapService local = overlapService;
        if (local == null) {
            synchronized (this) {
                local = overlapService;
                if (local == null) {
                    overlapService = local = new OverlapService();
                }
            }
        }
        return local;
    }

    /**
     * Get geocoding service (lazy initialization with double-checked locking).
     */
    private GeocodingService getGeocodingService() {
        GeocodingService local = geocodingService;
        if (local == null) {
            synchronized (this) {
                local = geocodingService;
                if (local == null) {
                    geocodingService = local = new GeocodingService();
                }
            }
        }
        return local;
    }

    /**
     * Get nearby parcels service (lazy initialization with double-checked locking).
     */
    private NearbyParcelsService getNearbyParcelsService() {
        NearbyParcelsService local = nearbyParcelsService;
        if (local == null) {
            synchronized (this) {
                local = nearbyParcelsService;
                if (local == null) {
                    nearbyParcelsService = local = new NearbyParcelsService();
                }
            }
        }
        return local;
    }

    // ==========================================================================
    // CALCULATE ENDPOINT
    // ==========================================================================

    /**
     * Calculate geometry metrics (area, perimeter, centroid, bounding box).
     *
     * Endpoint: POST /jw/api/gis/gis/calculate
     */
    @Operation(
        path = "/gis/calculate",
        type = Operation.MethodType.POST,
        summary = "Calculate geometry metrics",
        description = "Calculates area (hectares), perimeter (meters), centroid, " +
                      "and bounding box for a given GeoJSON polygon."
    )
    @Responses({
        @Response(responseCode = 200, description = "Calculation successful"),
        @Response(responseCode = 400, description = "Invalid GeoJSON"),
        @Response(responseCode = 422, description = "Invalid geometry type"),
        @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse calculate(
        @Param(value = "body", required = true) String requestBody
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS Calculate Request [" + requestId + "] ===");

        // Check rate limit
        ApiResponse rateLimitError = checkRateLimit("calculate", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate request size
            InputValidator.ValidationResult sizeResult = InputValidator.validateRequestSize(requestBody);
            if (!sizeResult.isValid()) {
                return errorResponse(413, sizeResult, requestId, startTime);
            }

            JSONObject request = new JSONObject(requestBody);

            // Get geometry
            String geoJson = extractGeometry(request);
            if (geoJson == null || geoJson.isEmpty()) {
                return errorResponse(400, "MISSING_GEOMETRY",
                    "Geometry is required", requestId, startTime);
            }

            // Parse options
            JSONObject options = request.optJSONObject("options");
            boolean includeAreaSqM = options != null && options.optBoolean("includeAreaInSquareMeters", true);
            boolean includeBoundingBox = options == null || options.optBoolean("includeBoundingBox", true);

            JSONObject precision = options != null ? options.optJSONObject("decimalPrecision") : null;
            int areaPrecision = precision != null ? precision.optInt("area", 4) : 4;
            int perimeterPrecision = precision != null ? precision.optInt("perimeter", 2) : 2;
            int coordPrecision = precision != null ? precision.optInt("coordinates", 7) : 7;

            // Parse geometry
            Geometry geometry;
            try {
                geometry = getGeometryEngine().parseGeoJson(geoJson);
            } catch (GeometryParseException e) {
                return errorResponse(400, "INVALID_GEOJSON",
                    "Failed to parse GeoJSON geometry: " + e.getMessage(),
                    requestId, startTime);
            }

            // Validate input constraints (vertex count, coordinate range)
            ApiResponse validationError = validateInput(requestBody, geometry, requestId, startTime);
            if (validationError != null) {
                return validationError;
            }

            // Validate geometry type
            if (!isPolygonType(geometry)) {
                return errorResponse(422, "INVALID_GEOMETRY_TYPE",
                    "Expected Polygon or MultiPolygon, received: " + geometry.getGeometryType(),
                    requestId, startTime);
            }
            
            // Calculate metrics
            CalculateResult result = getGeometryEngine().calculate(geometry, includeBoundingBox, includeAreaSqM);
            
            // Build response
            JSONObject response = new JSONObject();
            response.put("success", true);
            
            JSONObject data = new JSONObject();
            data.put("areaHectares", round(result.getAreaHectares(), areaPrecision));
            if (includeAreaSqM && result.getAreaSquareMeters() != null) {
                data.put("areaSquareMeters", round(result.getAreaSquareMeters(), 2));
            }
            data.put("perimeterMeters", round(result.getPerimeterMeters(), perimeterPrecision));
            
            // Centroid as GeoJSON Point per spec section 4.1
            JSONObject centroid = new JSONObject();
            centroid.put("type", "Point");
            JSONArray coords = new JSONArray();
            coords.put(round(result.getCentroidLongitude(), coordPrecision));
            coords.put(round(result.getCentroidLatitude(), coordPrecision));
            centroid.put("coordinates", coords);
            data.put("centroid", centroid);
            
            if (includeBoundingBox && result.getMinLatitude() != null) {
                JSONObject boundingBox = new JSONObject();
                boundingBox.put("minLatitude", round(result.getMinLatitude(), coordPrecision));
                boundingBox.put("maxLatitude", round(result.getMaxLatitude(), coordPrecision));
                boundingBox.put("minLongitude", round(result.getMinLongitude(), coordPrecision));
                boundingBox.put("maxLongitude", round(result.getMaxLongitude(), coordPrecision));
                data.put("boundingBox", boundingBox);
            }
            
            data.put("vertexCount", result.getVertexCount());
            response.put("data", data);
            
            // Meta
            response.put("meta", buildMeta(requestId, startTime));
            
            LogUtil.info(CLASS_NAME, "Calculate success: area=" + result.getAreaHectares() + 
                         " ha, vertices=" + result.getVertexCount());
            
            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "calculate", requestId, startTime);
        }
    }

    // ==========================================================================
    // VALIDATE ENDPOINT
    // ==========================================================================

    /**
     * Validate geometry against configurable rules.
     *
     * Endpoint: POST /jw/api/gis/gis/validate
     *
     * Per spec section 4.2:
     * - rules.minAreaHectares: Minimum area (warning if below)
     * - rules.maxAreaHectares: Maximum area (error if exceeded)
     * - rules.minVertices: Minimum vertices (error)
     * - rules.maxVertices: Maximum vertices (error)
     * - rules.allowSelfIntersection: Allow self-intersecting polygons
     * - rules.allowHoles: Allow polygons with holes
     * - options.includeMetrics: Include calculated metrics (default true)
     * - options.detectSpikes: Detect very acute angles (default true)
     * - options.spikeAngleThreshold: Angle threshold in degrees (default 10)
     */
    @Operation(
        path = "/gis/validate",
        type = Operation.MethodType.POST,
        summary = "Validate geometry",
        description = "Validates a GeoJSON polygon against configurable rules " +
                      "(min/max area, min/max vertices, self-intersection, holes, spikes)."
    )
    @Responses({
        @Response(responseCode = 200, description = "Validation completed"),
        @Response(responseCode = 400, description = "Invalid GeoJSON"),
        @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse validate(
        @Param(value = "body", required = true) String requestBody
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS Validate Request [" + requestId + "] ===");

        // Check rate limit
        ApiResponse rateLimitError = checkRateLimit("validate", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate request size
            InputValidator.ValidationResult sizeResult = InputValidator.validateRequestSize(requestBody);
            if (!sizeResult.isValid()) {
                return errorResponse(413, sizeResult, requestId, startTime);
            }

            JSONObject request = new JSONObject(requestBody);

            // Get geometry
            String geoJson = extractGeometry(request);
            if (geoJson == null || geoJson.isEmpty()) {
                return errorResponse(400, "MISSING_GEOMETRY",
                    "Geometry is required", requestId, startTime);
            }

            // Get validation rules
            JSONObject rules = request.optJSONObject("rules");
            double minAreaHa = rules != null ? rules.optDouble("minAreaHectares", 0.0) : 0.0;
            double maxAreaHa = rules != null ? rules.optDouble("maxAreaHectares", 0.0) : 0.0; // 0 = no limit
            int minVertices = rules != null ? rules.optInt("minVertices", 3) : 3;
            int maxVertices = rules != null ? rules.optInt("maxVertices", 1000) : 1000;
            boolean allowSelfIntersection = rules != null && rules.optBoolean("allowSelfIntersection", false);
            boolean allowHoles = rules == null || rules.optBoolean("allowHoles", true);

            // Get validation options
            JSONObject options = request.optJSONObject("options");
            boolean includeMetrics = options == null || options.optBoolean("includeMetrics", true);
            boolean detectSpikes = options == null || options.optBoolean("detectSpikes", true);
            double spikeAngleThreshold = options != null ? options.optDouble("spikeAngleThreshold", 10.0) : 10.0;

            // Parse geometry
            Geometry geometry;
            try {
                geometry = getGeometryEngine().parseGeoJson(geoJson);
            } catch (GeometryParseException e) {
                return errorResponse(400, "INVALID_GEOJSON",
                    "Failed to parse GeoJSON geometry: " + e.getMessage(),
                    requestId, startTime);
            }

            // Validate input constraints (vertex count, coordinate range)
            ApiResponse validationError = validateInput(requestBody, geometry, requestId, startTime);
            if (validationError != null) {
                return validationError;
            }

            // Validate with all options
            ValidationResult result = getGeometryEngine().validate(
                geometry, minAreaHa, maxAreaHa, minVertices, maxVertices,
                allowSelfIntersection, allowHoles, detectSpikes, spikeAngleThreshold);

            // Build response per spec section 4.2
            JSONObject response = new JSONObject();
            response.put("success", true);

            JSONObject data = new JSONObject();
            data.put("valid", result.isValid());

            // Convert errors to JSON array of structured objects
            JSONArray errorsArray = new JSONArray();
            for (ValidationError error : result.getErrors()) {
                errorsArray.put(error.toJson());
            }
            data.put("errors", errorsArray);

            // Convert warnings to JSON array of structured objects
            JSONArray warningsArray = new JSONArray();
            for (ValidationError warning : result.getWarnings()) {
                warningsArray.put(warning.toJson());
            }
            data.put("warnings", warningsArray);

            // Include metrics if requested
            if (includeMetrics) {
                JSONObject metrics = new JSONObject();
                metrics.put("areaHectares", round(result.getAreaHectares(), 4));
                metrics.put("perimeterMeters", round(result.getPerimeterMeters(), 2));
                metrics.put("vertexCount", result.getVertexCount());
                data.put("metrics", metrics);
            }

            response.put("data", data);
            response.put("meta", buildMeta(requestId, startTime));

            LogUtil.info(CLASS_NAME, "Validate: valid=" + result.isValid() +
                         ", errors=" + result.getErrors().size() +
                         ", warnings=" + result.getWarnings().size());

            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "validate", requestId, startTime);
        }
    }

    // ==========================================================================
    // SIMPLIFY ENDPOINT
    // ==========================================================================

    // Conversion factor: meters to degrees at equator (1 degree ≈ 111,320 meters)
    private static final double METERS_TO_DEGREES = 1.0 / 111320.0;

    /**
     * Simplify geometry (reduce vertices).
     *
     * Endpoint: POST /jw/api/gis/gis/simplify
     *
     * Per spec section 4.3:
     * - options.toleranceMeters: Simplification tolerance in meters (converted to degrees)
     * - options.preserveTopology: Prevent creating invalid geometry (default true)
     * - options.targetVertexCount: Try to reduce to this vertex count
     * - options.maxAreaChangePercent: Maximum allowed area change (default 1.0)
     *
     * Backward compatibility:
     * - "tolerance" (old format): Direct degree value, used if toleranceMeters not provided
     */
    @Operation(
        path = "/gis/simplify",
        type = Operation.MethodType.POST,
        summary = "Simplify geometry",
        description = "Reduces the number of vertices using Douglas-Peucker algorithm " +
                      "while preserving the overall shape."
    )
    @Responses({
        @Response(responseCode = 200, description = "Simplification successful"),
        @Response(responseCode = 400, description = "Invalid GeoJSON"),
        @Response(responseCode = 422, description = "Area change exceeds maximum"),
        @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse simplify(
        @Param(value = "body", required = true) String requestBody
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS Simplify Request [" + requestId + "] ===");

        // Check rate limit
        ApiResponse rateLimitError = checkRateLimit("simplify", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate request size
            InputValidator.ValidationResult sizeResult = InputValidator.validateRequestSize(requestBody);
            if (!sizeResult.isValid()) {
                return errorResponse(413, sizeResult, requestId, startTime);
            }

            JSONObject request = new JSONObject(requestBody);

            // Get geometry
            String geoJson = extractGeometry(request);
            if (geoJson == null || geoJson.isEmpty()) {
                return errorResponse(400, "MISSING_GEOMETRY",
                    "Geometry is required", requestId, startTime);
            }

            // Get options
            JSONObject options = request.optJSONObject("options");

            // Determine tolerance:
            // 1. If options.toleranceMeters is provided, convert to degrees
            // 2. Otherwise use legacy "tolerance" field (degrees)
            // 3. Default: 1 meter ≈ 0.000009 degrees
            double tolerance;
            if (options != null && options.has("toleranceMeters")) {
                double toleranceMeters = options.optDouble("toleranceMeters", 1.0);
                tolerance = toleranceMeters * METERS_TO_DEGREES;
            } else {
                // Backward compatibility: use "tolerance" in degrees directly
                tolerance = request.optDouble("tolerance", 1.0 * METERS_TO_DEGREES);
            }

            boolean preserveTopology = options == null || options.optBoolean("preserveTopology", true);
            Integer targetVertexCount = options != null && options.has("targetVertexCount")
                ? options.optInt("targetVertexCount") : null;
            double maxAreaChangePercent = options != null
                ? options.optDouble("maxAreaChangePercent", 1.0) : 1.0;

            // Parse geometry
            Geometry geometry;
            try {
                geometry = getGeometryEngine().parseGeoJson(geoJson);
            } catch (GeometryParseException e) {
                return errorResponse(400, "INVALID_GEOJSON",
                    "Failed to parse GeoJSON geometry: " + e.getMessage(),
                    requestId, startTime);
            }

            // Validate input constraints (vertex count, coordinate range)
            ApiResponse validationError = validateInput(requestBody, geometry, requestId, startTime);
            if (validationError != null) {
                return validationError;
            }

            int originalVertices = getGeometryEngine().getVertexCount(geometry);
            double originalAreaHa = getGeometryEngine().calculateAreaHectares(geometry);

            // Simplify
            Geometry simplified;
            if (targetVertexCount != null && targetVertexCount > 0) {
                // Iterative simplification to reach target vertex count
                simplified = simplifyToTargetVertexCount(geometry, targetVertexCount, tolerance, preserveTopology);
            } else {
                simplified = getGeometryEngine().simplify(geometry, tolerance);
            }

            int simplifiedVertices = getGeometryEngine().getVertexCount(simplified);
            double simplifiedAreaHa = getGeometryEngine().calculateAreaHectares(simplified);

            // Calculate area change
            double areaChangePercent = originalAreaHa > 0
                ? ((simplifiedAreaHa - originalAreaHa) / originalAreaHa) * 100.0
                : 0.0;

            // Check if area change exceeds maximum
            if (Math.abs(areaChangePercent) > maxAreaChangePercent) {
                JSONObject response = new JSONObject();
                response.put("success", false);
                JSONObject error = new JSONObject();
                error.put("code", "AREA_CHANGE_EXCEEDS_MAXIMUM");
                error.put("message", String.format(
                    "Simplification would change area by %.2f%%, exceeds maximum allowed %.2f%%",
                    areaChangePercent, maxAreaChangePercent));
                JSONObject details = new JSONObject();
                details.put("originalAreaHectares", round(originalAreaHa, 4));
                details.put("simplifiedAreaHectares", round(simplifiedAreaHa, 4));
                details.put("changePercent", round(areaChangePercent, 2));
                details.put("maxAllowedPercent", maxAreaChangePercent);
                error.put("details", details);
                response.put("error", error);
                response.put("meta", buildMeta(requestId, startTime));
                return new ApiResponse(422, response.toString());
            }

            // Build success response per spec section 4.3
            JSONObject response = new JSONObject();
            response.put("success", true);

            JSONObject data = new JSONObject();

            // Parse simplified GeoJSON and include as object (not string)
            String simplifiedGeoJson = getGeometryEngine().toGeoJson(simplified);
            data.put("simplifiedGeometry", new JSONObject(simplifiedGeoJson));

            data.put("originalVertexCount", originalVertices);
            data.put("simplifiedVertexCount", simplifiedVertices);
            data.put("verticesRemoved", originalVertices - simplifiedVertices);

            // Area change object per spec
            JSONObject areaChange = new JSONObject();
            areaChange.put("originalHectares", round(originalAreaHa, 4));
            areaChange.put("simplifiedHectares", round(simplifiedAreaHa, 4));
            areaChange.put("changePercent", round(areaChangePercent, 2));
            data.put("areaChange", areaChange);

            response.put("data", data);
            response.put("meta", buildMeta(requestId, startTime));

            LogUtil.info(CLASS_NAME, "Simplify: " + originalVertices + " -> " + simplifiedVertices +
                         " vertices, area change: " + round(areaChangePercent, 2) + "%");

            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "simplify", requestId, startTime);
        }
    }

    /**
     * Iteratively simplify geometry to reach a target vertex count.
     * Uses binary search to find the right tolerance.
     */
    private Geometry simplifyToTargetVertexCount(Geometry geometry, int targetCount,
                                                  double initialTolerance, boolean preserveTopology) {
        double minTolerance = 0.0;
        double maxTolerance = initialTolerance * 100; // Start with a large upper bound
        double tolerance = initialTolerance;

        Geometry best = geometry;
        int maxIterations = 20;

        for (int i = 0; i < maxIterations; i++) {
            Geometry simplified = getGeometryEngine().simplify(geometry, tolerance);
            int vertexCount = getGeometryEngine().getVertexCount(simplified);

            if (vertexCount == targetCount) {
                return simplified;
            }

            if (vertexCount > targetCount) {
                // Need more simplification (higher tolerance)
                minTolerance = tolerance;
                tolerance = (tolerance + maxTolerance) / 2;
            } else {
                // Too much simplification (lower tolerance)
                maxTolerance = tolerance;
                tolerance = (minTolerance + tolerance) / 2;
                best = simplified; // Keep best result that doesn't exceed target
            }

            // Check convergence
            if (Math.abs(maxTolerance - minTolerance) < initialTolerance * 0.01) {
                break;
            }
        }

        // Return the best result (closest to target without going under)
        return best;
    }

    // ==========================================================================
    // CHECK OVERLAP ENDPOINT
    // ==========================================================================

    /**
     * Check for overlapping geometries (generic spatial query).
     *
     * Endpoint: POST /jw/api/gis/gis/checkOverlap
     *
     * Per spec section 4.4, this is a generic spatial query that can check
     * against any Joget form containing geometry data. The caller specifies
     * which form and field to check against.
     *
     * New request format (spec 4.4):
     * - target.formId: Form ID containing geometries
     * - target.geometryFieldId: Field ID containing GeoJSON
     * - target.excludeRecordId: Record to exclude (for editing)
     * - target.filterCondition: SQL WHERE condition
     * - target.filterParams: Array of parameters for condition
     * - options.returnFields: Array of field IDs to include in response
     *
     * Backward compatibility:
     * - Falls back to flat options.formId, options.geometryField, etc.
     */
    @Operation(
        path = "/gis/checkOverlap",
        type = Operation.MethodType.POST,
        summary = "Check for overlapping geometries",
        description = "Checks if the given polygon overlaps with existing geometries in any Joget form."
    )
    @Responses({
        @Response(responseCode = 200, description = "Overlap check completed"),
        @Response(responseCode = 400, description = "Invalid request"),
        @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse checkOverlap(
        @Param(value = "body", required = true) String requestBody
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS CheckOverlap Request [" + requestId + "] ===");

        // Check rate limit
        ApiResponse rateLimitError = checkRateLimit("checkOverlap", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate request size
            InputValidator.ValidationResult sizeResult = InputValidator.validateRequestSize(requestBody);
            if (!sizeResult.isValid()) {
                return errorResponse(413, sizeResult, requestId, startTime);
            }

            JSONObject request = new JSONObject(requestBody);

            // Get geometry
            String geoJson = extractGeometry(request);
            if (geoJson == null || geoJson.isEmpty()) {
                return errorResponse(400, "MISSING_GEOMETRY",
                    "Geometry is required", requestId, startTime);
            }

            // Validate coordinates before processing
            InputValidator.ValidationResult coordResult = InputValidator.validateGeoJsonCoordinates(geoJson);
            if (!coordResult.isValid()) {
                return errorResponse(422, coordResult, requestId, startTime);
            }

            // Get target object (new spec format) or fall back to options (backward compat)
            JSONObject target = request.optJSONObject("target");
            JSONObject options = request.optJSONObject("options");

            // Parse target parameters with backward compatibility
            String formId;
            String geometryFieldId;
            String excludeRecordId;
            String filterCondition;
            Object[] filterParams = null;

            if (target != null) {
                // New spec format: target object
                formId = target.optString("formId", "parcel");
                geometryFieldId = target.optString("geometryFieldId", "c_geometry");
                excludeRecordId = target.optString("excludeRecordId", null);
                filterCondition = target.optString("filterCondition", null);

                // Parse filterParams array
                JSONArray paramsArray = target.optJSONArray("filterParams");
                if (paramsArray != null && paramsArray.length() > 0) {
                    filterParams = new Object[paramsArray.length()];
                    for (int i = 0; i < paramsArray.length(); i++) {
                        filterParams[i] = paramsArray.get(i);
                    }
                }
            } else {
                // Backward compatibility: flat options format
                formId = options != null ? options.optString("formId", "parcel") : "parcel";
                geometryFieldId = options != null ? options.optString("geometryField", "c_geometry") : "c_geometry";
                excludeRecordId = options != null ? options.optString("excludeParcelId", null) : null;
                filterCondition = options != null ? options.optString("filterCondition", null) : null;
            }

            // Parse options
            double minOverlapPercent = options != null ? options.optDouble("minOverlapPercent", 1.0) : 1.0;
            int maxResults = options != null ? options.optInt("maxResults", 10) : 10;
            boolean includeOverlapGeometry = options != null && options.optBoolean("includeOverlapGeometry", false);

            // Parse returnFields array (new spec format)
            List<String> returnFields = new ArrayList<>();
            if (options != null) {
                JSONArray returnFieldsArray = options.optJSONArray("returnFields");
                if (returnFieldsArray != null) {
                    for (int i = 0; i < returnFieldsArray.length(); i++) {
                        returnFields.add(returnFieldsArray.getString(i));
                    }
                } else {
                    // Backward compatibility: add codeField and nameField if specified
                    String codeField = options.optString("codeField", null);
                    String nameField = options.optString("nameField", null);
                    if (codeField != null && !codeField.isEmpty()) {
                        returnFields.add(codeField);
                    }
                    if (nameField != null && !nameField.isEmpty()) {
                        returnFields.add(nameField);
                    }
                }
            }

            // Check overlaps
            OverlapResult result = getOverlapService().checkOverlaps(
                geoJson, excludeRecordId, formId, geometryFieldId,
                filterCondition, filterParams, returnFields,
                minOverlapPercent, maxResults, includeOverlapGeometry
            );

            // Build response per spec section 4.4
            JSONObject response = new JSONObject();
            response.put("success", true);

            JSONObject data = new JSONObject();
            data.put("hasOverlaps", result.isHasOverlaps());
            data.put("totalOverlapAreaHectares", result.getTotalOverlapAreaHectares());
            data.put("checkedRecordCount", result.getCheckedRecordCount());

            JSONArray overlapsArray = new JSONArray();
            for (OverlapResult.OverlapInfo info : result.getOverlaps()) {
                JSONObject overlap = new JSONObject();
                overlap.put("recordId", info.getRecordId());
                overlap.put("overlapAreaHectares", info.getOverlapAreaHectares());
                overlap.put("overlapPercentOfInput", info.getOverlapPercentOfInput());
                overlap.put("overlapPercentOfExisting", info.getOverlapPercentOfExisting());

                // Add recordData object with requested fields
                Map<String, String> recordData = info.getRecordData();
                if (recordData != null && !recordData.isEmpty()) {
                    JSONObject recordDataJson = new JSONObject();
                    for (Map.Entry<String, String> entry : recordData.entrySet()) {
                        recordDataJson.put(entry.getKey(), entry.getValue());
                    }
                    overlap.put("recordData", recordDataJson);
                }

                if (info.getOverlapGeometry() != null) {
                    overlap.put("overlapGeometry", new JSONObject(info.getOverlapGeometry()));
                }
                overlapsArray.put(overlap);
            }
            data.put("overlaps", overlapsArray);

            response.put("data", data);
            response.put("meta", buildMeta(requestId, startTime));

            LogUtil.info(CLASS_NAME, "CheckOverlap: hasOverlaps=" + result.isHasOverlaps() +
                         ", count=" + result.getOverlaps().size());

            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "checkOverlap", requestId, startTime);
        }
    }

    // ==========================================================================
    // GEOCODE ENDPOINT
    // ==========================================================================

    /**
     * Forward geocode: search for location by name.
     *
     * Endpoint: GET /jw/api/gis/gis/geocode
     *
     * Per spec section 4.5:
     * - query: Search term (min 3 characters)
     * - limit: Maximum results to return (default 5)
     * - countryCode: ISO country code to bias results
     * - boundingBox: minLon,minLat,maxLon,maxLat to limit search area
     */
    @Operation(
        path = "/gis/geocode",
        type = Operation.MethodType.GET,
        summary = "Geocode location",
        description = "Search for a location by name and return coordinates (uses Nominatim/OpenStreetMap)."
    )
    @Responses({
        @Response(responseCode = 200, description = "Geocoding successful"),
        @Response(responseCode = 400, description = "Invalid query"),
        @Response(responseCode = 503, description = "Geocoding service unavailable")
    })
    public ApiResponse geocode(
        @Param(value = "query", required = false) String query,
        @Param(value = "limit", required = false) Integer limit,
        @Param(value = "countryCode", required = false) String countryCode,
        @Param(value = "boundingBox", required = false) String boundingBox
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS Geocode Request [" + requestId + "] query=" + query + " ===");

        // Check rate limit
        ApiResponse rateLimitError = checkRateLimit("geocode", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate query
            if (query == null || query.trim().length() < 3) {
                return errorResponse(400, "INVALID_QUERY",
                    "Query must be at least 3 characters", requestId, startTime);
            }

            // Call geocoding service
            GeocodingResult result = getGeocodingService().geocode(query, limit, countryCode, boundingBox);

            // Check for errors
            if (result.hasError()) {
                String errorCode = result.getErrorCode();
                int statusCode = "RATE_LIMITED".equals(errorCode) ? 503 : 400;
                return errorResponse(statusCode, errorCode, result.getErrorMessage(), requestId, startTime);
            }

            // Build response per spec section 4.5
            JSONObject response = new JSONObject();
            response.put("success", true);

            JSONObject data = new JSONObject();
            data.put("query", result.getQuery());
            data.put("totalResults", result.getTotalResults());

            JSONArray resultsArray = new JSONArray();
            for (GeocodingResult.GeocodingResultItem item : result.getResults()) {
                JSONObject resultItem = new JSONObject();
                resultItem.put("displayName", item.getDisplayName());

                // Location as GeoJSON Point
                JSONObject location = new JSONObject();
                location.put("type", "Point");
                JSONArray coords = new JSONArray();
                coords.put(round(item.getLongitude(), 7));
                coords.put(round(item.getLatitude(), 7));
                location.put("coordinates", coords);
                resultItem.put("location", location);

                resultItem.put("type", item.getType());

                // Bounding box if available
                if (item.hasBoundingBox()) {
                    JSONObject bbox = new JSONObject();
                    bbox.put("minLongitude", round(item.getMinLongitude(), 7));
                    bbox.put("maxLongitude", round(item.getMaxLongitude(), 7));
                    bbox.put("minLatitude", round(item.getMinLatitude(), 7));
                    bbox.put("maxLatitude", round(item.getMaxLatitude(), 7));
                    resultItem.put("boundingBox", bbox);
                }

                resultsArray.put(resultItem);
            }
            data.put("results", resultsArray);

            response.put("data", data);
            response.put("meta", buildMeta(requestId, startTime));

            LogUtil.info(CLASS_NAME, "Geocode success: query=" + query +
                         ", results=" + result.getTotalResults());

            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "geocode", requestId, startTime);
        }
    }

    // ==========================================================================
    // REVERSE GEOCODE ENDPOINT
    // ==========================================================================

    /**
     * Reverse geocode: get place name from coordinates.
     *
     * Endpoint: GET /jw/api/gis/gis/reverseGeocode
     *
     * Per spec section 4.6:
     * - lon: Longitude (required)
     * - lat: Latitude (required)
     * - zoom: Detail level (higher = more specific, default 14)
     */
    @Operation(
        path = "/gis/reverseGeocode",
        type = Operation.MethodType.GET,
        summary = "Reverse geocode",
        description = "Get place name from coordinates (uses Nominatim/OpenStreetMap)."
    )
    @Responses({
        @Response(responseCode = 200, description = "Reverse geocoding successful"),
        @Response(responseCode = 400, description = "Invalid coordinates"),
        @Response(responseCode = 503, description = "Geocoding service unavailable")
    })
    public ApiResponse reverseGeocode(
        @Param(value = "lon", required = false) String lonParam,
        @Param(value = "lat", required = false) String latParam,
        @Param(value = "zoom", required = false) Integer zoom
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS ReverseGeocode Request [" + requestId + "] lon=" +
                     lonParam + " lat=" + latParam + " ===");

        // Check rate limit (uses same limit as geocode)
        ApiResponse rateLimitError = checkRateLimit("reverseGeocode", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate and parse coordinates
            if (lonParam == null || lonParam.trim().isEmpty()) {
                return errorResponse(400, "MISSING_LONGITUDE",
                    "Longitude (lon) is required", requestId, startTime);
            }
            if (latParam == null || latParam.trim().isEmpty()) {
                return errorResponse(400, "MISSING_LATITUDE",
                    "Latitude (lat) is required", requestId, startTime);
            }

            double longitude;
            double latitude;
            try {
                longitude = Double.parseDouble(lonParam.trim());
            } catch (NumberFormatException e) {
                return errorResponse(400, "INVALID_LONGITUDE",
                    "Longitude must be a valid number", requestId, startTime);
            }
            try {
                latitude = Double.parseDouble(latParam.trim());
            } catch (NumberFormatException e) {
                return errorResponse(400, "INVALID_LATITUDE",
                    "Latitude must be a valid number", requestId, startTime);
            }

            if (longitude < -180 || longitude > 180) {
                return errorResponse(400, "INVALID_LONGITUDE",
                    "Longitude must be between -180 and 180", requestId, startTime);
            }
            if (latitude < -90 || latitude > 90) {
                return errorResponse(400, "INVALID_LATITUDE",
                    "Latitude must be between -90 and 90", requestId, startTime);
            }

            // Call geocoding service
            ReverseGeocodingResult result = getGeocodingService().reverseGeocode(longitude, latitude, zoom);

            // Check for errors
            if (result.hasError()) {
                String errorCode = result.getErrorCode();
                int statusCode = "RATE_LIMITED".equals(errorCode) ? 503 :
                                 "NOT_FOUND".equals(errorCode) ? 404 : 400;
                return errorResponse(statusCode, errorCode, result.getErrorMessage(), requestId, startTime);
            }

            // Build response per spec section 4.6
            JSONObject response = new JSONObject();
            response.put("success", true);

            JSONObject data = new JSONObject();
            data.put("displayName", result.getDisplayName());

            // Address object
            ReverseGeocodingResult.Address addr = result.getAddress();
            if (addr != null) {
                JSONObject address = new JSONObject();
                if (addr.getVillage() != null) {
                    address.put("village", addr.getVillage());
                }
                if (addr.getDistrict() != null) {
                    address.put("district", addr.getDistrict());
                }
                if (addr.getCountry() != null) {
                    address.put("country", addr.getCountry());
                }
                if (addr.getCountryCode() != null) {
                    address.put("countryCode", addr.getCountryCode());
                }
                data.put("address", address);
            }

            // Location as GeoJSON Point
            JSONObject location = new JSONObject();
            location.put("type", "Point");
            JSONArray coords = new JSONArray();
            coords.put(round(result.getLongitude(), 7));
            coords.put(round(result.getLatitude(), 7));
            location.put("coordinates", coords);
            data.put("location", location);

            response.put("data", data);
            response.put("meta", buildMeta(requestId, startTime));

            LogUtil.info(CLASS_NAME, "ReverseGeocode success: " + result.getDisplayName());

            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "reverseGeocode", requestId, startTime);
        }
    }

    // ==========================================================================
    // NEARBY PARCELS ENDPOINT
    // ==========================================================================

    /**
     * Get nearby parcels for read-only display.
     *
     * Endpoint: GET /jw/api/gis/gis/nearbyParcels
     *
     * Returns parcels within a bounding box for visual context when
     * adding/editing a new parcel. Results are READ-ONLY.
     */
    @Operation(
        path = "/gis/nearbyParcels",
        type = Operation.MethodType.GET,
        summary = "Get nearby parcels for display",
        description = "Retrieves parcels within a bounding box for read-only display context."
    )
    @Responses({
        @Response(responseCode = 200, description = "Parcels retrieved successfully"),
        @Response(responseCode = 400, description = "Invalid parameters"),
        @Response(responseCode = 429, description = "Rate limit exceeded"),
        @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse getNearbyParcels(
        @Param(value = "formId", required = true) String formId,
        @Param(value = "geometryFieldId", required = true) String geometryFieldId,
        @Param(value = "bounds", required = true) String bounds,
        @Param(value = "excludeRecordId", required = false) String excludeRecordId,
        @Param(value = "filterCondition", required = false) String filterCondition,
        @Param(value = "returnFields", required = false) String returnFields,
        @Param(value = "maxResults", required = false) Integer maxResults
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS NearbyParcels Request [" + requestId + "] ===");

        // Check rate limit
        ApiResponse rateLimitError = checkRateLimit("nearbyParcels", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate required parameters
            if (formId == null || formId.isEmpty()) {
                return errorResponse(400, "MISSING_FORM_ID",
                    "Form ID is required", requestId, startTime);
            }

            if (geometryFieldId == null || geometryFieldId.isEmpty()) {
                return errorResponse(400, "MISSING_GEOMETRY_FIELD",
                    "Geometry field ID is required", requestId, startTime);
            }

            if (bounds == null || bounds.isEmpty()) {
                return errorResponse(400, "INVALID_BOUNDS",
                    "Bounds parameter is required", requestId, startTime);
            }

            // Parse bounds: minLng,minLat,maxLng,maxLat
            String[] boundsParts = bounds.split(",");
            if (boundsParts.length != 4) {
                JSONObject details = new JSONObject();
                details.put("format", "minLng,minLat,maxLng,maxLat");
                details.put("example", "28.1,-29.6,28.3,-29.4");
                return errorResponse(400, "INVALID_BOUNDS",
                    "Bounds must have 4 comma-separated values: minLng,minLat,maxLng,maxLat",
                    details, requestId, startTime);
            }

            double minLng, minLat, maxLng, maxLat;
            try {
                minLng = Double.parseDouble(boundsParts[0].trim());
                minLat = Double.parseDouble(boundsParts[1].trim());
                maxLng = Double.parseDouble(boundsParts[2].trim());
                maxLat = Double.parseDouble(boundsParts[3].trim());
            } catch (NumberFormatException e) {
                JSONObject details = new JSONObject();
                details.put("format", "minLng,minLat,maxLng,maxLat");
                details.put("example", "28.1,-29.6,28.3,-29.4");
                return errorResponse(400, "INVALID_BOUNDS",
                    "Bounds values must be valid numbers",
                    details, requestId, startTime);
            }

            // Validate coordinate ranges (WGS84)
            if (minLng < -180 || minLng > 180 || maxLng < -180 || maxLng > 180) {
                return errorResponse(400, "INVALID_BOUNDS",
                    "Longitude must be between -180 and 180", requestId, startTime);
            }
            if (minLat < -90 || minLat > 90 || maxLat < -90 || maxLat > 90) {
                return errorResponse(400, "INVALID_BOUNDS",
                    "Latitude must be between -90 and 90", requestId, startTime);
            }

            // Parse returnFields
            List<String> returnFieldsList = null;
            if (returnFields != null && !returnFields.isEmpty()) {
                String[] fields = returnFields.split(",");
                returnFieldsList = new ArrayList<>();
                for (String field : fields) {
                    String trimmed = field.trim();
                    if (!trimmed.isEmpty()) {
                        returnFieldsList.add(trimmed);
                    }
                }
            }

            // Call service
            NearbyParcelsResult result = getNearbyParcelsService().getParcelsInBounds(
                formId,
                geometryFieldId,
                minLng, minLat, maxLng, maxLat,
                excludeRecordId,
                filterCondition,
                returnFieldsList,
                maxResults
            );

            // Build response
            JSONObject response = new JSONObject();
            response.put("success", true);

            JSONObject data = new JSONObject();

            // Build parcels array
            JSONArray parcelsArray = new JSONArray();
            for (NearbyParcel parcel : result.getParcels()) {
                JSONObject parcelJson = new JSONObject();
                parcelJson.put("recordId", parcel.getRecordId());

                // Parse geometry JSON string to JSONObject
                try {
                    parcelJson.put("geometry", new JSONObject(parcel.getGeometry()));
                } catch (Exception e) {
                    parcelJson.put("geometry", parcel.getGeometry());
                }

                // Centroid as GeoJSON Point
                JSONObject centroid = new JSONObject();
                centroid.put("type", "Point");
                JSONArray centroidCoords = new JSONArray();
                centroidCoords.put(round(parcel.getCentroidLongitude(), 6));
                centroidCoords.put(round(parcel.getCentroidLatitude(), 6));
                centroid.put("coordinates", centroidCoords);
                parcelJson.put("centroid", centroid);

                parcelJson.put("areaHectares", round(parcel.getAreaHectares(), 2));

                // Add record data if present
                if (parcel.getRecordData() != null && !parcel.getRecordData().isEmpty()) {
                    JSONObject recordData = new JSONObject();
                    for (Map.Entry<String, String> entry : parcel.getRecordData().entrySet()) {
                        recordData.put(entry.getKey(), entry.getValue());
                    }
                    parcelJson.put("recordData", recordData);
                }

                parcelsArray.put(parcelJson);
            }
            data.put("parcels", parcelsArray);

            data.put("totalCount", result.getTotalCount());

            // Bounds object
            JSONObject boundsJson = new JSONObject();
            boundsJson.put("minLongitude", result.getMinLongitude());
            boundsJson.put("minLatitude", result.getMinLatitude());
            boundsJson.put("maxLongitude", result.getMaxLongitude());
            boundsJson.put("maxLatitude", result.getMaxLatitude());
            data.put("bounds", boundsJson);

            data.put("truncated", result.isTruncated());

            response.put("data", data);
            response.put("meta", buildMeta(requestId, startTime));

            LogUtil.info(CLASS_NAME, "NearbyParcels [" + requestId + "] returned " +
                result.getParcels().size() + " parcels (total: " + result.getTotalCount() + ")");

            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "getNearbyParcels", requestId, startTime);
        }
    }

    // ==========================================================================
    // HEALTH CHECK ENDPOINT
    // ==========================================================================

    /**
     * Service health check.
     *
     * Endpoint: GET /jw/api/gis/gis/health
     */
    @Operation(
        path = "/gis/health",
        type = Operation.MethodType.GET,
        summary = "Health check",
        description = "Returns service health status and version information."
    )
    @Responses({
        @Response(responseCode = 200, description = "Service is healthy")
    })
    public ApiResponse health() {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        JSONObject response = new JSONObject();
        response.put("success", true);

        JSONObject data = new JSONObject();
        data.put("status", "healthy");
        data.put("version", getVersion());

        // Capabilities per spec section 4.7
        JSONObject capabilities = new JSONObject();
        capabilities.put("calculate", true);
        capabilities.put("validate", true);
        capabilities.put("simplify", true);
        capabilities.put("checkOverlap", true);
        capabilities.put("batchCalculate", true);
        capabilities.put("geocode", true);
        capabilities.put("reverseGeocode", true);
        capabilities.put("nearbyParcels", true);
        data.put("capabilities", capabilities);

        data.put("geometryEngine", "JTS 1.19.0");
        data.put("geocodingProvider", "Nominatim");

        // Supported geometry types
        JSONArray geometryTypes = new JSONArray();
        geometryTypes.put("Polygon");
        geometryTypes.put("MultiPolygon");
        data.put("supportedGeometryTypes", geometryTypes);

        response.put("data", data);
        response.put("meta", buildMeta(requestId, startTime));

        return new ApiResponse(200, response.toString());
    }

    // ==========================================================================
    // BATCH CALCULATE ENDPOINT
    // ==========================================================================

    /**
     * Batch calculate metrics for multiple geometries.
     *
     * Endpoint: POST /jw/api/gis/gis/batchCalculate
     */
    @Operation(
        path = "/gis/batchCalculate",
        type = Operation.MethodType.POST,
        summary = "Batch calculate geometry metrics",
        description = "Calculates metrics for multiple geometries in a single request."
    )
    @Responses({
        @Response(responseCode = 200, description = "Batch calculation completed"),
        @Response(responseCode = 400, description = "Invalid request"),
        @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse batchCalculate(
        @Param(value = "body", required = true) String requestBody
    ) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        LogUtil.info(CLASS_NAME, "=== GIS BatchCalculate Request [" + requestId + "] ===");

        // Check rate limit
        ApiResponse rateLimitError = checkRateLimit("batchCalculate", requestId, startTime);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        try {
            // Validate request size
            InputValidator.ValidationResult sizeResult = InputValidator.validateRequestSize(requestBody);
            if (!sizeResult.isValid()) {
                return errorResponse(413, sizeResult, requestId, startTime);
            }

            JSONObject request = new JSONObject(requestBody);
            JSONArray geometries = request.optJSONArray("geometries");

            if (geometries == null || geometries.length() == 0) {
                return errorResponse(400, "MISSING_GEOMETRIES",
                    "Array of geometries is required", requestId, startTime);
            }

            // Validate batch size to prevent OOM attacks
            if (geometries.length() > MAX_BATCH_SIZE) {
                JSONObject details = new JSONObject();
                details.put("requestedSize", geometries.length());
                details.put("maxAllowed", MAX_BATCH_SIZE);
                return errorResponse(400, "BATCH_TOO_LARGE",
                    String.format("Batch size (%d) exceeds maximum allowed (%d)",
                        geometries.length(), MAX_BATCH_SIZE),
                    details, requestId, startTime);
            }

            boolean continueOnError = request.optBoolean("continueOnError", true);
            
            JSONArray results = new JSONArray();
            int successful = 0;
            int failed = 0;
            
            for (int i = 0; i < geometries.length(); i++) {
                JSONObject item = geometries.getJSONObject(i);
                String id = item.optString("id", "geometry-" + i);
                String geoJson = extractGeometry(item);
                
                JSONObject result = new JSONObject();
                result.put("id", id);
                
                try {
                    if (geoJson == null || geoJson.isEmpty()) {
                        throw new IllegalArgumentException("Geometry is missing");
                    }
                    
                    Geometry geometry = getGeometryEngine().parseGeoJson(geoJson);
                    CalculateResult calcResult = getGeometryEngine().calculate(geometry, false, false);
                    
                    result.put("success", true);
                    JSONObject data = new JSONObject();
                    data.put("areaHectares", round(calcResult.getAreaHectares(), 4));
                    data.put("perimeterMeters", round(calcResult.getPerimeterMeters(), 2));
                    // Centroid as GeoJSON Point per spec section 4.1
                    JSONObject centroid = new JSONObject();
                    centroid.put("type", "Point");
                    JSONArray centroidCoords = new JSONArray();
                    centroidCoords.put(round(calcResult.getCentroidLongitude(), 7));
                    centroidCoords.put(round(calcResult.getCentroidLatitude(), 7));
                    centroid.put("coordinates", centroidCoords);
                    data.put("centroid", centroid);
                    data.put("vertexCount", calcResult.getVertexCount());
                    result.put("data", data);
                    successful++;
                    
                } catch (Exception e) {
                    result.put("success", false);
                    JSONObject error = new JSONObject();
                    error.put("code", "CALCULATION_ERROR");
                    error.put("message", e.getMessage());
                    result.put("error", error);
                    failed++;
                    
                    if (!continueOnError) {
                        break;
                    }
                }
                
                results.put(result);
            }
            
            // Build response
            JSONObject response = new JSONObject();
            response.put("success", true);
            
            JSONObject data = new JSONObject();
            data.put("results", results);
            
            JSONObject summary = new JSONObject();
            summary.put("total", geometries.length());
            summary.put("successful", successful);
            summary.put("failed", failed);
            data.put("summary", summary);
            
            response.put("data", data);
            response.put("meta", buildMeta(requestId, startTime));
            
            LogUtil.info(CLASS_NAME, "BatchCalculate: " + successful + "/" + geometries.length() + " successful");
            
            return new ApiResponse(200, response.toString());

        } catch (Exception e) {
            return serverErrorResponse(e, "batchCalculate", requestId, startTime);
        }
    }

    // ==========================================================================
    // HELPER METHODS
    // ==========================================================================

    /**
     * Extract geometry string from request (handles both direct geometry and Feature).
     */
    private String extractGeometry(JSONObject request) {
        // Try direct geometry object
        JSONObject geomObj = request.optJSONObject("geometry");
        if (geomObj != null) {
            return geomObj.toString();
        }
        
        // Try geometry string
        String geomStr = request.optString("geometry", null);
        if (geomStr != null && !geomStr.isEmpty()) {
            return geomStr;
        }
        
        // Check if request itself is a geometry
        String type = request.optString("type", null);
        if ("Polygon".equals(type) || "MultiPolygon".equals(type) || "Feature".equals(type)) {
            return request.toString();
        }
        
        return null;
    }

    /**
     * Check if geometry is a polygon type.
     */
    private boolean isPolygonType(Geometry geometry) {
        return geometry.getGeometryType().equals("Polygon") || 
               geometry.getGeometryType().equals("MultiPolygon");
    }

    /**
     * Round a double to specified decimal places.
     */
    private double round(double value, int places) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value)
            .setScale(places, RoundingMode.HALF_UP)
            .doubleValue();
    }

    /**
     * Build meta object for response.
     */
    private JSONObject buildMeta(String requestId, long startTime) {
        JSONObject meta = new JSONObject();
        meta.put("requestId", requestId);
        meta.put("processingTimeMs", System.currentTimeMillis() - startTime);
        return meta;
    }

    /**
     * Build error response.
     */
    private ApiResponse errorResponse(int statusCode, String errorCode, String message,
                                       String requestId, long startTime) {
        return errorResponse(statusCode, errorCode, message, null, requestId, startTime);
    }

    /**
     * Build error response with details object (per spec section 6.1).
     */
    private ApiResponse errorResponse(int statusCode, String errorCode, String message,
                                       JSONObject details, String requestId, long startTime) {
        JSONObject response = new JSONObject();
        response.put("success", false);

        JSONObject error = new JSONObject();
        error.put("code", errorCode);
        error.put("message", message);
        if (details != null && details.length() > 0) {
            error.put("details", details);
        }
        response.put("error", error);

        response.put("meta", buildMeta(requestId, startTime));

        return new ApiResponse(statusCode, response.toString());
    }

    /**
     * Build error response from InputValidator.ValidationResult.
     */
    private ApiResponse errorResponse(int statusCode, InputValidator.ValidationResult validationResult,
                                       String requestId, long startTime) {
        return errorResponse(statusCode, validationResult.getErrorCode(),
            validationResult.getMessage(), validationResult.toDetailsJson(), requestId, startTime);
    }

    /**
     * Build sanitized server error response (hides internal details).
     * Logs the full exception but returns generic message to client.
     */
    private ApiResponse serverErrorResponse(Exception e, String operation,
                                             String requestId, long startTime) {
        LogUtil.error(CLASS_NAME, e, "Error in " + operation + " [" + requestId + "]");
        String safeMessage = "An internal error occurred. Please try again or contact support.";
        JSONObject details = new JSONObject();
        details.put("requestId", requestId);
        details.put("operation", operation);
        return errorResponse(500, "SERVER_ERROR", safeMessage, details, requestId, startTime);
    }

    /**
     * Check rate limit for endpoint and return error response if exceeded.
     * Returns null if request is allowed.
     */
    private ApiResponse checkRateLimit(String endpoint, String requestId, long startTime) {
        if (!RateLimiter.getInstance().isAllowed(endpoint)) {
            int limit = RateLimiter.getInstance().getRateLimit(endpoint);
            long resetSeconds = RateLimiter.getInstance().getSecondsUntilReset(endpoint);

            JSONObject details = new JSONObject();
            details.put("endpoint", endpoint);
            details.put("limit", limit);
            details.put("window", "1 minute");
            details.put("retryAfterSeconds", resetSeconds);

            return errorResponse(429, "RATE_LIMIT_EXCEEDED",
                String.format("Rate limit exceeded for %s endpoint. Limit is %d requests per minute.",
                    endpoint, limit),
                details, requestId, startTime);
        }
        return null;
    }

    /**
     * Validate common input constraints (size, vertex count, coordinate range).
     * Returns error response if validation fails, null if valid.
     */
    private ApiResponse validateInput(String requestBody, Geometry geometry,
                                       String requestId, long startTime) {
        // Check request size
        InputValidator.ValidationResult sizeResult = InputValidator.validateRequestSize(requestBody);
        if (!sizeResult.isValid()) {
            return errorResponse(413, sizeResult, requestId, startTime);
        }

        if (geometry != null) {
            // Check vertex count
            InputValidator.ValidationResult vertexResult = InputValidator.validateVertexCount(geometry);
            if (!vertexResult.isValid()) {
                return errorResponse(422, vertexResult, requestId, startTime);
            }

            // Check coordinate range
            InputValidator.ValidationResult coordResult = InputValidator.validateCoordinateRange(geometry);
            if (!coordResult.isValid()) {
                return errorResponse(422, coordResult, requestId, startTime);
            }
        }

        return null;
    }
}
