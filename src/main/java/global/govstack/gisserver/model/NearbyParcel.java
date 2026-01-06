package global.govstack.gisserver.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a parcel returned for read-only display.
 *
 * This model is used by the NearbyParcels endpoint to return
 * existing parcels within a bounding box for visual context.
 *
 * Per spec section 2.1.2, includes:
 * - recordId: unique identifier
 * - geometry: GeoJSON Polygon/MultiPolygon
 * - centroid: calculated center point
 * - areaHectares: calculated area
 * - recordData: dynamic fields from form
 */
public class NearbyParcel {

    private String recordId;
    private String geometry;           // GeoJSON Polygon/MultiPolygon
    private double centroidLongitude;
    private double centroidLatitude;
    private double areaHectares;
    private Map<String, String> recordData;

    public NearbyParcel() {
        this.recordData = new HashMap<>();
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getGeometry() {
        return geometry;
    }

    public void setGeometry(String geometry) {
        this.geometry = geometry;
    }

    public double getCentroidLongitude() {
        return centroidLongitude;
    }

    public void setCentroidLongitude(double centroidLongitude) {
        this.centroidLongitude = centroidLongitude;
    }

    public double getCentroidLatitude() {
        return centroidLatitude;
    }

    public void setCentroidLatitude(double centroidLatitude) {
        this.centroidLatitude = centroidLatitude;
    }

    /**
     * Set centroid from longitude and latitude.
     */
    public void setCentroid(double longitude, double latitude) {
        this.centroidLongitude = longitude;
        this.centroidLatitude = latitude;
    }

    public double getAreaHectares() {
        return areaHectares;
    }

    public void setAreaHectares(double areaHectares) {
        this.areaHectares = areaHectares;
    }

    public Map<String, String> getRecordData() {
        return recordData;
    }

    public void setRecordData(Map<String, String> recordData) {
        this.recordData = recordData;
    }

    /**
     * Add a field to recordData.
     */
    public void addRecordField(String fieldId, String value) {
        if (value != null) {
            this.recordData.put(fieldId, value);
        }
    }
}
