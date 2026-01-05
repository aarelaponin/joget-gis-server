08-gis-backend-spec# GIS Geometry Services - Backend API Specification

## Document Information

| Attribute | Value |
|-----------|-------|
| Document Type | Backend API Specification |
| Component | joget-gis-server Plugin |
| Version | 1.0 |
| Status | Draft |
| Related Document | GIS UI/UX Specification |
| Platform | Joget DX8 Enterprise Edition |

---

## 1. OVERVIEW

### 1.1 Purpose

This specification defines a **generic GIS backend service** for Joget DX8 that provides geometry calculations, validation, and spatial query capabilities. The plugin is application-agnostic and can be used for any polygon-based data capture: land parcels, farm boundaries, irrigation zones, protected areas, administrative boundaries, etc.

### 1.2 Scope

This specification covers:
- All API endpoints for geometry operations
- Request/response formats
- Validation rules and error handling
- Data models
- Security considerations

### 1.3 Design Principles

| Principle | Description |
|-----------|-------------|
| **Generic** | No business-domain assumptions; pure geometry operations |
| **Stateless** | Each request contains all information needed for processing |
| **Configurable** | All behavior controlled via request parameters |
| **Joget-Native** | Uses Joget APIs (FormDataDao) for all data access |
| **GovStack-Aligned** | Reusable building block approach |

### 1.4 Technology Stack

| Component | Technology |
|-----------|------------|
| **Platform** | Joget DX8 API Plugin (`ApiPluginAbstract`) |
| **Geometry Engine** | JTS Topology Suite 1.19.0 |
| **JSON Processing** | org.json (provided by Joget) |
| **Coordinate System** | WGS84 (EPSG:4326) |
| **Data Access** | Joget FormDataDao API |

---

## 2. API OVERVIEW

### 2.1 Base URL

```
/jw/api/{appId}/gis
```

Where `{appId}` is the Joget application ID.

**Examples:**
- `/jw/api/frs/gis/calculate` — Farmers Registry System
- `/jw/api/lrs/gis/validate` — Land Registry System
- `/jw/api/irr/gis/checkOverlap` — Irrigation Management

