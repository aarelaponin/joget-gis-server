# GIS Plugin Enhancement: Nearby Parcels Display

## Document Information

| Attribute | Value |
|-----------|-------|
| Document Type | Technical Specification |
| Version | 1.0 |
| Status | Draft |
| Date | 2026-01-06 |
| Related Plugins | joget-gis-ui, joget-gis-server |
| Compliance | EU LPIS, WFP SCOPE principles |

---

## 1. EXECUTIVE SUMMARY

### 1.1 Purpose

This specification defines the changes required to the GIS polygon capture plugins to display registered land parcels as read-only context during new parcel registration or editing. This feature is essential for:

1. **Fraud Prevention**: Officers can visually verify they're not registering land already claimed
2. **Double-Claiming Prevention**: Aligned with LPIS best practices that require visibility of existing parcels
3. **User Context**: Officers understand the spatial context when digitizing boundaries

### 1.2 Key Requirement

**CRITICAL**: Other registered lands MUST be read-only and cannot be modified during the session of adding/editing a new land parcel. The feature provides **visual context only** - no editing capabilities for existing parcels.

### 1.3 Scope

| In Scope | Out of Scope |
|----------|--------------|
| Display existing parcels within map viewport | Editing other parcels |
| Read-only polygon visualization | Multi-parcel editing workflows |
| Popup information on hover/click | Bulk parcel operations |
| Configurable data source and styling | Integration with external cadastral systems |
| Filter by administrative area | Real-time collaboration features |

---

## 2. SERVER PLUGIN CHANGES (joget-gis-server)

### 2.1 New Endpoint: GET /gis/nearbyParcels

Retrieves parcels within a specified bounding box for display purposes. This is separate from `/gis/checkOverlap` which performs intersection analysis.

#### 2.1.1 Request

```
GET /jw/api/gis/gis/nearbyParcels
```

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `formId` | String | Yes | - | Form ID containing parcel data |
| `geometryFieldId` | String | Yes | - | Field ID containing GeoJSON geometry |
| `bounds` | String | Yes | - | Bounding box: `minLng,minLat,maxLng,maxLat` |
| `excludeRecordId` | String | No | - | Record ID to exclude (current parcel being edited) |
| `filterCondition` | String | No | - | SQL WHERE condition (URL-encoded) |
| `returnFields` | String | No | - | Comma-separated field IDs to include |
| `maxResults` | Integer | No | 100 | Maximum parcels to return |

**Example Request:**

```
GET /jw/api/gis/gis/nearbyParcels?formId=farmerLandParcel&geometryFieldId=c_geometry&bounds=28.1,−29.6,28.3,−29.4&excludeRecordId=abc123&returnFields=parcel_name,farmer_name,area_ha&maxResults=50
```

#### 2.1.2 Response

**Success (200):**

```json
{
  "success": true,
  "data": {
    "parcels": [
      {
        "recordId": "parcel_001",
        "geometry": {
          "type": "Polygon",
          "coordinates": [[[28.15, -29.55], [28.16, -29.55], [28.16, -29.54], [28.15, -29.54], [28.15, -29.55]]]
        },
        "centroid": {
          "type": "Point",
          "coordinates": [28.155, -29.545]
        },
        "areaHectares": 1.25,
        "recordData": {
          "parcel_name": "Northern Field",
          "farmer_name": "Mamello Moshoeshoe",
          "area_ha": "1.25"
        }
      },
      {
        "recordId": "parcel_002",
        "geometry": {
          "type": "Polygon",
          "coordinates": [[[28.17, -29.56], [28.18, -29.56], [28.18, -29.55], [28.17, -29.55], [28.17, -29.56]]]
        },
        "centroid": {
          "type": "Point",
          "coordinates": [28.175, -29.555]
        },
        "areaHectares": 0.82,
        "recordData": {
          "parcel_name": "River Plot",
          "farmer_name": "Thabo Mokoena",
          "area_ha": "0.82"
        }
      }
    ],
    "totalCount": 2,
    "bounds": {
      "minLongitude": 28.1,
      "minLatitude": -29.6,
      "maxLongitude": 28.3,
      "maxLatitude": -29.4
    },
    "truncated": false
  },
  "meta": {
    "requestId": "550e8400-e29b-41d4-a716-446655440030",
    "processingTimeMs": 45
  }
}
```

**Error Responses:**

| Code | Error Code | Description |
|------|------------|-------------|
| 400 | `INVALID_BOUNDS` | Invalid or missing bounding box format |
| 400 | `MISSING_FORM_ID` | Form ID is required |
| 400 | `MISSING_GEOMETRY_FIELD` | Geometry field ID is required |
| 404 | `FORM_NOT_FOUND` | Specified form does not exist |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many requests |
| 500 | `SERVER_ERROR` | Internal server error |

