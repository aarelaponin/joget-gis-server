package global.govstack.gisserver.service;

import global.govstack.gisserver.engine.GeometryEngine;
import global.govstack.gisserver.model.NearbyParcel;
import global.govstack.gisserver.model.NearbyParcelsResult;
import global.govstack.gisserver.util.InputValidator;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for retrieving nearby parcels within a bounding box.
 *
 * This service returns parcels for READ-ONLY display purposes.
 * It does NOT support any modification operations.
 *
 * Per spec section 2.2, this service:
 * - Queries Joget form data using FormDataDao
 * - Filters by bounding box intersection
 * - Calculates centroid and area for each parcel
 * - Enforces max results limit
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
     * @param formId           Form ID containing parcel data
     * @param geometryFieldId  Field containing GeoJSON geometry
     * @param minLng           Minimum longitude (west)
     * @param minLat           Minimum latitude (south)
     * @param maxLng           Maximum longitude (east)
     * @param maxLat           Maximum latitude (north)
     * @param excludeRecordId  Record ID to exclude (optional, for editing existing)
     * @param filterCondition  SQL filter condition (optional)
     * @param returnFields     Fields to include in response (optional)
     * @param maxResults       Maximum results to return (optional)
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
                            Coordinate centroid = geometryEngine.calculateCentroid(geom);
                            parcel.setCentroid(centroid.x, centroid.y);

                            // Calculate area
                            double areaSqM = geometryEngine.calculateAreaSquareMeters(geom);
                            parcel.setAreaHectares(Math.round(areaSqM / 100.0) / 100.0); // Round to 2 decimals

                            // Add requested fields
                            if (returnFields != null) {
                                for (String fieldId : returnFields) {
                                    String value = row.getProperty(fieldId);
                                    parcel.addRecordField(fieldId, value);
                                }
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
