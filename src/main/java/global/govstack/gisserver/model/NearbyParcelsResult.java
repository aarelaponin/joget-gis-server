package global.govstack.gisserver.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of nearby parcels query.
 *
 * Per spec section 2.1.2, this wrapper contains:
 * - parcels: list of NearbyParcel objects
 * - totalCount: total matching parcels (may exceed returned count)
 * - truncated: true if more results exist than maxResults
 * - bounds: the query bounding box
 */
public class NearbyParcelsResult {

    private List<NearbyParcel> parcels;
    private int totalCount;
    private boolean truncated;
    private double minLongitude;
    private double minLatitude;
    private double maxLongitude;
    private double maxLatitude;

    public NearbyParcelsResult() {
        this.parcels = new ArrayList<>();
        this.totalCount = 0;
        this.truncated = false;
    }

    public List<NearbyParcel> getParcels() {
        return parcels;
    }

    public void setParcels(List<NearbyParcel> parcels) {
        this.parcels = parcels;
    }

    public void addParcel(NearbyParcel parcel) {
        this.parcels.add(parcel);
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public double getMinLongitude() {
        return minLongitude;
    }

    public void setMinLongitude(double minLongitude) {
        this.minLongitude = minLongitude;
    }

    public double getMinLatitude() {
        return minLatitude;
    }

    public void setMinLatitude(double minLatitude) {
        this.minLatitude = minLatitude;
    }

    public double getMaxLongitude() {
        return maxLongitude;
    }

    public void setMaxLongitude(double maxLongitude) {
        this.maxLongitude = maxLongitude;
    }

    public double getMaxLatitude() {
        return maxLatitude;
    }

    public void setMaxLatitude(double maxLatitude) {
        this.maxLatitude = maxLatitude;
    }

    /**
     * Set all bounds at once.
     */
    public void setBounds(double minLng, double minLat, double maxLng, double maxLat) {
        this.minLongitude = minLng;
        this.minLatitude = minLat;
        this.maxLongitude = maxLng;
        this.maxLatitude = maxLat;
    }
}