#### 2.1.3 Implementation Details

**File:** `GisApiProvider.java`

Add new method:

```java
@Operation(
    path = "/gis/nearbyParcels",
    type = Operation.MethodType.GET,
    summary = "Get nearby parcels for display",
    description = "Retrieves parcels within a bounding box for read-only display context."
)
@Responses({
    @Response(responseCode = 200, description = "Parcels retrieved successfully"),
    @Response(responseCode = 400, description = "Invalid parameters"),
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
    // Implementation - see section 2.2
}
```

### 2.2 New Service: NearbyParcelsService

**File:** `service/NearbyParcelsService.java`

```java
package global.govstack.gisserver.service;

import global.govstack.gisserver.engine.GeometryEngine;
import global.govstack.gisserver.model.NearbyParcel;
import global.govstack.gisserver.model.NearbyParcelsResult;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import java.util.*;

/**
 * Service for retrieving nearby parcels within a bounding box.
 * 
 * This service returns parcels for READ-ONLY display purposes.
 * It does NOT support any modification operations.
 */
public class NearbyParcelsService {

    private static final String CLASS_NAME = NearbyParcelsService.class.getName();
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int ABSOLUTE_MAX_RESULTS = 500;

    private final GeometryEngine geometryEngine;

    public NearbyParcelsService() {
        this.geometryEngine = new GeometryEngine();
    }

    /**
     * Retrieve parcels within the specified bounding box.
     * 
     * @param formId Form ID containing parcel data
     * @param geometryFieldId Field containing GeoJSON geometry
     * @param minLng Minimum longitude
     * @param minLat Minimum latitude
     * @param maxLng Maximum longitude
     * @param maxLat Maximum latitude
     * @param excludeRecordId Record ID to exclude (optional)
     * @param filterCondition SQL filter condition (optional)
     * @param returnFields Fields to include in response (optional)
     * @param maxResults Maximum results to return
     * @return NearbyParcelsResult with parcels in bounds
     */
    public NearbyParcelsResult getParcelsInBounds(
            String formId,
            String geometryFieldId,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            String excludeRecordId,
            String filterCondition,
            List<String> returnFields,
            Integer maxResults) {

        NearbyParcelsResult result = new NearbyParcelsResult();
        result.setBounds(minLng, minLat, maxLng, maxLat);

        // Enforce max results limit
        int limit = (maxResults != null && maxResults > 0) 
            ? Math.min(maxResults, ABSOLUTE_MAX_RESULTS) 
            : DEFAULT_MAX_RESULTS;

        try {
            // Create bounding box for spatial filter
            Envelope queryEnvelope = new Envelope(minLng, maxLng, minLat, maxLat);

            // Get FormDataDao
            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext()
                .getBean("formDataDao");

            // Build query condition
            StringBuilder conditionBuilder = new StringBuilder();
            List<Object> paramsList = new ArrayList<>();

            if (excludeRecordId != null && !excludeRecordId.isEmpty()) {
                conditionBuilder.append("WHERE id != ?");
                paramsList.add(excludeRecordId);
            }

            if (filterCondition != null && !filterCondition.isEmpty()) {
                if (conditionBuilder.length() == 0) {
                    conditionBuilder.append("WHERE ");
                } else {
                    conditionBuilder.append(" AND ");
                }
                conditionBuilder.append(filterCondition);
            }

            String condition = conditionBuilder.toString();
            Object[] params = paramsList.toArray();

            // Query all records (spatial filtering done in Java)
            // Note: For large datasets, consider adding spatial index support
            FormRowSet rowSet = formDataDao.find(formId, formId, condition, params, null, null, null, null);

            if (rowSet == null || rowSet.isEmpty()) {
                result.setTotalCount(0);
                result.setTruncated(false);
                return result;
            }

            int totalMatches = 0;
            List<NearbyParcel> parcels = new ArrayList<>();

            for (FormRow row : rowSet) {
                String geoJson = row.getProperty(geometryFieldId);
                if (geoJson == null || geoJson.isEmpty()) {
                    continue;
                }

                try {
                    Geometry geom = geometryEngine.parseGeoJson(geoJson);
                    Envelope parcelEnvelope = geom.getEnvelopeInternal();

                    // Check if parcel intersects query bounds
                    if (queryEnvelope.intersects(parcelEnvelope)) {
                        totalMatches++;

                        // Only add to results if under limit
                        if (parcels.size() < limit) {
                            NearbyParcel parcel = new NearbyParcel();
                            parcel.setRecordId(row.getId());
                            parcel.setGeometry(geoJson);

                            // Calculate centroid
                            double[] centroid = geometryEngine.calculateCentroid(geom);
                            parcel.setCentroid(centroid[0], centroid[1]);

                            // Calculate area
                            double areaSqM = geometryEngine.calculateAreaSquareMeters(geom);
                            parcel.setAreaHectares(areaSqM / 10000.0);

                            // Add requested fields
                            if (returnFields != null) {
                                Map<String, String> recordData = new LinkedHashMap<>();
                                for (String fieldId : returnFields) {
                                    String value = row.getProperty(fieldId);
                                    if (value != null) {
                                        recordData.put(fieldId, value);
                                    }
                                }
                                parcel.setRecordData(recordData);
                            }

                            parcels.add(parcel);
                        }
                    }
                } catch (GeometryEngine.GeometryParseException e) {
                    LogUtil.warn(CLASS_NAME, "Invalid geometry in record " + row.getId());
                }
            }

            result.setParcels(parcels);
            result.setTotalCount(totalMatches);
            result.setTruncated(totalMatches > limit);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error retrieving nearby parcels");
        }

        return result;
    }
}
```

