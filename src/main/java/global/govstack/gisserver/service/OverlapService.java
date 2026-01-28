package global.govstack.gisserver.service;

import global.govstack.gisserver.engine.GeometryEngine;
import global.govstack.gisserver.model.OverlapResult;
import global.govstack.gisserver.model.OverlapResult.OverlapInfo;
import global.govstack.gisserver.util.InputValidator;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for checking geometry overlaps against existing records.
 * Uses FormDataDao directly with form/table names.
 *
 * Per spec section 4.4, this service is generic and can check overlaps
 * against any Joget form containing geometry data.
 */
public class OverlapService {

    private static final String CLASS_NAME = OverlapService.class.getName();

    private final GeometryEngine geometryEngine;

    public OverlapService() {
        this.geometryEngine = new GeometryEngine();
    }

    /**
     * Check for overlapping geometries in the database.
     *
     * Per spec section 4.4, this is a generic spatial query that checks
     * against any Joget form containing geometry data.
     *
     * @param geometry New geometry (GeoJSON)
     * @param excludeRecordId Record ID to exclude (for editing existing)
     * @param formId Form ID containing geometry data (also used as table name)
     * @param geometryFieldId Field ID containing GeoJSON geometry
     * @param filterCondition SQL filter condition (optional)
     * @param filterParams Parameters for filter condition (optional)
     * @param returnFields List of field IDs to include in recordData (optional)
     * @param minOverlapPercent Minimum overlap percentage to report
     * @param maxResults Maximum number of overlaps to return
     * @param includeOverlapGeometry Include overlap polygon in result
     * @return OverlapResult with found overlaps
     */
    public OverlapResult checkOverlaps(
            String geometry,
            String excludeRecordId,
            String formId,
            String geometryFieldId,
            String filterCondition,
            Object[] filterParams,
            List<String> returnFields,
            double minOverlapPercent,
            int maxResults,
            boolean includeOverlapGeometry) {

        OverlapResult result = new OverlapResult();

        try {
            // Parse new geometry
            Geometry newGeom;
            try {
                newGeom = geometryEngine.parseGeoJson(geometry);
            } catch (GeometryEngine.GeometryParseException e) {
                LogUtil.error(CLASS_NAME, e, "Failed to parse input geometry");
                return result;
            }

            double newAreaSqM = geometryEngine.calculateAreaSquareMeters(newGeom);

            // Validate filterCondition to prevent SQL injection
            String safeFilterCondition = filterCondition;
            if (filterCondition != null && !filterCondition.isEmpty()) {
                InputValidator.ValidationResult filterResult =
                    InputValidator.validateFilterCondition(filterCondition);
                if (!filterResult.isValid()) {
                    LogUtil.warn(CLASS_NAME, "Invalid filter rejected: " + filterResult.getMessage());
                    safeFilterCondition = null;  // Ignore invalid filter
                }
            }

            // Get FormDataDao
            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext()
                .getBean("formDataDao");

            // Build query condition with WHERE prefix
            StringBuilder conditionBuilder = new StringBuilder();
            List<Object> paramsList = new ArrayList<>();

            if (excludeRecordId != null && !excludeRecordId.isEmpty()) {
                conditionBuilder.append("WHERE id != ?");
                paramsList.add(excludeRecordId);
            }

            if (safeFilterCondition != null && !safeFilterCondition.isEmpty()) {
                if (conditionBuilder.length() == 0) {
                    conditionBuilder.append("WHERE ");
                } else {
                    conditionBuilder.append(" AND ");
                }
                conditionBuilder.append(safeFilterCondition);

                // Add filter params if provided
                if (filterParams != null) {
                    for (Object param : filterParams) {
                        paramsList.add(param);
                    }
                }
            }

            String condition = conditionBuilder.toString();
            Object[] params = paramsList.toArray();

            // Load records using formId as both form name and table name
            FormRowSet rowSet = formDataDao.find(formId, formId, condition, params, null, null, null, null);
            result.setCheckedRecordCount(rowSet != null ? rowSet.size() : 0);

            if (rowSet == null || rowSet.isEmpty()) {
                return result;
            }

            double totalOverlapArea = 0.0;
            int overlapsFound = 0;

            // Check each existing record for overlap
            for (FormRow row : rowSet) {
                if (overlapsFound >= maxResults) {
                    break;
                }

                String existingGeoJson = row.getProperty(geometryFieldId);
                if (existingGeoJson == null || existingGeoJson.isEmpty()) {
                    continue;
                }

                try {
                    Geometry existingGeom = geometryEngine.parseGeoJson(existingGeoJson);

                    // Check intersection
                    if (geometryEngine.intersects(newGeom, existingGeom)) {
                        Geometry overlapGeom = geometryEngine.intersection(newGeom, existingGeom);

                        if (overlapGeom != null && !overlapGeom.isEmpty()) {
                            double overlapAreaSqM = geometryEngine.calculateAreaSquareMeters(overlapGeom);
                            double overlapPercentInput = (newAreaSqM > 0) ?
                                (overlapAreaSqM / newAreaSqM * 100.0) : 0.0;

                            double existingAreaSqM = geometryEngine.calculateAreaSquareMeters(existingGeom);
                            double overlapPercentExisting = (existingAreaSqM > 0) ?
                                (overlapAreaSqM / existingAreaSqM * 100.0) : 0.0;

                            // Filter by minimum overlap percent
                            if (overlapPercentInput >= minOverlapPercent) {
                                OverlapInfo info = new OverlapInfo();
                                info.setRecordId(row.getId());

                                // Add requested return fields to recordData
                                if (returnFields != null) {
                                    for (String fieldId : returnFields) {
                                        String value = row.getProperty(fieldId);
                                        info.addRecordField(fieldId, value);
                                    }
                                }

                                info.setOverlapAreaHectares(overlapAreaSqM / 10000.0);
                                info.setOverlapPercentOfInput(Math.round(overlapPercentInput * 10.0) / 10.0);
                                info.setOverlapPercentOfExisting(Math.round(overlapPercentExisting * 10.0) / 10.0);

                                if (includeOverlapGeometry) {
                                    info.setOverlapGeometry(geometryEngine.toGeoJson(overlapGeom));
                                }

                                result.addOverlap(info);
                                totalOverlapArea += overlapAreaSqM / 10000.0;
                                overlapsFound++;
                            }
                        }
                    }
                } catch (GeometryEngine.GeometryParseException e) {
                    LogUtil.warn(CLASS_NAME, "Failed to parse geometry for record " + row.getId());
                }
            }

            result.setTotalOverlapAreaHectares(Math.round(totalOverlapArea * 1000.0) / 1000.0);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error checking overlaps");
        }

        return result;
    }
}
