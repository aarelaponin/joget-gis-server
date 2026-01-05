package global.govstack.gisserver.model;

/**
 * Request for geometry validation operations.
 */
public class ValidationRequest {
    
    private String geometry;               // GeoJSON geometry string
    private Double minAreaHectares;        // Minimum area (default 0.01)
    private Double maxAreaHectares;        // Maximum area (default 1000)
    private Integer minVertices;           // Minimum vertices (default 3)
    private Integer maxVertices;           // Maximum vertices (default 100)
    private Boolean allowSelfIntersection; // Allow self-intersecting polygons (default false)
    
    public ValidationRequest() {
        this.minAreaHectares = 0.01;
        this.maxAreaHectares = 1000.0;
        this.minVertices = 3;
        this.maxVertices = 100;
        this.allowSelfIntersection = false;
    }
    
    public String getGeometry() {
        return geometry;
    }
    
    public void setGeometry(String geometry) {
        this.geometry = geometry;
    }
    
    public Double getMinAreaHectares() {
        return minAreaHectares;
    }
    
    public void setMinAreaHectares(Double minAreaHectares) {
        this.minAreaHectares = minAreaHectares;
    }
    
    public Double getMaxAreaHectares() {
        return maxAreaHectares;
    }
    
    public void setMaxAreaHectares(Double maxAreaHectares) {
        this.maxAreaHectares = maxAreaHectares;
    }
    
    public Integer getMinVertices() {
        return minVertices;
    }
    
    public void setMinVertices(Integer minVertices) {
        this.minVertices = minVertices;
    }
    
    public Integer getMaxVertices() {
        return maxVertices;
    }
    
    public void setMaxVertices(Integer maxVertices) {
        this.maxVertices = maxVertices;
    }
    
    public Boolean getAllowSelfIntersection() {
        return allowSelfIntersection;
    }
    
    public void setAllowSelfIntersection(Boolean allowSelfIntersection) {
        this.allowSelfIntersection = allowSelfIntersection;
    }
}