### 2.3 New Model Classes

**File:** `model/NearbyParcel.java`

```java
package global.govstack.gisserver.model;

import java.util.Map;

/**
 * Represents a parcel returned for read-only display.
 */
public class NearbyParcel {
    private String recordId;
    private String geometry;        // GeoJSON Polygon
    private double centroidLng;
    private double centroidLat;
    private double areaHectares;
    private Map<String, String> recordData;

    // Getters and setters...
}
```

**File:** `model/NearbyParcelsResult.java`

```java
package global.govstack.gisserver.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of nearby parcels query.
 */
public class NearbyParcelsResult {
    private List<NearbyParcel> parcels = new ArrayList<>();
    private int totalCount;
    private boolean truncated;
    private double minLng, minLat, maxLng, maxLat;

    // Getters and setters...
}
```

### 2.4 Rate Limiting

Add rate limit for the new endpoint in `RateLimiter.java`:

```java
RATE_LIMITS.put("nearbyParcels", 60);  // 60 requests per minute
```

---

## 3. CLIENT PLUGIN CHANGES (joget-gis-ui)

### 3.1 New Configuration Properties

**File:** `properties/GisPolygonCaptureElement.json`

Add new property section:

```json
{
    "title": "Nearby Parcels Display",
    "properties": [
        {
            "name": "showNearbyParcels",
            "label": "Show Nearby Parcels",
            "description": "Display existing registered parcels as read-only context",
            "type": "selectbox",
            "options": [
                {"value": "DISABLED", "label": "Disabled"},
                {"value": "ON_LOAD", "label": "Load on map initialization"},
                {"value": "ON_DEMAND", "label": "Load on user request (button)"}
            ],
            "value": "DISABLED"
        },
        {
            "name": "nearbyParcelsFormId",
            "label": "Parcels Form ID",
            "description": "Form containing parcel records to display",
            "type": "textfield",
            "value": ""
        },
        {
            "name": "nearbyParcelsGeometryField",
            "label": "Parcels Geometry Field ID",
            "description": "Field containing GeoJSON geometry",
            "type": "textfield",
            "value": "c_geometry"
        },
        {
            "name": "nearbyParcelsDisplayFields",
            "label": "Display Fields",
            "description": "Comma-separated field IDs to show in popup (e.g., parcel_name,farmer_name)",
            "type": "textfield",
            "value": ""
        },
        {
            "name": "nearbyParcelsFilterCondition",
            "label": "Filter Condition",
            "description": "SQL WHERE clause to filter displayed parcels (e.g., status = 'ACTIVE')",
            "type": "textfield",
            "value": ""
        },
        {
            "name": "nearbyParcelsFillColor",
            "label": "Fill Color",
            "description": "Fill color for existing parcels (CSS color)",
            "type": "textfield",
            "value": "#808080"
        },
        {
            "name": "nearbyParcelsFillOpacity",
            "label": "Fill Opacity",
            "description": "Fill opacity for existing parcels (0-1)",
            "type": "textfield",
            "value": "0.15"
        },
        {
            "name": "nearbyParcelsStrokeColor",
            "label": "Stroke Color",
            "description": "Border color for existing parcels",
            "type": "textfield",
            "value": "#666666"
        },
        {
            "name": "nearbyParcelsMaxResults",
            "label": "Maximum Parcels",
            "description": "Maximum number of nearby parcels to display",
            "type": "textfield",
            "value": "100"
        }
    ]
}
```