### 2.2 Endpoint Summary

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/gis/calculate` | POST | Calculate area, perimeter, centroid, bounding box |
| `/gis/validate` | POST | Validate geometry against configurable rules |
| `/gis/simplify` | POST | Reduce vertex count (Douglas-Peucker) |
| `/gis/checkOverlap` | POST | Find overlapping geometries in any Joget form |
| `/gis/geocode` | GET | Search for location by name |
| `/gis/reverseGeocode` | GET | Get place name from coordinates |
| `/gis/health` | GET | Service health check |

### 2.3 Authentication

All endpoints require valid Joget session or API key authentication:

| Method | Header/Parameter |
|--------|-----------------|
| **Session** | Valid Joget session cookie |
| **API Key** | `api_id` + `api_key` parameters or `Authorization` header |

### 2.4 Common HTTP Status Codes

| Status | Meaning | When Used |
|--------|---------|-----------|
| **200** | Success | Request processed successfully |
| **400** | Bad Request | Invalid input parameters |
| **401** | Unauthorized | Missing or invalid authentication |
| **404** | Not Found | Resource not found |
| **422** | Unprocessable Entity | Valid JSON but semantic errors |
| **500** | Internal Server Error | Unexpected server error |
| **503** | Service Unavailable | Dependent service unavailable |

### 2.5 Common Response Envelope

All responses follow this structure:

```json
{
  "success": true|false,
  "data": { },
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable message",
    "details": { }
  },
  "meta": {
    "requestId": "uuid",
    "processingTimeMs": 45
  }
}
```

---

## 3. DATA MODELS

### 3.1 GeoJSON Geometry Input

All geometry inputs accept standard GeoJSON format:

```json
{
  "type": "Polygon",
  "coordinates": [
    [
      [longitude1, latitude1],
      [longitude2, latitude2],
      [longitude3, latitude3],
      [longitude1, latitude1]
    ]
  ]
}
```

**Or as a Feature:**

```json
{
  "type": "Feature",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[lon, lat], ...]]
  },
  "properties": { }
}
```

**Important:** GeoJSON uses `[longitude, latitude]` order (not `[lat, lon]`).

### 3.2 Supported Geometry Types

| Type | Support | Notes |
|------|---------|-------|
| **Polygon** | ✅ Full | Primary use case |
| **MultiPolygon** | ✅ Full | For non-contiguous areas |
| **Point** | ✅ Read-only | Centroid output only |
| **LineString** | ⚠️ Future | Planned for boundaries/paths |

### 3.3 Validation Rules Model

```json
{
  "minAreaHectares": 0.01,
  "maxAreaHectares": 1000,
  "minVertices": 3,
  "maxVertices": 100,
  "allowSelfIntersection": false,
  "allowHoles": true
}
```

### 3.4 Calculation Result Model

```json
{
  "areaHectares": 0.82,
  "areaSquareMeters": 8200.00,
  "perimeterMeters": 362.50,
  "centroid": {
    "type": "Point",
    "coordinates": [28.1234, -29.3145]
  },
  "boundingBox": {
    "minLongitude": 28.1200,
    "maxLongitude": 28.1300,
    "minLatitude": -29.3200,
    "maxLatitude": -29.3100
  },
  "vertexCount": 4
}
```

### 3.5 Validation Result Model

```json
{
  "valid": true|false,
  "errors": [
    {
      "code": "SELF_INTERSECTION",
      "message": "Polygon is self-intersecting",
      "severity": "ERROR",
      "location": {
        "type": "Point",
        "coordinates": [28.1240, -29.3150]
      }
    }
  ],
  "warnings": [
    {
      "code": "AREA_BELOW_MINIMUM",
      "message": "Area (0.005 ha) is below configured minimum (0.01 ha)",
      "severity": "WARNING"
    }
  ],
  "metrics": {
    "areaHectares": 0.005,
    "vertexCount": 4
  }
}
```

### 3.6 Overlap Result Model (Generic)

```json
{
  "hasOverlaps": true,
  "overlaps": [
    {
      "recordId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "overlapAreaHectares": 0.15,
      "overlapPercentOfInput": 18.3,
      "overlapPercentOfExisting": 12.5,
      "overlapGeometry": {
        "type": "Polygon",
        "coordinates": [[[...]]]
      },
      "recordData": {
        "field1": "value1",
        "field2": "value2"
      }
    }
  ],
  "totalOverlapAreaHectares": 0.15,
  "checkedRecordCount": 47
}
```

**Note:** The `recordData` object contains fields specified in the request's `returnFields` parameter, allowing the caller to get any business-specific data needed for display.

### 3.7 Error Codes Enumeration

| Code | Severity | Description |
|------|----------|-------------|
| **INVALID_GEOJSON** | ERROR | GeoJSON parsing failed |
| **INVALID_GEOMETRY_TYPE** | ERROR | Expected Polygon, got other type |
| **EMPTY_GEOMETRY** | ERROR | No coordinates provided |
| **TOO_FEW_VERTICES** | ERROR | Less than minimum required vertices |
| **TOO_MANY_VERTICES** | ERROR | Exceeds maximum allowed vertices |
| **SELF_INTERSECTION** | ERROR | Polygon edges cross each other |
| **INVALID_RING** | ERROR | Polygon ring not properly closed |
| **AREA_ZERO** | ERROR | Calculated area is zero |
| **AREA_NEGATIVE** | ERROR | Calculated area is negative (ring orientation) |
| **AREA_EXCEEDS_MAXIMUM** | ERROR | Area larger than configured maximum |
| **AREA_BELOW_MINIMUM** | WARNING | Area smaller than configured minimum |
| **VERTICES_NEAR_LIMIT** | WARNING | Approaching maximum vertex limit |
| **DUPLICATE_VERTICES** | WARNING | Consecutive identical vertices found |
| **SPIKE_DETECTED** | WARNING | Very acute angle detected (potential error) |

---

## 4. ENDPOINT SPECIFICATIONS

### 4.1 Calculate Geometry Metrics

Calculates area, perimeter, centroid, and bounding box for a given geometry.

#### Request

```
POST /jw/api/{appId}/gis/calculate
Content-Type: application/json
```

**Request Body:**

```json
{
  "geometry": {
    "type": "Polygon",
    "coordinates": [
      [
        [28.1234, -29.3145],
        [28.1256, -29.3145],
        [28.1256, -29.3167],
        [28.1234, -29.3167],
        [28.1234, -29.3145]
      ]
    ]
  },
  "options": {
    "includeAreaInSquareMeters": true,
    "includeBoundingBox": true,
    "decimalPrecision": {
      "area": 4,
      "perimeter": 2,
      "coordinates": 7
    }
  }
}
```

**Request Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `geometry` | GeoJSON | Yes | - | Polygon geometry to calculate |
| `options.includeAreaInSquareMeters` | Boolean | No | false | Include area in m² |
| `options.includeBoundingBox` | Boolean | No | true | Include bounding box |
| `options.decimalPrecision.area` | Integer | No | 4 | Decimal places for area |
| `options.decimalPrecision.perimeter` | Integer | No | 2 | Decimal places for perimeter |
| `options.decimalPrecision.coordinates` | Integer | No | 7 | Decimal places for coordinates |

#### Response

**Success (200):**

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
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "processingTimeMs": 12
  }
}
```

