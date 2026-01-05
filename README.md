# Joget GIS Server Plugin

Backend REST API for GIS geometry operations in Joget DX8.

## Overview

This plugin provides REST API endpoints for:
- **Calculate** - Area, perimeter, centroid, bounding box
- **Validate** - Check geometry against configurable rules
- **Simplify** - Reduce vertex count using Douglas-Peucker
- **Check Overlap** - Find overlapping geometries in any Joget form
- **Geocode** - Search for location by name (Nominatim/OpenStreetMap)
- **Reverse Geocode** - Get place name from coordinates
- **Batch Calculate** - Process multiple geometries
- **Health Check** - Service status and capabilities

## Technology Stack

| Component | Technology |
|-----------|------------|
| Platform | Joget DX8 API Builder Plugin |
| Geometry Engine | JTS Topology Suite 1.19.0 |
| Geocoding Provider | Nominatim (OpenStreetMap) |
| Coordinate System | WGS84 (EPSG:4326) |

## Installation

1. Build the plugin:
   ```bash
   mvn clean package
   ```

2. Deploy to Joget:
   ```bash
   cp target/joget-gis-server-8.1-SNAPSHOT.jar /path/to/joget/data/plugins/
   ```

3. Configure:
   - Go to Settings > API Builder
   - Enable the "GIS Geometry API" endpoint

## API Endpoints

### Base URL
```
/jw/api/gis/gis/...
```

### Authentication
All endpoints require Joget API authentication:
```bash
curl -H "api_id: YOUR_API_ID" -H "api_key: YOUR_API_KEY" ...
```

---

### Calculate Metrics

Calculate area, perimeter, centroid, and bounding box.

**Endpoint:** `POST /gis/calculate`

**Request:**
```json
{
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[28.1234, -29.3145], [28.1256, -29.3145], [28.1256, -29.3167], [28.1234, -29.3167], [28.1234, -29.3145]]]
  },
  "options": {
    "includeAreaInSquareMeters": true,
    "includeBoundingBox": true
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "areaHectares": 0.8234,
    "areaSquareMeters": 8234.00,
    "perimeterMeters": 362.45,
    "centroid": {
      "type": "Point",
      "coordinates": [28.1245, -29.3156]
    },
    "boundingBox": {
      "minLongitude": 28.1234,
      "maxLongitude": 28.1256,
      "minLatitude": -29.3167,
      "maxLatitude": -29.3145
    },
    "vertexCount": 4
  },
  "meta": {
    "requestId": "uuid",
    "processingTimeMs": 12
  }
}
```

---

### Validate Geometry

Validate geometry against configurable rules.

**Endpoint:** `POST /gis/validate`

**Request:**
```json
{
  "geometry": { ... },
  "rules": {
    "minAreaHectares": 0.01,
    "maxAreaHectares": 1000,
    "minVertices": 3,
    "maxVertices": 100,
    "allowSelfIntersection": false,
    "allowHoles": true
  },
  "options": {
    "includeMetrics": true,
    "detectSpikes": true,
    "spikeAngleThreshold": 10
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "valid": true,
    "errors": [],
    "warnings": [],
    "metrics": {
      "areaHectares": 0.82,
      "perimeterMeters": 362.45,
      "vertexCount": 4
    }
  }
}
```

---

### Simplify Geometry

Reduce vertex count while preserving shape using Douglas-Peucker algorithm.

**Endpoint:** `POST /gis/simplify`

**Request:**
```json
{
  "geometry": { ... },
  "options": {
    "toleranceMeters": 5,
    "preserveTopology": true,
    "targetVertexCount": 20,
    "maxAreaChangePercent": 1.0
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "simplifiedGeometry": { ... },
    "originalVertexCount": 47,
    "simplifiedVertexCount": 12,
    "verticesRemoved": 35,
    "areaChange": {
      "originalHectares": 0.8234,
      "simplifiedHectares": 0.8215,
      "changePercent": -0.23
    }
  }
}
```

---

### Check Overlaps

Check for overlapping geometries in any Joget form.

**Endpoint:** `POST /gis/checkOverlap`

**Request:**
```json
{
  "geometry": { ... },
  "target": {
    "formId": "parcel",
    "geometryFieldId": "c_geometry",
    "excludeRecordId": "existing-record-id",
    "filterCondition": "c_status = ?",
    "filterParams": ["ACTIVE"]
  },
  "options": {
    "minOverlapPercent": 1.0,
    "maxResults": 10,
    "includeOverlapGeometry": true,
    "returnFields": ["c_name", "c_code", "c_owner"]
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "hasOverlaps": true,
    "overlaps": [
      {
        "recordId": "uuid",
        "overlapAreaHectares": 0.15,
        "overlapPercentOfInput": 18.3,
        "overlapPercentOfExisting": 12.5,
        "overlapGeometry": { ... },
        "recordData": {
          "c_name": "Northern Field",
          "c_code": "BER-2024-00123"
        }
      }
    ],
    "totalOverlapAreaHectares": 0.15,
    "checkedRecordCount": 47
  }
}
```

---

### Geocode Location

Search for a location by name.

**Endpoint:** `GET /gis/geocode`

**Parameters:**
- `query` (required) - Search term (min 3 characters)
- `limit` - Maximum results (default 5)
- `countryCode` - ISO country code to bias results
- `boundingBox` - Limit search area (minLon,minLat,maxLon,maxLat)

**Example:**
```bash
curl "/gis/geocode?query=Teyateyaneng&countryCode=LS&limit=5"
```