### 3.2 JavaScript Changes

**File:** `static/gis-capture.js`

#### 3.2.1 Add Options

In the constructor options:

```javascript
this.options = Object.assign({
    // ... existing options ...
    
    // Nearby parcels configuration
    nearbyParcels: {
        enabled: false,          // 'DISABLED', 'ON_LOAD', 'ON_DEMAND'
        formId: '',
        geometryFieldId: 'c_geometry',
        displayFields: [],
        filterCondition: '',
        maxResults: 100,
        style: {
            fillColor: '#808080',
            fillOpacity: 0.15,
            strokeColor: '#666666',
            strokeWidth: 1,
            strokeDashArray: '3, 3'
        }
    }
}, options);
```

#### 3.2.2 Add State

```javascript
this.state = {
    // ... existing state ...
    
    // Nearby parcels state
    nearbyParcelsLoaded: false,
    nearbyParcelsLoading: false,
    nearbyParcelsData: []       // Cached parcel data
};
```

#### 3.2.3 Add Map References

```javascript
// Map references
this.nearbyParcelsLayer = null;      // Layer group for nearby parcels (READ-ONLY)
```

#### 3.2.4 New Method: Initialize Nearby Parcels Layer

```javascript
/**
 * Initialize nearby parcels layer.
 * 
 * CRITICAL: This layer is READ-ONLY. All interactions are disabled
 * to prevent any modification of existing parcels.
 */
_initNearbyParcelsLayer: function() {
    // Create layer group for nearby parcels
    // Add BELOW the drawing layer so new parcel appears on top
    this.nearbyParcelsLayer = L.layerGroup();
    
    // Insert before drawing layer (so drawing layer is on top)
    this.nearbyParcelsLayer.addTo(this.map);
    this.nearbyParcelsLayer.bringToBack();
    
    // Load parcels based on configuration
    var mode = this.options.nearbyParcels.enabled;
    
    if (mode === 'ON_LOAD') {
        this._loadNearbyParcels();
    } else if (mode === 'ON_DEMAND') {
        this._addNearbyParcelsToggle();
    }
},
```

#### 3.2.5 New Method: Load Nearby Parcels

```javascript
/**
 * Load nearby parcels from server.
 * Updates when map view changes (with debouncing).
 */
_loadNearbyParcels: function() {
    var self = this;
    var config = this.options.nearbyParcels;
    
    // Skip if not enabled or already loading
    if (!config.enabled || config.enabled === 'DISABLED') {
        return;
    }
    
    if (this.state.nearbyParcelsLoading) {
        return;
    }
    
    // Get current map bounds
    var bounds = this.map.getBounds();
    var boundsStr = [
        bounds.getWest().toFixed(6),
        bounds.getSouth().toFixed(6),
        bounds.getEast().toFixed(6),
        bounds.getNorth().toFixed(6)
    ].join(',');
    
    // Build API URL
    var apiUrl = this.options.apiBase + '/nearbyParcels?' +
        'formId=' + encodeURIComponent(config.formId) +
        '&geometryFieldId=' + encodeURIComponent(config.geometryFieldId) +
        '&bounds=' + encodeURIComponent(boundsStr) +
        '&maxResults=' + (config.maxResults || 100);
    
    // Exclude current record if editing
    if (this.options.recordId) {
        apiUrl += '&excludeRecordId=' + encodeURIComponent(this.options.recordId);
    }
    
    // Add filter condition
    if (config.filterCondition) {
        apiUrl += '&filterCondition=' + encodeURIComponent(config.filterCondition);
    }
    
    // Add return fields
    if (config.displayFields && config.displayFields.length > 0) {
        apiUrl += '&returnFields=' + encodeURIComponent(config.displayFields.join(','));
    }
    
    // Build headers
    var headers = {};
    if (this.options.apiId && this.options.apiKey) {
        headers['api_id'] = this.options.apiId;
        headers['api_key'] = this.options.apiKey;
    }
    
    this.state.nearbyParcelsLoading = true;
    this._showNearbyParcelsLoading();
    
    fetch(apiUrl, {
        method: 'GET',
        headers: headers
    })
    .then(function(response) {
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        return response.json();
    })
    .then(function(response) {
        self.state.nearbyParcelsLoading = false;
        self._hideNearbyParcelsLoading();
        
        // Parse Joget API response wrapper
        var data = response;
        if (response.message && typeof response.message === 'string') {
            try {
                data = JSON.parse(response.message);
            } catch (e) {
                // Fall back to original
            }
        }
        
        var parcelsData = data.data || data;
        self.state.nearbyParcelsData = parcelsData.parcels || [];
        self.state.nearbyParcelsLoaded = true;
        
        self._displayNearbyParcels(self.state.nearbyParcelsData);
        
        // Update count badge
        self._updateNearbyParcelsCount(self.state.nearbyParcelsData.length, parcelsData.truncated);
    })
    .catch(function(error) {
        self.state.nearbyParcelsLoading = false;
        self._hideNearbyParcelsLoading();
        console.warn('[GISCapture] Failed to load nearby parcels:', error);
    });
},
```

