# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the OSGi bundle
mvn clean package

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Output JAR location
target/joget-gis-server-8.1-SNAPSHOT.jar
```

## Deployment & Testing

Deploy to Joget DX8 jdx4 instance:
```bash
cp target/joget-gis-server-8.1-SNAPSHOT.jar ~/joget-enterprise-linux-9.0.0/data/jdx4/plugins/
```

Test endpoints (base URL: `/jw/api/gis/gis/...`):
```bash
# Health check
curl http://localhost:8084/jw/api/gis/gis/health

# Calculate area/perimeter
curl -X POST http://localhost:8084/jw/api/gis/gis/calculate \
  -H "Content-Type: application/json" \
  -d '{"geometry":{"type":"Polygon","coordinates":[[[28.1,-29.3],[28.2,-29.3],[28.2,-29.4],[28.1,-29.4],[28.1,-29.3]]]}}'
```

## Architecture

This is a **Joget DX8 API Builder plugin** providing REST endpoints for GIS geometry operations. It works with the companion **joget-gis-ui** frontend form element plugin.

### Core Components

- **GisApiProvider** (`lib/GisApiProvider.java`) - REST API plugin with annotated endpoints:
  - `POST /gis/calculate` - Area, perimeter, centroid, bounding box
  - `POST /gis/validate` - Rule-based geometry validation
  - `POST /gis/simplify` - Douglas-Peucker vertex reduction
  - `POST /gis/checkOverlap` - Database overlap detection
  - `POST /gis/batchCalculate` - Multi-geometry processing
  - `GET /gis/health` - Health check

- **GeometryEngine** (`engine/GeometryEngine.java`) - JTS-based geometry operations:
  - Spherical area/perimeter calculation (not planar)
  - GeoJSON parsing/serialization
  - Topology validation and fixing
  - Uses Earth radius 6,371,000m for geodesic calculations

- **OverlapService** (`service/OverlapService.java`) - Queries Joget form data for spatial overlap detection

### Response Envelope

All endpoints return:
```json
{
  "success": true|false,
  "data": { ... },
  "error": { "code": "...", "message": "..." },
  "meta": { "requestId": "...", "processingTimeMs": ... }
}
```

### Calculate Response Format

The `/calculate` endpoint returns centroid as a GeoJSON Point:
```json
{
  "data": {
    "areaHectares": 0.82,
    "perimeterMeters": 362.50,
    "centroid": {
      "type": "Point",
      "coordinates": [28.1234, -29.3145]
    },
    "boundingBox": { ... },
    "vertexCount": 4
  }
}
```

### Health Response Format

The `/health` endpoint includes capabilities:
```json
{
  "data": {
    "status": "healthy",
    "version": "8.1-SNAPSHOT",
    "capabilities": {
      "calculate": true,
      "validate": true,
      "simplify": true,
      "checkOverlap": true,
      "batchCalculate": true,
      "geocode": false,
      "reverseGeocode": false
    },
    "geometryEngine": "JTS 1.19.0",
    "geocodingProvider": "none",
    "supportedGeometryTypes": ["Polygon", "MultiPolygon"]
  }
}
```

### Key Design Patterns

1. **OSGi Bundle** - JTS Topology Suite 1.19.0 is embedded (`Embed-Dependency`) to avoid classpath conflicts
2. **Lazy Initialization** - GeometryEngine and OverlapService are lazily created
3. **WGS84 Coordinates** - All coordinates use EPSG:4326 (longitude, latitude order per GeoJSON spec)
4. **Geodesic Calculations** - Area uses spherical excess formula; perimeter uses Haversine distance

## Related Projects

| Project | Purpose |
|---------|---------|
| joget-gis-ui | Frontend form element that calls this API |
| joget-rules-api | Same API Builder plugin pattern |