**Error (400) - Invalid GeoJSON:**

```json
{
  "success": false,
  "error": {
    "code": "INVALID_GEOJSON",
    "message": "Failed to parse GeoJSON geometry",
    "details": {
      "parseError": "Unexpected token at position 45",
      "hint": "Ensure coordinates array is properly formatted as [longitude, latitude] pairs"
    }
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440001",
    "processingTimeMs": 3
  }
}
```

---

### 4.2 Validate Geometry

Validates geometry against configurable rules and returns detailed results.

#### Request

```
POST /jw/api/{appId}/gis/validate
Content-Type: application/json
```

**Request Body:**

```json
{
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[28.1234, -29.3145], ...]]
  },
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

**Request Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `geometry` | GeoJSON | Yes | - | Polygon geometry to validate |
| `rules.minAreaHectares` | Decimal | No | 0.0 | Minimum area (warning if below) |
| `rules.maxAreaHectares` | Decimal | No | null | Maximum area (error if exceeded) |
| `rules.minVertices` | Integer | No | 3 | Minimum vertices (error) |
| `rules.maxVertices` | Integer | No | 1000 | Maximum vertices (error) |
| `rules.allowSelfIntersection` | Boolean | No | false | Allow self-intersecting polygons |
| `rules.allowHoles` | Boolean | No | true | Allow polygons with holes |
| `options.includeMetrics` | Boolean | No | true | Include calculated metrics |
| `options.detectSpikes` | Boolean | No | true | Detect very acute angles |
| `options.spikeAngleThreshold` | Decimal | No | 10 | Angle (degrees) below which to warn |

#### Response

**Success - Valid (200):**

```json
{
  "success": true,
  "data": {
    "valid": true,
    "errors": [],
    "warnings": [],
    "metrics": {
      "areaHectares": 0.8234,
      "perimeterMeters": 362.45,
      "vertexCount": 4
    }
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440010",
    "processingTimeMs": 18
  }
}
```

**Success - Invalid with Errors (200):**

```json
{
  "success": true,
  "data": {
    "valid": false,
    "errors": [
      {
        "code": "SELF_INTERSECTION",
        "message": "Polygon is self-intersecting",
        "severity": "ERROR",
        "location": {
          "type": "Point",
          "coordinates": [28.1240, -29.3150]
        },
        "context": {
          "intersectionType": "RING_SELF_INTERSECTION",
          "edgeIndices": [1, 3]
        }
      }
    ],
    "warnings": [],
    "metrics": {
      "vertexCount": 5
    }
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440012",
    "processingTimeMs": 22
  }
}
```

#### Validation Rules Detail

| Rule | Type | Condition | Result |
|------|------|-----------|--------|
| **Topology Valid** | ERROR | JTS IsValidOp fails | INVALID_RING, SELF_INTERSECTION |
| **Self-Intersection** | ERROR | Edges cross (if not allowed) | SELF_INTERSECTION with location |
| **Minimum Vertices** | ERROR | vertexCount < minVertices | TOO_FEW_VERTICES |
| **Maximum Vertices** | ERROR | vertexCount > maxVertices | TOO_MANY_VERTICES |
| **Maximum Area** | ERROR | area > maxAreaHectares | AREA_EXCEEDS_MAXIMUM |
| **Zero/Negative Area** | ERROR | area <= 0 | AREA_ZERO or AREA_NEGATIVE |
| **Holes Not Allowed** | ERROR | Has holes when !allowHoles | HOLES_NOT_ALLOWED |
| **Minimum Area** | WARNING | area < minAreaHectares | AREA_BELOW_MINIMUM |
| **Vertices Near Limit** | WARNING | vertexCount > maxVertices * 0.9 | VERTICES_NEAR_LIMIT |
| **Duplicate Vertices** | WARNING | Consecutive identical coordinates | DUPLICATE_VERTICES |
| **Spike Detection** | WARNING | Internal angle < threshold | SPIKE_DETECTED |

---

### 4.3 Simplify Geometry

Reduces vertex count while maintaining shape accuracy using Douglas-Peucker algorithm.

#### Request

```
POST /jw/api/{appId}/gis/simplify
Content-Type: application/json
```

**Request Body:**

```json
{
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[28.1234, -29.3145], ...]]
  },
  "options": {
    "toleranceMeters": 5,
    "preserveTopology": true,
    "targetVertexCount": null,
    "maxAreaChangePercent": 1.0
  }
}
```

**Request Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `geometry` | GeoJSON | Yes | - | Polygon geometry to simplify |
| `options.toleranceMeters` | Decimal | No | 1.0 | Simplification tolerance in meters |
| `options.preserveTopology` | Boolean | No | true | Prevent creating invalid geometry |
| `options.targetVertexCount` | Integer | No | null | Try to reduce to this vertex count |
| `options.maxAreaChangePercent` | Decimal | No | 1.0 | Maximum allowed area change |

#### Response

**Success (200):**

```json
{
  "success": true,
  "data": {
    "simplifiedGeometry": {
      "type": "Polygon",
      "coordinates": [[[28.1234, -29.3145], ...]]
    },
    "originalVertexCount": 47,
    "simplifiedVertexCount": 12,
    "verticesRemoved": 35,
    "areaChange": {
      "originalHectares": 0.8234,
      "simplifiedHectares": 0.8215,
      "changePercent": -0.23
    }
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440020",
    "processingTimeMs": 8
  }
}
```

---

### 4.4 Check Overlap (Generic)

Checks if the given geometry overlaps with existing geometries stored in any Joget form. This is a **generic spatial query** — the caller specifies which form and field to check against.

#### Request

```
POST /jw/api/{appId}/gis/checkOverlap
Content-Type: application/json
```

**Request Body:**

```json
{
  "geometry": {
    "type": "Polygon",
    "coordinates": [[[28.1234, -29.3145], ...]]
  },
  "target": {
    "formId": "parcel",
    "geometryFieldId": "c_geometry",
    "excludeRecordId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "filterCondition": "c_status = ?",
    "filterParams": ["ACTIVE"]
  },
  "options": {
    "includeOverlapGeometry": true,
    "minOverlapPercent": 1.0,
    "maxResults": 10,
    "returnFields": ["c_name", "c_code", "c_owner_name"]
  }
}
```

**Request Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `geometry` | GeoJSON | Yes | - | Polygon geometry to check |
| `target.formId` | String | Yes | - | Joget form ID containing geometries |
| `target.geometryFieldId` | String | Yes | - | Form field ID containing GeoJSON |
| `target.excludeRecordId` | String | No | null | Exclude this record (for editing existing) |
| `target.filterCondition` | String | No | null | SQL WHERE condition for filtering records |
| `target.filterParams` | Array | No | [] | Parameters for filter condition |
| `options.includeOverlapGeometry` | Boolean | No | false | Return overlap polygon geometry |
| `options.minOverlapPercent` | Decimal | No | 1.0 | Minimum overlap % to report |
| `options.maxResults` | Integer | No | 10 | Maximum overlaps to return |
| `options.returnFields` | Array | No | [] | Form field IDs to include in response |

#### Implementation Pattern

Uses Joget's FormDataDao for all data access:

```java
// Get services
FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext()
    .getBean("formDataDao");