#### 3.2.6 New Method: Display Nearby Parcels (READ-ONLY)

```javascript
/**
 * Display nearby parcels on the map.
 * 
 * CRITICAL SECURITY: All polygons are displayed as READ-ONLY.
 * - No drag handlers
 * - No edit controls
 * - No modification events
 * - Click only shows info popup (no editing)
 */
_displayNearbyParcels: function(parcels) {
    var self = this;
    var config = this.options.nearbyParcels;
    var style = config.style;
    
    // Clear previous parcels
    this.nearbyParcelsLayer.clearLayers();
    
    if (!parcels || parcels.length === 0) {
        return;
    }
    
    parcels.forEach(function(parcel) {
        try {
            var geojson = parcel.geometry;
            if (typeof geojson === 'string') {
                geojson = JSON.parse(geojson);
            }
            
            // Get coordinates
            var coordinates = geojson.coordinates;
            if (geojson.type === 'Feature') {
                coordinates = geojson.geometry.coordinates;
            }
            
            if (!coordinates || !coordinates[0]) {
                return;
            }
            
            // Convert [lng, lat] to [lat, lng] for Leaflet
            var latlngs = coordinates[0].map(function(c) {
                return [c[1], c[0]];
            });
            
            // Create polygon with READ-ONLY styling
            var polygon = L.polygon(latlngs, {
                fillColor: style.fillColor,
                fillOpacity: style.fillOpacity,
                color: style.strokeColor,
                weight: style.strokeWidth,
                dashArray: style.strokeDashArray,
                // CRITICAL: Disable all interaction that could modify
                interactive: true,      // Allow click for popup
                bubblingMouseEvents: false
            });
            
            // Build popup content (READ-ONLY info display)
            var popupContent = self._buildNearbyParcelPopup(parcel);
            polygon.bindPopup(popupContent, {
                closeButton: true,
                autoClose: true
            });
            
            // Hover effect (visual feedback only)
            polygon.on('mouseover', function() {
                this.setStyle({
                    fillOpacity: style.fillOpacity + 0.1,
                    weight: style.strokeWidth + 1
                });
            });
            
            polygon.on('mouseout', function() {
                this.setStyle({
                    fillOpacity: style.fillOpacity,
                    weight: style.strokeWidth
                });
            });
            
            // Add to layer
            self.nearbyParcelsLayer.addLayer(polygon);
            
        } catch (e) {
            console.warn('[GISCapture] Invalid nearby parcel geometry:', e);
        }
    });
    
    // Ensure drawing layer stays on top
    if (this.drawingLayer) {
        this.drawingLayer.bringToFront();
    }
},
```

#### 3.2.7 New Method: Build Popup Content

```javascript
/**
 * Build popup content for nearby parcel.
 * Shows READ-ONLY information - no edit links or buttons.
 */
_buildNearbyParcelPopup: function(parcel) {
    var html = '<div class="gis-nearby-parcel-popup">';
    html += '<div class="gis-nearby-parcel-header">';
    html += '<span class="gis-nearby-parcel-icon">📍</span>';
    html += '<span class="gis-nearby-parcel-title">Registered Parcel</span>';
    html += '</div>';
    
    // Display area
    if (parcel.areaHectares !== undefined) {
        html += '<div class="gis-nearby-parcel-info">';
        html += '<span class="gis-info-label">Area:</span> ';
        html += '<span class="gis-info-value">' + parcel.areaHectares.toFixed(2) + ' ha</span>';
        html += '</div>';
    }
    
    // Display configured fields
    if (parcel.recordData) {
        Object.keys(parcel.recordData).forEach(function(key) {
            var value = parcel.recordData[key];
            if (value) {
                // Format field name (convert snake_case to Title Case)
                var label = key.replace(/_/g, ' ').replace(/\b\w/g, function(c) {
                    return c.toUpperCase();
                });
                html += '<div class="gis-nearby-parcel-info">';
                html += '<span class="gis-info-label">' + label + ':</span> ';
                html += '<span class="gis-info-value">' + value + '</span>';
                html += '</div>';
            }
        });
    }
    
    // Footer note - no edit capability
    html += '<div class="gis-nearby-parcel-footer">';
    html += '<small>This parcel is already registered</small>';
    html += '</div>';
    
    html += '</div>';
    return html;
},
```

