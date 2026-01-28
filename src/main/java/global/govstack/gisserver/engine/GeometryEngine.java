package global.govstack.gisserver.engine;

import global.govstack.gisserver.model.CalculateResult;
import global.govstack.gisserver.model.ValidationError;
import global.govstack.gisserver.model.ValidationResult;
import org.joget.commons.util.LogUtil;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

/**
 * Core geometry engine using JTS Topology Suite.
 * 
 * Provides geometry operations:
 * - Parse/serialize GeoJSON
 * - Calculate area, perimeter, centroid
 * - Validate geometry
 * - Simplify geometry
 * - Check intersections
 * 
 * All coordinates are in WGS84 (EPSG:4326) - longitude, latitude order.
 */
public class GeometryEngine {
    
    private static final String CLASS_NAME = GeometryEngine.class.getName();
    
    // Earth radius in meters (WGS84 mean radius)
    private static final double EARTH_RADIUS_METERS = 6371000.0;
    
    // Conversion factor: square meters to hectares
    private static final double SQ_METERS_TO_HECTARES = 10000.0;
    
    private final GeometryFactory geometryFactory;
    private final GeoJsonReader geoJsonReader;
    private final GeoJsonWriter geoJsonWriter;
    
    public GeometryEngine() {
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        this.geoJsonReader = new GeoJsonReader(geometryFactory);
        this.geoJsonWriter = new GeoJsonWriter();
        this.geoJsonWriter.setEncodeCRS(false);
    }
    
    // ==========================================================================
    // GEOJSON PARSING
    // ==========================================================================
    
    /**
     * Parse GeoJSON string to JTS Geometry.
     * 
     * @param geoJson GeoJSON geometry or feature string
     * @return JTS Geometry object
     * @throws GeometryParseException if parsing fails
     */
    public Geometry parseGeoJson(String geoJson) throws GeometryParseException {
        if (geoJson == null || geoJson.trim().isEmpty()) {
            throw new GeometryParseException("GeoJSON string is null or empty");
        }
        
        try {
            // Check if it's a Feature (has "geometry" property)
            String geometryJson = geoJson;
            if (geoJson.contains("\"type\":\"Feature\"") || geoJson.contains("\"type\": \"Feature\"")) {
                // Extract geometry from feature
                int geometryStart = geoJson.indexOf("\"geometry\"");
                if (geometryStart != -1) {
                    int braceStart = geoJson.indexOf("{", geometryStart);
                    int braceCount = 1;
                    int braceEnd = braceStart + 1;
                    while (braceCount > 0 && braceEnd < geoJson.length()) {
                        char c = geoJson.charAt(braceEnd);
                        if (c == '{') braceCount++;
                        else if (c == '}') braceCount--;
                        braceEnd++;
                    }
                    geometryJson = geoJson.substring(braceStart, braceEnd);
                }
            }
            
            return geoJsonReader.read(geometryJson);
        } catch (ParseException e) {
            throw new GeometryParseException("Failed to parse GeoJSON: " + e.getMessage(), e);
        }
    }
    
    /**
     * Convert JTS Geometry to GeoJSON string.
     */
    public String toGeoJson(Geometry geometry) {
        return geoJsonWriter.write(geometry);
    }
    
    // ==========================================================================
    // AREA CALCULATION
    // ==========================================================================
    
    /**
     * Calculate area using spherical excess formula (geodesic).
     * 
     * Uses the Shoelace formula adapted for spherical coordinates.
     * Accuracy: ±0.1% for parcels under 100 hectares at mid-latitudes.
     * 
     * @param geometry Polygon geometry
     * @return Area in hectares
     */
    public double calculateAreaHectares(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return 0.0;
        }
        