AppDefinition appDef = AppUtil.getCurrentAppDefinition();

// Build filter condition
String condition = target.getFilterCondition();
Object[] params = target.getFilterParams();

// Query records with geometry
FormRowSet rowSet = formDataDao.find(
    formId, tableName, condition, params, null, null, null, null
);

// Check overlaps using JTS
GeoJSONReader reader = new GeoJSONReader();
Geometry inputGeom = reader.read(inputGeoJson);

List<OverlapResult> overlaps = new ArrayList<>();
for (FormRow row : rowSet) {
    String existingGeoJson = row.getProperty(geometryFieldId);
    if (existingGeoJson != null && !existingGeoJson.isEmpty()) {
        Geometry existingGeom = reader.read(existingGeoJson);
        if (inputGeom.intersects(existingGeom)) {
            Geometry overlapGeom = inputGeom.intersection(existingGeom);
            double overlapArea = calculateAreaHectares(overlapGeom);
            
            // Build result with requested return fields
            Map<String, String> recordData = new HashMap<>();
            for (String field : returnFields) {
                recordData.put(field, row.getProperty(field));
            }
            
            overlaps.add(new OverlapResult(row.getId(), overlapArea, recordData));
        }
    }
}
```

#### Response

**Success - No Overlaps (200):**

```json
{
  "success": true,
  "data": {
    "hasOverlaps": false,
    "overlaps": [],
    "totalOverlapAreaHectares": 0,
    "checkedRecordCount": 47
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440030",
    "processingTimeMs": 125
  }
}
```

**Success - Overlaps Found (200):**

```json
{
  "success": true,
  "data": {
    "hasOverlaps": true,
    "overlaps": [
      {
        "recordId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "overlapAreaHectares": 0.15,
        "overlapPercentOfInput": 18.3,
        "overlapPercentOfExisting": 12.5,
        "overlapGeometry": {
          "type": "Polygon",
          "coordinates": [[[28.1240, -29.3150], ...]]
        },
        "recordData": {
          "c_name": "Northern Field",
          "c_code": "BER-2024-00123",
          "c_owner_name": "John Mokoena"
        }
      }
    ],
    "totalOverlapAreaHectares": 0.15,
    "checkedRecordCount": 47
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440031",
    "processingTimeMs": 180
  }
}
```

#### Usage Examples

**Example 1: Land Parcel Overlap Check**
```json
{
  "geometry": { ... },
  "target": {
    "formId": "parcel",
    "geometryFieldId": "c_geometry",
    "filterCondition": "c_status = ?",
    "filterParams": ["REGISTERED"]
  },
  "options": {
    "returnFields": ["c_parcel_code", "c_farmer_name", "c_parcel_name"]
  }
}
```

**Example 2: Protected Area Check**
```json
{
  "geometry": { ... },
  "target": {
    "formId": "protected_area",
    "geometryFieldId": "c_boundary",
    "filterCondition": "c_protection_level IN (?, ?)",
    "filterParams": ["STRICT", "MODERATE"]
  },
  "options": {
    "returnFields": ["c_area_name", "c_protection_level", "c_restrictions"]
  }
}
```

**Example 3: Administrative Boundary Check**
```json
{
  "geometry": { ... },
  "target": {
    "formId": "admin_boundary",
    "geometryFieldId": "c_polygon"
  },
  "options": {
    "returnFields": ["c_district_name", "c_village_name", "c_chief_name"]
  }
}
```

---

### 4.5 Geocode Location

Searches for a location by name and returns coordinates.

#### Request

```
GET /jw/api/{appId}/gis/geocode?query={query}&limit={limit}
```

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | String | Yes | - | Search term (min 3 characters) |
| `limit` | Integer | No | 5 | Maximum results to return |
| `countryCode` | String | No | null | ISO country code to bias results |
| `boundingBox` | String | No | null | Limit search area (minLon,minLat,maxLon,maxLat) |

#### Response

**Success (200):**

```json
{
  "success": true,
  "data": {
    "results": [
      {
        "displayName": "Teyateyaneng, Berea District, Lesotho",
        "location": {
          "type": "Point",
          "coordinates": [27.7386, -29.1475]
        },
        "type": "city",
        "boundingBox": {
          "minLongitude": 27.7100,
          "maxLongitude": 27.7700,
          "minLatitude": -29.1700,
          "maxLatitude": -29.1200
        }
      }
    ],
    "query": "Teyateyaneng",
    "totalResults": 1
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440040",
    "processingTimeMs": 340
  }
}
```

#### Implementation Note

Uses Nominatim (OpenStreetMap) with caching and rate limiting:
- Cache results for 24 hours
- Respect Nominatim's 1 request/second policy
- Configurable fallback providers

---

### 4.6 Reverse Geocode

Gets place name from coordinates.

#### Request

```
GET /jw/api/{appId}/gis/reverseGeocode?lon={longitude}&lat={latitude}
```

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `lon` | Decimal | Yes | - | Longitude |
| `lat` | Decimal | Yes | - | Latitude |
| `zoom` | Integer | No | 14 | Detail level (higher = more specific) |

#### Response

**Success (200):**

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
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440050",
    "processingTimeMs": 280
  }
}
```