#### 3.2.8 New Method: Toggle Button (ON_DEMAND mode)

```javascript
/**
 * Add toggle button for nearby parcels (ON_DEMAND mode).
 */
_addNearbyParcelsToggle: function() {
    var self = this;
    
    var control = document.createElement('div');
    control.className = 'gis-nearby-parcels-control';
    
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'gis-nearby-parcels-btn';
    btn.innerHTML = '🗺️ Show Nearby';
    btn.setAttribute('aria-label', 'Show or hide nearby registered parcels');
    btn.setAttribute('aria-pressed', 'false');
    
    // Count badge (initially hidden)
    var badge = document.createElement('span');
    badge.className = 'gis-nearby-parcels-badge';
    badge.style.display = 'none';
    
    btn.onclick = function(e) {
        e.preventDefault();
        e.stopPropagation();
        
        var isActive = btn.classList.toggle('active');
        btn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
        
        if (isActive) {
            btn.innerHTML = '🗺️ Hide Nearby';
            self._loadNearbyParcels();
        } else {
            btn.innerHTML = '🗺️ Show Nearby';
            self._clearNearbyParcels();
        }
    };
    
    control.appendChild(btn);
    control.appendChild(badge);
    this.mapContainer.appendChild(control);
    
    this.nearbyParcelsToggle = btn;
    this.nearbyParcelsBadge = badge;
},
```

#### 3.2.9 New Method: Map Move Handler (Reload on Pan/Zoom)

```javascript
/**
 * Set up map move handler to reload nearby parcels on pan/zoom.
 * Uses debouncing to avoid excessive API calls.
 */
_setupNearbyParcelsReload: function() {
    var self = this;
    var reloadTimeout = null;
    
    this.map.on('moveend', function() {
        if (self.options.nearbyParcels.enabled === 'ON_LOAD' ||
            (self.options.nearbyParcels.enabled === 'ON_DEMAND' && 
             self.nearbyParcelsToggle && 
             self.nearbyParcelsToggle.classList.contains('active'))) {
            
            // Debounce - wait 500ms after last move
            clearTimeout(reloadTimeout);
            reloadTimeout = setTimeout(function() {
                self._loadNearbyParcels();
            }, 500);
        }
    });
},
```

#### 3.2.10 Helper Methods

```javascript
/**
 * Show loading indicator for nearby parcels.
 */
_showNearbyParcelsLoading: function() {
    if (this.nearbyParcelsToggle) {
        this.nearbyParcelsToggle.classList.add('loading');
    }
},

/**
 * Hide loading indicator.
 */
_hideNearbyParcelsLoading: function() {
    if (this.nearbyParcelsToggle) {
        this.nearbyParcelsToggle.classList.remove('loading');
    }
},

/**
 * Update count badge.
 */
_updateNearbyParcelsCount: function(count, truncated) {
    if (this.nearbyParcelsBadge) {
        if (count > 0) {
            this.nearbyParcelsBadge.textContent = truncated ? count + '+' : count;
            this.nearbyParcelsBadge.style.display = 'inline-block';
        } else {
            this.nearbyParcelsBadge.style.display = 'none';
        }
    }
},

/**
 * Clear nearby parcels from map.
 */
_clearNearbyParcels: function() {
    if (this.nearbyParcelsLayer) {
        this.nearbyParcelsLayer.clearLayers();
    }
    this.state.nearbyParcelsData = [];
    this._updateNearbyParcelsCount(0, false);
},
```

### 3.3 CSS Changes

**File:** `static/gis-capture.css`

Add styles for nearby parcels:

