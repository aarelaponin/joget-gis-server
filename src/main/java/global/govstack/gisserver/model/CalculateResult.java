package global.govstack.gisserver.model;

/**
 * Result of geometry calculation containing area, perimeter, centroid, etc.
 */
public class CalculateResult {
    
    private double areaHectares;
    private Double areaSquareMeters;  // Optional
    private double perimeterMeters;
    private double centroidLatitude;
    private double centroidLongitude;
    private int vertexCount;
    
    // Bounding box (optional)
    private Double minLatitude;
    private Double maxLatitude;
    private Double minLongitude;
    private Double maxLongitude;
    
    public double getAreaHectares() {
        return areaHectares;
    }
    
    public void setAreaHectares(double areaHectares) {
        this.areaHectares = areaHectares;
    }
    
    public Double getAreaSquareMeters() {
        return areaSquareMeters;
    }
    
    public void setAreaSquareMeters(Double areaSquareMeters) {
        this.areaSquareMeters = areaSquareMeters;
    }
    
    public double getPerimeterMeters() {
        return perimeterMeters;
    }
    
    public void setPerimeterMeters(double perimeterMeters) {
        this.perimeterMeters = perimeterMeters;
    }
    
    public double getCentroidLatitude() {
        return centroidLatitude;
    }
    
    public void setCentroidLatitude(double centroidLatitude) {
        this.centroidLatitude = centroidLatitude;
    }
    
    public double getCentroidLongitude() {
        return centroidLongitude;
    }
    
    public void setCentroidLongitude(double centroidLongitude) {
        this.centroidLongitude = centroidLongitude;
    }
    
    public int getVertexCount() {
        return vertexCount;
    }
    
    public void setVertexCount(int vertexCount) {
        this.vertexCount = vertexCount;
    }
    
    public Double getMinLatitude() {
        return minLatitude;
    }
    
    public void setMinLatitude(Double minLatitude) {
        this.minLatitude = minLatitude;
    }
    
    public Double getMaxLatitude() {
        return maxLatitude;
    }
    
    public void setMaxLatitude(Double maxLatitude) {
        this.maxLatitude = maxLatitude;
    }
    
    public Double getMinLongitude() {
        return minLongitude;
    }
    
    public void setMinLongitude(Double minLongitude) {
        this.minLongitude = minLongitude;
    }
    
    public Double getMaxLongitude() {
        return maxLongitude;
    }
    
    public void setMaxLongitude(Double maxLongitude) {
        this.maxLongitude = maxLongitude;
    }
    
    /**
     * Set bounding box from envelope coordinates.
     */
    public void setBoundingBox(double minLon, double minLat, double maxLon, double maxLat) {
        this.minLongitude = minLon;
        this.minLatitude = minLat;
        this.maxLongitude = maxLon;
        this.maxLatitude = maxLat;
    }
}