---

### 4.7 Health Check

Returns service status and capabilities.

#### Request

```
GET /jw/api/{appId}/gis/health
```

#### Response

**Success (200):**

```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "version": "1.0.0",
    "capabilities": {
      "calculate": true,
      "validate": true,
      "simplify": true,
      "checkOverlap": true,
      "geocode": true,
      "reverseGeocode": true
    },
    "geometryEngine": "JTS 1.19.0",
    "geocodingProvider": "Nominatim",
    "supportedGeometryTypes": ["Polygon", "MultiPolygon"]
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440060",
    "processingTimeMs": 5
  }
}
```

---

## 5. IMPLEMENTATION ARCHITECTURE

### 5.1 Plugin Structure

```
joget-gis-server/
├── pom.xml
├── src/main/java/global/govstack/gis/
│   ├── Activator.java
│   ├── lib/
│   │   └── GisApiProvider.java           ← Main API Plugin class
│   ├── service/
│   │   ├── GeometryService.java          ← JTS calculations (area, perimeter, centroid)
│   │   ├── ValidationService.java        ← Geometry validation rules
│   │   ├── SimplificationService.java    ← Douglas-Peucker simplification
│   │   ├── OverlapService.java           ← Spatial overlap detection
│   │   └── GeocodingService.java         ← Nominatim integration
│   ├── model/
│   │   ├── CalculationResult.java
│   │   ├── ValidationResult.java
│   │   ├── ValidationError.java
│   │   ├── SimplificationResult.java
│   │   ├── OverlapResult.java
│   │   └── GeocodingResult.java
│   └── util/
│       ├── GeoJsonParser.java            ← GeoJSON ↔ JTS conversion
│       └── AreaCalculator.java           ← Geodesic area calculation
├── src/main/resources/
│   └── properties/
│       └── GisApiProvider.json
└── src/test/java/
    └── global/govstack/gis/
        ├── service/
        │   ├── GeometryServiceTest.java
        │   ├── ValidationServiceTest.java
        │   └── OverlapServiceTest.java
        └── util/
            └── GeoJsonParserTest.java
```