```css
/* =====================================================
   NEARBY PARCELS DISPLAY (READ-ONLY)
   ===================================================== */

/* Control button */
.gis-nearby-parcels-control {
    position: absolute;
    top: 10px;
    right: 60px;
    z-index: 1000;
}

.gis-nearby-parcels-btn {
    background: white;
    border: 2px solid rgba(0, 0, 0, 0.2);
    border-radius: 4px;
    padding: 6px 12px;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.2s;
}

.gis-nearby-parcels-btn:hover {
    background: #f4f4f4;
}

.gis-nearby-parcels-btn.active {
    background: #e3f2fd;
    border-color: #2196f3;
}

.gis-nearby-parcels-btn.loading {
    opacity: 0.7;
    pointer-events: none;
}

.gis-nearby-parcels-btn.loading::after {
    content: '...';
    animation: loading-dots 1s infinite;
}

@keyframes loading-dots {
    0%, 20% { content: '.'; }
    40% { content: '..'; }
    60%, 100% { content: '...'; }
}

/* Count badge */
.gis-nearby-parcels-badge {
    position: absolute;
    top: -5px;
    right: -5px;
    background: #2196f3;
    color: white;
    font-size: 10px;
    padding: 2px 5px;
    border-radius: 10px;
    min-width: 16px;
    text-align: center;
}

/* Popup styling */
.gis-nearby-parcel-popup {
    min-width: 180px;
    font-size: 13px;
}

.gis-nearby-parcel-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-bottom: 8px;
    border-bottom: 1px solid #e0e0e0;
    margin-bottom: 8px;
}

.gis-nearby-parcel-icon {
    font-size: 16px;
}

.gis-nearby-parcel-title {
    font-weight: 600;
    color: #333;
}

.gis-nearby-parcel-info {
    padding: 3px 0;
}

.gis-nearby-parcel-info .gis-info-label {
    color: #666;
    font-size: 12px;
}

.gis-nearby-parcel-info .gis-info-value {
    color: #333;
    font-weight: 500;
}

.gis-nearby-parcel-footer {
    margin-top: 10px;
    padding-top: 8px;
    border-top: 1px solid #e0e0e0;
    color: #888;
    font-style: italic;
}

/* Legend for nearby parcels */
.gis-legend {
    position: absolute;
    bottom: 30px;
    left: 10px;
    background: white;
    padding: 8px 12px;
    border-radius: 4px;
    box-shadow: 0 1px 4px rgba(0,0,0,0.2);
    font-size: 12px;
    z-index: 1000;
}

.gis-legend-item {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 4px 0;
}

.gis-legend-swatch {
    width: 20px;
    height: 14px;
    border-radius: 2px;
}

.gis-legend-swatch.nearby {
    background: rgba(128, 128, 128, 0.15);
    border: 1px dashed #666;
}

.gis-legend-swatch.current {
    background: rgba(51, 136, 255, 0.2);
    border: 2px solid #3388ff;
}

.gis-legend-swatch.overlap {
    background: rgba(220, 53, 69, 0.4);
    border: 2px solid #dc3545;
}
```

### 3.4 Integration Points

#### 3.4.1 Modify _initMap()

Add call to initialize nearby parcels layer:

```javascript
_initMap: function() {
    // ... existing code ...
    
    // Initialize nearby parcels layer (before drawing layer)
    if (this.options.nearbyParcels.enabled && 
        this.options.nearbyParcels.enabled !== 'DISABLED') {
        this._initNearbyParcelsLayer();
        this._setupNearbyParcelsReload();
    }
    
    // ... rest of existing code ...
},
```

#### 3.4.2 Modify _buildUI()

Add legend if nearby parcels enabled:

```javascript
_buildUI: function() {
    // ... existing code ...
    
    // Add legend if nearby parcels enabled
    if (this.options.nearbyParcels.enabled && 
        this.options.nearbyParcels.enabled !== 'DISABLED') {
        this._addLegend();
    }
},
```

#### 3.4.3 Add Legend Method

```javascript
/**
 * Add map legend showing parcel types.
 */
_addLegend: function() {
    var legend = document.createElement('div');
    legend.className = 'gis-legend';
    legend.innerHTML = 
        '<div class="gis-legend-item">' +
            '<div class="gis-legend-swatch nearby"></div>' +
            '<span>Registered parcels</span>' +
        '</div>' +
        '<div class="gis-legend-item">' +
            '<div class="gis-legend-swatch current"></div>' +
            '<span>Current parcel</span>' +
        '</div>' +
        '<div class="gis-legend-item">' +
            '<div class="gis-legend-swatch overlap"></div>' +
            '<span>Overlap area</span>' +
        '</div>';
    
    this.mapContainer.appendChild(legend);
},
```

---

## 4. SECURITY CONSIDERATIONS

### 4.1 Read-Only Enforcement

**Server-Side:**
- The `/gis/nearbyParcels` endpoint is GET-only (no modification)
- No write operations on retrieved records
- No record IDs that could be used for modification are exposed unnecessarily

**Client-Side:**
- Nearby parcels layer is non-editable by design
- No drag handlers attached to nearby parcel polygons
- No edit buttons or links in popups
- Layer is always behind the drawing layer
- Map events don't bubble to nearby parcels for modification

