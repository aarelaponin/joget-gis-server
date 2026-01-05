package global.govstack.gisserver.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of geometry validation containing validity status and any errors/warnings.
 *
 * Per spec section 3.5, validation results include structured error objects
 * with code, message, severity, and optional location.
 */
public class ValidationResult {

    private boolean valid;
    private List<ValidationError> errors;
    private List<ValidationError> warnings;
    private double areaHectares;
    private double perimeterMeters;
    private int vertexCount;

    public ValidationResult() {
        this.valid = true;
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    public void setErrors(List<ValidationError> errors) {
        this.errors = errors;
    }

    /**
     * Add a structured error and mark result as invalid.
     */
    public void addError(ValidationError error) {
        this.errors.add(error);
        this.valid = false;
    }

    /**
     * Add an error with code and message.
     */
    public void addError(String code, String message) {
        addError(ValidationError.error(code, message));
    }

    /**
     * Add an error with code, message, and location.
     */
    public void addErrorAt(String code, String message, double longitude, double latitude) {
        addError(ValidationError.errorAt(code, message, longitude, latitude));
    }

    public List<ValidationError> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<ValidationError> warnings) {
        this.warnings = warnings;
    }

    /**
     * Add a structured warning.
     */
    public void addWarning(ValidationError warning) {
        this.warnings.add(warning);
    }

    /**
     * Add a warning with code and message.
     */
    public void addWarning(String code, String message) {
        addWarning(ValidationError.warning(code, message));
    }

    /**
     * Add a warning with code, message, and location.
     */
    public void addWarningAt(String code, String message, double longitude, double latitude) {
        addWarning(ValidationError.warningAt(code, message, longitude, latitude));
    }

    public double getAreaHectares() {
        return areaHectares;
    }

    public void setAreaHectares(double areaHectares) {
        this.areaHectares = areaHectares;
    }

    public double getPerimeterMeters() {
        return perimeterMeters;
    }

    public void setPerimeterMeters(double perimeterMeters) {
        this.perimeterMeters = perimeterMeters;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public void setVertexCount(int vertexCount) {
        this.vertexCount = vertexCount;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