### 5.2 API Plugin Class Pattern

Following the established `joget-rules-api` pattern:

```java
package global.govstack.gis.lib;

import org.joget.api.annotations.Operation;
import org.joget.api.annotations.Param;
import org.joget.api.annotations.Response;
import org.joget.api.annotations.Responses;
import org.joget.api.model.ApiPluginAbstract;
import org.joget.api.model.ApiResponse;

public class GisApiProvider extends ApiPluginAbstract {

    @Override
    public String getName() {
        return "GIS Geometry Services";
    }

    @Override
    public String getTag() {
        return "gis";
    }

    @Override
    public String getDescription() {
        return "Generic GIS services for geometry calculations, validation, and spatial queries";
    }

    @Operation(
        path = "/gis/calculate",
        type = Operation.MethodType.POST,
        summary = "Calculate geometry metrics",
        description = "Calculate area, perimeter, centroid, and bounding box for a GeoJSON polygon"
    )
    @Responses({
        @Response(responseCode = 200, description = "Calculation successful"),
        @Response(responseCode = 400, description = "Invalid GeoJSON"),
        @Response(responseCode = 500, description = "Server error")
    })
    public ApiResponse calculate(
        @Param(value = "body", required = true) String requestBody
    ) {
        // Delegate to GeometryService
    }

    @Operation(
        path = "/gis/validate",
        type = Operation.MethodType.POST,
        summary = "Validate geometry",
        description = "Validate geometry against configurable rules"
    )
    public ApiResponse validate(@Param(value = "body", required = true) String requestBody) {
        // Delegate to ValidationService
    }

    @Operation(
        path = "/gis/simplify",
        type = Operation.MethodType.POST,
        summary = "Simplify geometry",
        description = "Reduce vertex count using Douglas-Peucker algorithm"
    )
    public ApiResponse simplify(@Param(value = "body", required = true) String requestBody) {
        // Delegate to SimplificationService
    }

    @Operation(
        path = "/gis/checkOverlap",
        type = Operation.MethodType.POST,
        summary = "Check geometry overlap",
        description = "Check if geometry overlaps with existing records in any Joget form"
    )
    public ApiResponse checkOverlap(@Param(value = "body", required = true) String requestBody) {
        // Delegate to OverlapService
    }

    @Operation(
        path = "/gis/geocode",
        type = Operation.MethodType.GET,
        summary = "Geocode location",
        description = "Search for a location by name"
    )
    public ApiResponse geocode(
        @Param(value = "query", required = true) String query,
        @Param(value = "limit") Integer limit,
        @Param(value = "countryCode") String countryCode
    ) {
        // Delegate to GeocodingService
    }

    @Operation(
        path = "/gis/reverseGeocode",
        type = Operation.MethodType.GET,
        summary = "Reverse geocode",
        description = "Get place name from coordinates"
    )
    public ApiResponse reverseGeocode(
        @Param(value = "lon", required = true) Double longitude,
        @Param(value = "lat", required = true) Double latitude,
        @Param(value = "zoom") Integer zoom
    ) {
        // Delegate to GeocodingService
    }

    @Operation(
        path = "/gis/health",
        type = Operation.MethodType.GET,
        summary = "Health check",
        description = "Returns service status and capabilities"
    )
    public ApiResponse health() {
        // Return health status
    }
}
```