### 4.2 Data Access Control

- Filter condition allows restricting visible parcels by status, ownership, etc.
- `excludeRecordId` ensures the current record being edited is not shown as "existing"
- API authentication (api_id, api_key) required for requests

### 4.3 Performance Safeguards

- `maxResults` limits prevent overloading the map
- Bounding box queries limit data to visible area
- Debounced reload on map move (500ms)
- Client-side caching of loaded parcels

---

## 5. TESTING REQUIREMENTS

### 5.1 Server Tests

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| TC-S01 | Valid bounds query | Returns parcels within bounds |
| TC-S02 | Empty bounds | Returns empty array |
| TC-S03 | Invalid bounds format | Returns 400 error |
| TC-S04 | Missing formId | Returns 400 error |
| TC-S05 | Exclude record ID | Current record not in results |
| TC-S06 | Filter condition | Only matching parcels returned |
| TC-S07 | Rate limiting | Returns 429 after threshold |

### 5.2 Client Tests

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| TC-C01 | Load parcels on init | Parcels displayed on map load |
| TC-C02 | Toggle button (ON_DEMAND) | Parcels show/hide on click |
| TC-C03 | Reload on pan | New parcels loaded for new bounds |
| TC-C04 | Popup on click | Info popup shows record data |
| TC-C05 | No edit on nearby parcels | Cannot modify nearby parcels |
| TC-C06 | Layer ordering | Drawing layer always on top |
| TC-C07 | Legend display | Legend shows parcel types |

### 5.3 Integration Tests

| Test Case | Description | Expected Result |
|-----------|-------------|-----------------|
| TC-I01 | New parcel with nearby | Can draw parcel seeing others |
| TC-I02 | Edit existing parcel | Current parcel excluded from display |
| TC-I03 | Overlap detection with nearby | Both features work together |
| TC-I04 | Mobile device | Touch interactions work correctly |

---

## 6. DEPLOYMENT CHECKLIST

### 6.1 Server Plugin

- [ ] Add `NearbyParcelsService.java`
- [ ] Add `NearbyParcel.java` model
- [ ] Add `NearbyParcelsResult.java` model
- [ ] Add `getNearbyParcels()` method to `GisApiProvider.java`
- [ ] Add rate limit for new endpoint
- [ ] Update plugin version
- [ ] Build and test JAR
- [ ] Deploy to Joget instance

### 6.2 Client Plugin

- [ ] Update `GisPolygonCaptureElement.json` with new properties
- [ ] Update `gis-capture.js` with new methods
- [ ] Update `gis-capture.css` with new styles
- [ ] Update `GisPolygonCaptureElement.java` to pass new options
- [ ] Update plugin version
- [ ] Build and test JAR
- [ ] Deploy to Joget instance

### 6.3 Configuration

- [ ] Configure Nearby Parcels settings in form designer
- [ ] Set up appropriate filter conditions
- [ ] Test with production data
- [ ] Verify performance with expected parcel counts

---

## 7. APPENDIX: UI MOCKUP

```
┌─────────────────────────────────────────────────────────────────┐
│  🗺️ PARCEL CAPTURE                    [🗺️ Show Nearby] [Layers]│
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                                                         │   │
│   │     ┌─ ─ ─ ─ ─┐                                         │   │
│   │     │░░░░░░░░░│  ← Farmer A's parcel (gray, dashed)     │   │
│   │     └─ ─ ─ ─ ─┘    READ-ONLY                            │   │
│   │           ┌─────────────┐                               │   │
│   │           │ ███ 1 ███   │  ← Being registered (blue)    │   │
│   │           │ ███   ███   │    EDITABLE                   │   │
│   │           │ ███ 2 ███   │                               │   │
│   │           └─────────────┘                               │   │
│   │                    ┌─ ─ ─┐                              │   │
│   │                    │░░░░░│  ← Farmer B's parcel         │   │
│   │                    └─ ─ ─┘    READ-ONLY                 │   │
│   │                                                         │   │
│   │  ┌────────────────┐                                     │   │
│   │  │ LEGEND         │                                     │   │
│   │  │ ░░ Registered  │                                     │   │
│   │  │ ██ Current     │                                     │   │
│   │  │ ▓▓ Overlap     │                                     │   │
│   │  └────────────────┘                                     │   │
│   │                                                         │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Area: 0.82 ha  |  Perimeter: 382 m  |  Corners: 5             │
│                                                                 │
│  [↩️ Undo]  [🗑️ Clear]                          [✓ Complete]    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

*End of Specification*