**Response:**
```json
{
  "success": true,
  "data": {
    "query": "Teyateyaneng",
    "totalResults": 1,
    "results": [
      {
        "displayName": "Teyateyaneng, Berea District, Lesotho",
        "location": {
          "type": "Point",
          "coordinates": [27.7386, -29.1475]
        },
        "type": "city",
        "boundingBox": { ... }
      }
    ]
  }
}
```

---

### Reverse Geocode

Get place name from coordinates.

**Endpoint:** `GET /gis/reverseGeocode`

**Parameters:**
- `lon` (required) - Longitude
- `lat` (required) - Latitude
- `zoom` - Detail level (default 14, higher = more specific)

**Example:**
```bash
curl "/gis/reverseGeocode?lon=28.1234&lat=-29.3145"
```

**Response:**
```json
{
  "success": true,
  "data": {
    "displayName": "Ha Matala, Berea District, Lesotho",
    "address": {
      "village": "Ha Matala",
      "district": "Berea District",
      "country": "Lesotho",
      "countryCode": "LS"
    },
    "location": {
      "type": "Point",
      "coordinates": [28.1234, -29.3145]
    }
  }
}
```

---

### Health Check

**Endpoint:** `GET /gis/health`

**Response:**
```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "version": "8.1-SNAPSHOT",
    "capabilities": {
      "calculate": true,
      "validate": true,
      "simplify": true,
      "checkOverlap": true,
      "batchCalculate": true,
      "geocode": true,
      "reverseGeocode": true
    },
    "geometryEngine": "JTS 1.19.0",
    "geocodingProvider": "Nominatim",
    "supportedGeometryTypes": ["Polygon", "MultiPolygon"]
  }
}
```

---

## Production Features

### Rate Limiting

| Endpoint | Limit |
|----------|-------|
| calculate | 100 req/min |
| validate | 100 req/min |
| simplify | 50 req/min |
| checkOverlap | 20 req/min |
| geocode | 30 req/min |
| reverseGeocode | 30 req/min |
| batchCalculate | 20 req/min |
| health | unlimited |

Rate limit exceeded returns HTTP 429:
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded for calculate endpoint",
    "details": {
      "limit": 100,
      "window": "1 minute",
      "retryAfterSeconds": 45
    }
  }
}
```

### Input Validation

- **Payload size**: Maximum 5 MB request body
- **Vertex count**: Maximum 10,000 vertices per geometry
- **Coordinate range**: WGS84 bounds (-180 to 180 longitude, -90 to 90 latitude)

### Geocoding

- Uses Nominatim (OpenStreetMap) - free, no API key required
- Results cached for 24 hours
- Rate limited to 1 request/second (Nominatim policy)

---

## Error Codes

| Code | HTTP | Description |
|------|------|-------------|
| `MISSING_GEOMETRY` | 400 | Geometry parameter is required |
| `INVALID_GEOJSON` | 400 | Failed to parse GeoJSON |
| `INVALID_GEOMETRY_TYPE` | 422 | Expected Polygon/MultiPolygon |
| `INVALID_COORDINATES` | 422 | Coordinates outside WGS84 range |
| `TOO_MANY_VERTICES` | 422 | Exceeds 10,000 vertex limit |
| `PAYLOAD_TOO_LARGE` | 413 | Request body exceeds 5 MB |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests |
| `SERVER_ERROR` | 500 | Unexpected server error |

All errors include a `details` object:
```json
{
  "error": {
    "code": "INVALID_COORDINATES",
    "message": "Geometry contains coordinates outside valid WGS84 range",
    "details": {
      "field": "geometry.coordinates",
      "reason": "longitude 200.000000 out of range [-180, 180]",
      "suggestion": "Ensure coordinates are in [longitude, latitude] order"
    }
  }
}
```

---

## GeoJSON Notes

- Coordinates use `[longitude, latitude]` order (GeoJSON standard)
- Coordinate system: WGS84 (EPSG:4326)
- Precision: 7 decimal places = ~1cm accuracy

## Area Calculation

Uses spherical excess formula for geodesic accuracy:
- Earth radius: 6,371,000 meters
- Accuracy: ±0.1% for polygons under 100 hectares
- Results in hectares (1 hectare = 10,000 m²)

---

## Integration with joget-gis-ui

This plugin provides the backend services for the `joget-gis-ui` form element plugin.
The UI plugin calls these endpoints for:
- Real-time area calculation during drawing
- Geometry validation before save
- Overlap checking
- Location search (geocoding)

---

## Development

### Build
```bash
mvn clean package
```

### Test
```bash
mvn test
```

### Deploy
```bash
cp target/joget-gis-server-8.1-SNAPSHOT.jar ~/joget/data/plugins/
```

### Project Structure
```
joget-gis-server/
├── src/main/java/global/govstack/gisserver/
│   ├── Activator.java
│   ├── lib/
│   │   └── GisApiProvider.java        # REST API endpoints
│   ├── engine/
│   │   └── GeometryEngine.java        # JTS geometry operations
│   ├── service/
│   │   ├── OverlapService.java        # Spatial overlap detection
│   │   └── GeocodingService.java      # Nominatim integration
│   ├── model/
│   │   ├── CalculateResult.java
│   │   ├── ValidationResult.java
│   │   ├── ValidationError.java
│   │   └── OverlapResult.java
│   └── util/
│       ├── RateLimiter.java           # Endpoint rate limiting
│       └── InputValidator.java        # Input validation
└── pom.xml
```

### Dependencies

- JTS Topology Suite 1.19.0 (embedded in bundle)
- Joget DX8 wflow-core (provided)
- Joget API Builder (provided)

---

## License

MIT License - GovStack Global 2024