### 5.3 Service Layer Pattern

```java
package global.govstack.gis.service;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.geojson.GeoJSONReader;
import org.locationtech.jts.io.geojson.GeoJSONWriter;

@Service
public class GeometryService {
    
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    
    public CalculationResult calculate(Geometry geometry, CalculationOptions options) {
        double areaHectares = calculateAreaHectares(geometry);
        double perimeterMeters = calculatePerimeterMeters(geometry);
        Point centroid = geometry.getCentroid();
        Envelope boundingBox = geometry.getEnvelopeInternal();
        int vertexCount = geometry.getNumPoints() - 1; // Exclude closing point
        
        return CalculationResult.builder()
            .areaHectares(round(areaHectares, options.getAreaPrecision()))
            .areaSquareMeters(options.isIncludeSquareMeters() 
                ? round(areaHectares * 10000, 2) : null)
            .perimeterMeters(round(perimeterMeters, options.getPerimeterPrecision()))
            .centroid(centroid)
            .boundingBox(options.isIncludeBoundingBox() ? boundingBox : null)
            .vertexCount(vertexCount)
            .build();
    }
    
    private double calculateAreaHectares(Geometry geometry) {
        // Use spherical excess formula for geodesic area
        // Returns area in hectares
    }
    
    private double calculatePerimeterMeters(Geometry geometry) {
        // Use Haversine formula for geodesic distance
        // Returns perimeter in meters
    }
}
```

---

## 6. ERROR HANDLING

