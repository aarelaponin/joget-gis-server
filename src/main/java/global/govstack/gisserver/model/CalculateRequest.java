package global.govstack.gisserver.model;

/**
 * Request for geometry calculation operations.
 */
public class CalculateRequest {
    
    private String geometry;           // GeoJSON geometry string
    private boolean includeAreaSqM;    // Include area in square meters
    private boolean includeBoundingBox; // Include bounding box
    private int areaDecimalPrecision;   // Decimal precision for area (default 4)
    private int perimeterDecimalPrecision; // Decimal precision for perimeter (default 2)
    private int coordinateDecimalPrecision; // Decimal precision for coordinates (default 7)
    
    public CalculateRequest() {
        this.includeAreaSqM = true;
        this.includeBoundingBox = true;
        this.areaDecimalPrecision = 4;
        this.perimeterDecimalPrecision = 2;
        this.coordinateDecimalPrecision = 7;
    }
    
    public String getGeometry() {
        return geometry;
    }
    
    public void setGeometry(String geometry) {
        this.geometry = geometry;
    }
    
    public boolean isIncludeAreaSqM() {
        return includeAreaSqM;
    }
    
    public void setIncludeAreaSqM(boolean includeAreaSqM) {
        this.includeAreaSqM = includeAreaSqM;
    }
    
    public boolean isIncludeBoundingBox() {
        return includeBoundingBox;
    }
    
    public void setIncludeBoundingBox(boolean includeBoundingBox) {
        this.includeBoundingBox = includeBoundingBox;
    }
    
    public int getAreaDecimalPrecision() {
        return areaDecimalPrecision;
    }
    
    public void setAreaDecimalPrecision(int areaDecimalPrecision) {
        this.areaDecimalPrecision = areaDecimalPrecision;
    }
    
    public int getPerimeterDecimalPrecision() {
        return perimeterDecimalPrecision;
    }
    
    public void setPerimeterDecimalPrecision(int perimeterDecimalPrecision) {
        this.perimeterDecimalPrecision = perimeterDecimalPrecision;
    }
    
    public int getCoordinateDecimalPrecision() {
        return coordinateDecimalPrecision;
    }
    
    public void setCoordinateDecimalPrecision(int coordinateDecimalPrecision) {
        this.coordinateDecimalPrecision = coordinateDecimalPrecision;
    }
}