        double areaSqMeters = calculateAreaSquareMeters(geometry);
        return areaSqMeters / SQ_METERS_TO_HECTARES;
    }
    
    /**
     * Calculate area in square meters using spherical excess formula.
     */
    public double calculateAreaSquareMeters(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return 0.0;
        }
        
        if (geometry instanceof Polygon) {
            return calculatePolygonAreaSqM((Polygon) geometry);
        } else if (geometry instanceof MultiPolygon) {
            double totalArea = 0.0;
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                totalArea += calculatePolygonAreaSqM((Polygon) geometry.getGeometryN(i));
            }
            return totalArea;
        }
        
        return 0.0;
    }
    
    /**
     * Calculate area of a single polygon using the spherical excess formula.
     */
    private double calculatePolygonAreaSqM(Polygon polygon) {
        // Calculate exterior ring area
        double area = calculateRingAreaSqM(polygon.getExteriorRing().getCoordinates());
        
        // Subtract hole areas
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            area -= calculateRingAreaSqM(polygon.getInteriorRingN(i).getCoordinates());
        }
        
        return Math.abs(area);
    }
    
    /**
     * Calculate area of a coordinate ring using spherical excess formula.
     * 
     * Based on: https://en.wikipedia.org/wiki/Shoelace_formula
     * Adapted for spherical coordinates.
     */
    private double calculateRingAreaSqM(Coordinate[] coords) {
        if (coords.length < 4) {
            return 0.0;
        }
        
        double area = 0.0;
        int n = coords.length - 1; // Last point is same as first
        
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            
            double lat1 = Math.toRadians(coords[i].y);
            double lat2 = Math.toRadians(coords[j].y);
            double lon1 = Math.toRadians(coords[i].x);
            double lon2 = Math.toRadians(coords[j].x);
            
            area += (lon2 - lon1) * (2 + Math.sin(lat1) + Math.sin(lat2));
        }
        
        area = Math.abs(area * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / 2.0);
        
        return area;
    }
    
    // ==========================================================================
    // PERIMETER CALCULATION
    // ==========================================================================
    
    /**
     * Calculate perimeter using geodesic distance (Haversine formula).
     * 
     * @param geometry Polygon geometry
     * @return Perimeter in meters
     */
    public double calculatePerimeterMeters(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return 0.0;
        }
        
        if (geometry instanceof Polygon) {
            return calculatePolygonPerimeter((Polygon) geometry);
        } else if (geometry instanceof MultiPolygon) {
            double totalPerimeter = 0.0;
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                totalPerimeter += calculatePolygonPerimeter((Polygon) geometry.getGeometryN(i));
            }
            return totalPerimeter;
        }
        
        return 0.0;
    }
    
    private double calculatePolygonPerimeter(Polygon polygon) {
        // Only count exterior ring for perimeter
        return calculateRingPerimeter(polygon.getExteriorRing().getCoordinates());
    }
    
    private double calculateRingPerimeter(Coordinate[] coords) {
        double perimeter = 0.0;
        
        for (int i = 0; i < coords.length - 1; i++) {
            perimeter += haversineDistance(coords[i], coords[i + 1]);
        }
        
        return perimeter;
    }
    
    /**
     * Calculate Haversine distance between two coordinates.
     * 
     * @return Distance in meters
     */
    private double haversineDistance(Coordinate c1, Coordinate c2) {
        double lat1 = Math.toRadians(c1.y);
        double lat2 = Math.toRadians(c2.y);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(c2.x - c1.x);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(lat1) * Math.cos(lat2) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_METERS * c;
    }
    
    // ==========================================================================
    // CENTROID AND BOUNDING BOX
    // ==========================================================================
    
    /**
     * Calculate centroid point.
     * 
     * @return Coordinate with centroid (x=longitude, y=latitude)
     */
    public Coordinate calculateCentroid(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return new Coordinate(0, 0);
        }
        
        Point centroid = geometry.getCentroid();
        return centroid.getCoordinate();
    }
    
    /**
     * Get bounding box envelope.
     */
    public Envelope getBoundingBox(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return new Envelope();
        }
        return geometry.getEnvelopeInternal();
    }
    
    // ==========================================================================
    // VERTEX OPERATIONS
    // ==========================================================================
    
    /**
     * Count vertices in geometry.
     * For polygons, excludes the duplicate closing point of each ring.
     */
    public int getVertexCount(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return 0;
        }

        if (geometry instanceof Polygon) {
            return countPolygonVertices((Polygon) geometry);
        } else if (geometry instanceof MultiPolygon) {
            int total = 0;
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                total += countPolygonVertices((Polygon) geometry.getGeometryN(i));
            }
            return total;
        }

        return geometry.getNumPoints();
    }

    /**
     * Count vertices in a polygon, properly handling interior rings (holes).
     * Each ring has a duplicate closing point which is excluded from the count.
     */
    private int countPolygonVertices(Polygon polygon) {
        // Exterior ring: subtract 1 for closing point
        int count = polygon.getExteriorRing().getNumPoints() - 1;

        // Interior rings (holes): subtract 1 for each closing point
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            count += polygon.getInteriorRingN(i).getNumPoints() - 1;
        }

        return Math.max(0, count);
    }
    
    // ==========================================================================
    // VALIDATION
    // ==========================================================================

    /**
     * Validate geometry against rules (backward compatible overload).
     */
    public ValidationResult validate(Geometry geometry,
                                     double minAreaHa, double maxAreaHa,
                                     int minVertices, int maxVertices,
                                     boolean allowSelfIntersection) {
        return validate(geometry, minAreaHa, maxAreaHa, minVertices, maxVertices,
                       allowSelfIntersection, true, true, 10.0);
    }

    /**
     * Validate geometry against configurable rules.
     *
     * Per spec section 4.2, this validates:
     * - Topology validity (self-intersection, invalid rings)
     * - Vertex count limits
     * - Area limits
     * - Holes (if not allowed)
     * - Spikes (acute angles below threshold)
     *
     * @param geometry The geometry to validate
     * @param minAreaHa Minimum area in hectares (warning if below)
     * @param maxAreaHa Maximum area in hectares (error if exceeded, 0 = no limit)
     * @param minVertices Minimum vertex count (error)
     * @param maxVertices Maximum vertex count (error)
     * @param allowSelfIntersection Allow self-intersecting polygons
     * @param allowHoles Allow polygons with interior rings (holes)
     * @param detectSpikes Detect acute angles (spikes)
     * @param spikeAngleThreshold Angle in degrees below which to warn
     * @return ValidationResult with structured errors and warnings
     */
    public ValidationResult validate(Geometry geometry,
                                     double minAreaHa, double maxAreaHa,
                                     int minVertices, int maxVertices,
                                     boolean allowSelfIntersection,
                                     boolean allowHoles,
                                     boolean detectSpikes,
                                     double spikeAngleThreshold) {

        ValidationResult result = new ValidationResult();

        if (geometry == null || geometry.isEmpty()) {
            result.addError(ValidationError.EMPTY_GEOMETRY, "Geometry is null or empty");
            return result;
        }

        // Check geometry type
        if (!(geometry instanceof Polygon) && !(geometry instanceof MultiPolygon)) {
            result.addError(ValidationError.INVALID_GEOMETRY_TYPE,
                "Expected Polygon or MultiPolygon, got: " + geometry.getGeometryType());
            return result;
        }

        // Calculate metrics
        double areaHa = calculateAreaHectares(geometry);
        double perimeterM = calculatePerimeterMeters(geometry);
        int vertices = getVertexCount(geometry);

        result.setAreaHectares(areaHa);
        result.setPerimeterMeters(perimeterM);
        result.setVertexCount(vertices);

        // Check topology validity (self-intersection, invalid rings)
        if (!allowSelfIntersection) {
            IsValidOp validOp = new IsValidOp(geometry);
            if (!validOp.isValid()) {
                TopologyValidationError error = validOp.getValidationError();
                if (error != null) {
                    String errorCode = mapJtsErrorCode(error.getErrorType());
                    Coordinate loc = error.getCoordinate();
                    if (loc != null) {
                        result.addErrorAt(errorCode, error.getMessage(), loc.x, loc.y);
                    } else {
                        result.addError(errorCode, error.getMessage());
                    }
                } else {
                    result.addError(ValidationError.SELF_INTERSECTION,
                        "Geometry contains self-intersections");
                }
            }
        }

        // Check holes
        if (!allowHoles) {
            int holeCount = countHoles(geometry);
            if (holeCount > 0) {
                result.addError(ValidationError.HOLES_NOT_ALLOWED,
                    String.format("Polygon has %d hole(s), but holes are not allowed", holeCount));
            }
        }

        // Check area limits
        if (areaHa <= 0) {
            if (areaHa < 0) {
                result.addError(ValidationError.AREA_NEGATIVE,
                    String.format("Calculated area is negative (%.4f ha) - check ring orientation", areaHa));
            } else {
                result.addError(ValidationError.AREA_ZERO, "Calculated area is zero");
            }
        } else {
            if (minAreaHa > 0 && areaHa < minAreaHa) {
                result.addWarning(ValidationError.AREA_BELOW_MINIMUM,
                    String.format("Area (%.4f ha) is below configured minimum (%.4f ha)", areaHa, minAreaHa));
            }
            if (maxAreaHa > 0 && areaHa > maxAreaHa) {
                result.addError(ValidationError.AREA_EXCEEDS_MAXIMUM,
                    String.format("Area (%.4f ha) exceeds configured maximum (%.4f ha)", areaHa, maxAreaHa));
            }
        }

        // Check vertex limits
        if (vertices < minVertices) {
            result.addError(ValidationError.TOO_FEW_VERTICES,
                String.format("Vertex count (%d) is below minimum (%d)", vertices, minVertices));
        }
        if (maxVertices > 0 && vertices > maxVertices) {
            result.addError(ValidationError.TOO_MANY_VERTICES,
                String.format("Vertex count (%d) exceeds maximum (%d)", vertices, maxVertices));
        }

        // Check vertices near limit (warning at 90% of max)
        if (maxVertices > 0 && vertices > maxVertices * 0.9 && vertices <= maxVertices) {
            result.addWarning(ValidationError.VERTICES_NEAR_LIMIT,
                String.format("Vertex count (%d) is approaching maximum (%d)", vertices, maxVertices));
        }

        // Detect duplicate consecutive vertices
        detectDuplicateVertices(geometry, result);

        // Detect spikes (acute angles)
        if (detectSpikes) {
            detectSpikes(geometry, spikeAngleThreshold, result);
        }

        return result;
    }

    /**
     * Map JTS topology error type to spec error code.
     */
    private String mapJtsErrorCode(int jtsErrorType) {
        switch (jtsErrorType) {
            case TopologyValidationError.SELF_INTERSECTION:
            case TopologyValidationError.RING_SELF_INTERSECTION:
                return ValidationError.SELF_INTERSECTION;
            case TopologyValidationError.RING_NOT_CLOSED:
            case TopologyValidationError.TOO_FEW_POINTS:
            case TopologyValidationError.INVALID_COORDINATE:
                return ValidationError.INVALID_RING;
            default:
                return ValidationError.SELF_INTERSECTION;
        }
    }

    /**
     * Count holes in a geometry.
     */
    private int countHoles(Geometry geometry) {
        int count = 0;
        if (geometry instanceof Polygon) {
            count = ((Polygon) geometry).getNumInteriorRing();
        } else if (geometry instanceof MultiPolygon) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                count += ((Polygon) geometry.getGeometryN(i)).getNumInteriorRing();
            }
        }
        return count;
    }

    /**
     * Detect duplicate consecutive vertices.
     */
    private void detectDuplicateVertices(Geometry geometry, ValidationResult result) {
        Coordinate[] coords = geometry.getCoordinates();
        for (int i = 0; i < coords.length - 1; i++) {
            if (coords[i].equals2D(coords[i + 1])) {
                result.addWarningAt(ValidationError.DUPLICATE_VERTICES,
                    "Consecutive duplicate vertices found", coords[i].x, coords[i].y);
                return; // Only report first occurrence
            }
        }
    }

    /**
     * Detect spikes (very acute angles) in the geometry.
     *
     * @param geometry The geometry to check
     * @param thresholdDegrees Angle below which to warn (e.g., 10 degrees)
     * @param result ValidationResult to add warnings to
     */
    private void detectSpikes(Geometry geometry, double thresholdDegrees, ValidationResult result) {
        if (geometry instanceof Polygon) {
            detectSpikesInPolygon((Polygon) geometry, thresholdDegrees, result);
        } else if (geometry instanceof MultiPolygon) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                detectSpikesInPolygon((Polygon) geometry.getGeometryN(i), thresholdDegrees, result);
            }
        }
    }

    private void detectSpikesInPolygon(Polygon polygon, double thresholdDegrees, ValidationResult result) {
        // Check exterior ring
        detectSpikesInRing(polygon.getExteriorRing().getCoordinates(), thresholdDegrees, result);

        // Check interior rings (holes)
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            detectSpikesInRing(polygon.getInteriorRingN(i).getCoordinates(), thresholdDegrees, result);
        }
    }

    private void detectSpikesInRing(Coordinate[] coords, double thresholdDegrees, ValidationResult result) {
        if (coords.length < 4) return; // Need at least a triangle

        int n = coords.length - 1; // Last point is same as first (closed ring)

        for (int i = 0; i < n; i++) {
            Coordinate prev = coords[(i - 1 + n) % n];
            Coordinate curr = coords[i];
            Coordinate next = coords[(i + 1) % n];

            double angle = calculateAngleDegrees(prev, curr, next);

            if (angle < thresholdDegrees) {
                result.addWarningAt(ValidationError.SPIKE_DETECTED,
                    String.format("Very acute angle (%.1f degrees) detected - possible spike", angle),
                    curr.x, curr.y);
            }
        }
    }

    /**
     * Calculate the interior angle at point B in degrees.
     */
    private double calculateAngleDegrees(Coordinate a, Coordinate b, Coordinate c) {
        // Vectors BA and BC
        double baX = a.x - b.x;
        double baY = a.y - b.y;
        double bcX = c.x - b.x;
        double bcY = c.y - b.y;

        // Dot product and magnitudes
        double dot = baX * bcX + baY * bcY;
        double magBA = Math.sqrt(baX * baX + baY * baY);
        double magBC = Math.sqrt(bcX * bcX + bcY * bcY);

        if (magBA == 0 || magBC == 0) {
            return 0; // Degenerate case
        }

        // Clamp to [-1, 1] to handle floating point errors
        double cosAngle = Math.max(-1, Math.min(1, dot / (magBA * magBC)));

        return Math.toDegrees(Math.acos(cosAngle));
    }
    
    // ==========================================================================
    // SIMPLIFICATION
    // ==========================================================================
    
    /**
     * Simplify geometry using Douglas-Peucker algorithm.
     * 
     * @param geometry Input geometry
     * @param tolerance Simplification tolerance in degrees (e.g., 0.00001 ≈ 1m)
     * @return Simplified geometry
     */
    public Geometry simplify(Geometry geometry, double tolerance) {
        if (geometry == null || geometry.isEmpty()) {
            return geometry;
        }
        
        return DouglasPeuckerSimplifier.simplify(geometry, tolerance);
    }
    
    /**
     * Fix invalid geometry.
     */
    public Geometry fixGeometry(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return geometry;
        }
        
        try {
            return GeometryFixer.fix(geometry);
        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME, "Failed to fix geometry: " + e.getMessage());
            return geometry;
        }
    }
    
    // ==========================================================================
    // SPATIAL OPERATIONS
    // ==========================================================================
    
    /**
     * Check if two geometries intersect.
     */
    public boolean intersects(Geometry g1, Geometry g2) {
        if (g1 == null || g2 == null || g1.isEmpty() || g2.isEmpty()) {
            return false;
        }
        return g1.intersects(g2);
    }
    
    /**
     * Get intersection of two geometries.
     */
    public Geometry intersection(Geometry g1, Geometry g2) {
        if (g1 == null || g2 == null || g1.isEmpty() || g2.isEmpty()) {
            return null;
        }
        return g1.intersection(g2);
    }
    
    /**
     * Create polygon from coordinate array.
     * Coordinates should be [longitude, latitude] pairs.
     */
    public Polygon createPolygon(double[][] coordinates) {
        if (coordinates == null || coordinates.length < 4) {
            return null;
        }
        
        Coordinate[] coords = new Coordinate[coordinates.length];
        for (int i = 0; i < coordinates.length; i++) {
            coords[i] = new Coordinate(coordinates[i][0], coordinates[i][1]);
        }
        
        return geometryFactory.createPolygon(coords);
    }
    
    // ==========================================================================
    // FULL CALCULATION
    // ==========================================================================
    
    /**
     * Perform complete calculation and return all metrics.
     */
    public CalculateResult calculate(Geometry geometry, boolean includeBoundingBox, boolean includeAreaSqM) {
        CalculateResult result = new CalculateResult();
        
        if (geometry == null || geometry.isEmpty()) {
            return result;
        }
        
        // Area
        double areaSqM = calculateAreaSquareMeters(geometry);
        result.setAreaHectares(areaSqM / SQ_METERS_TO_HECTARES);
        if (includeAreaSqM) {
            result.setAreaSquareMeters(areaSqM);
        }
        
        // Perimeter
        result.setPerimeterMeters(calculatePerimeterMeters(geometry));
        
        // Centroid
        Coordinate centroid = calculateCentroid(geometry);
        result.setCentroidLatitude(centroid.y);
        result.setCentroidLongitude(centroid.x);
        
        // Vertex count
        result.setVertexCount(getVertexCount(geometry));
        
        // Bounding box
        if (includeBoundingBox) {
            Envelope env = getBoundingBox(geometry);
            result.setBoundingBox(env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY());
        }
        
        return result;
    }
    
    // ==========================================================================
    // EXCEPTION
    // ==========================================================================
    
    /**
     * Exception for geometry parsing errors.
     */
    public static class GeometryParseException extends Exception {
        public GeometryParseException(String message) {
            super(message);
        }
        
        public GeometryParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