### 6.1 Error Response Structure

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable error message",
    "details": {
      "field": "geometry",
      "reason": "Specific technical detail",
      "suggestion": "How to fix the issue"
    }
  },
  "meta": {
    "requestId": "uuid",
    "processingTimeMs": 5
  }
}
```

### 6.2 Error Codes Reference

| Code | HTTP Status | Description | Recovery Action |
|------|-------------|-------------|-----------------|
| **INVALID_GEOJSON** | 400 | Cannot parse GeoJSON | Fix JSON syntax |
| **INVALID_GEOMETRY_TYPE** | 422 | Unsupported geometry type | Use Polygon or MultiPolygon |
| **EMPTY_GEOMETRY** | 422 | No coordinates | Provide coordinates |
| **INVALID_COORDINATES** | 422 | Coordinates out of range | Ensure lon [-180,180], lat [-90,90] |
| **MISSING_PARAMETER** | 400 | Required parameter missing | Include required parameter |
| **INVALID_FORM_ID** | 400 | Form not found | Check form ID |
| **INVALID_FIELD_ID** | 400 | Field not found in form | Check field ID |
| **GEOCODING_UNAVAILABLE** | 503 | Geocoding service down | Retry later |
| **INTERNAL_ERROR** | 500 | Unexpected error | Contact support |

---

## 7. SECURITY CONSIDERATIONS

### 7.1 Authentication & Authorization

| Requirement | Implementation |
|-------------|----------------|
| **API Authentication** | Joget API Builder built-in authentication |
| **Session Authentication** | Valid Joget session cookie |
| **App Permission** | User must have access to the app |
| **Form Permission** | For overlap checks, user must have read access to target form |

### 7.2 Input Validation

| Input | Validation |
|-------|------------|
| **GeoJSON** | Parse with strict mode, reject invalid JSON |
| **Coordinates** | Must be valid WGS84 range (-180 to 180, -90 to 90) |
| **Vertex Count** | Maximum 10,000 vertices per request |
| **String Parameters** | Sanitize, max length limits |
| **Filter Conditions** | Parameterized queries only (no SQL injection) |

### 7.3 Rate Limiting

| Endpoint | Limit | Window |
|----------|-------|--------|
| **calculate** | 100 requests | per minute |
| **validate** | 100 requests | per minute |
| **simplify** | 50 requests | per minute |
| **checkOverlap** | 20 requests | per minute |
| **geocode** | 30 requests | per minute |

---

## 8. PERFORMANCE REQUIREMENTS

### 8.1 Response Time Targets

| Endpoint | Target (p95) | Maximum |
|----------|--------------|---------|
| **calculate** | 50ms | 200ms |
| **validate** | 100ms | 500ms |
| **simplify** | 100ms | 500ms |
| **checkOverlap** | 500ms | 2000ms |
| **geocode** | 500ms | 2000ms |
| **health** | 10ms | 100ms |

### 8.2 Payload Size Limits

| Direction | Limit |
|-----------|-------|
| **Request Body** | 5 MB maximum |
| **Response Body** | 10 MB maximum |
| **Geometry Vertices** | 10,000 per geometry |

---

## 9. APPENDICES

### 9.1 Maven Dependencies

```xml
<!-- JTS Topology Suite -->
<dependency>
    <groupId>org.locationtech.jts</groupId>
    <artifactId>jts-core</artifactId>
    <version>1.19.0</version>
</dependency>

<!-- JTS IO for GeoJSON -->
<dependency>
    <groupId>org.locationtech.jts.io</groupId>
    <artifactId>jts-io-common</artifactId>
    <version>1.19.0</version>
</dependency>
```

### 9.2 GeoJSON Coordinate Order

**Important:** GeoJSON uses `[longitude, latitude]` order.

```json
{
  "type": "Point",
  "coordinates": [28.1234, -29.3145]
}
```

This represents:
- Longitude: 28.1234° E
- Latitude: 29.3145° S

### 9.3 Area Calculation Method

Uses the **spherical excess formula** for geodesic area calculation:

1. Coordinates converted to radians
2. Spherical polygon area via spherical excess
3. Earth radius: 6,371,000 meters
4. Result converted to hectares (÷ 10,000)

Accuracy: ±0.1% for polygons under 100 hectares at mid-latitudes.

---

**End of Specification**
