package global.govstack.gisserver.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of overlap checking operation.
 *
 * Per spec section 4.4, this is a generic overlap result that uses:
 * - recordId (instead of parcelId)
 * - recordData map (instead of parcelCode/parcelName)
 * - checkedRecordCount (instead of checkedParcelCount)
 */
public class OverlapResult {

    private boolean hasOverlaps;
    private List<OverlapInfo> overlaps;
    private double totalOverlapAreaHectares;
    private int checkedRecordCount;

    public OverlapResult() {
        this.hasOverlaps = false;
        this.overlaps = new ArrayList<>();
        this.totalOverlapAreaHectares = 0.0;
        this.checkedRecordCount = 0;
    }

    public boolean isHasOverlaps() {
        return hasOverlaps;
    }

    public void setHasOverlaps(boolean hasOverlaps) {
        this.hasOverlaps = hasOverlaps;
    }

    public List<OverlapInfo> getOverlaps() {
        return overlaps;
    }

    public void setOverlaps(List<OverlapInfo> overlaps) {
        this.overlaps = overlaps;
    }

    public void addOverlap(OverlapInfo overlap) {
        this.overlaps.add(overlap);
        this.hasOverlaps = true;
    }

    public double getTotalOverlapAreaHectares() {
        return totalOverlapAreaHectares;
    }

    public void setTotalOverlapAreaHectares(double totalOverlapAreaHectares) {
        this.totalOverlapAreaHectares = totalOverlapAreaHectares;
    }

    public int getCheckedRecordCount() {
        return checkedRecordCount;
    }

    public void setCheckedRecordCount(int checkedRecordCount) {
        this.checkedRecordCount = checkedRecordCount;
    }

    /**
     * Information about a single overlapping record.
     *
     * Per spec section 4.4:
     * - Uses recordId (generic) instead of parcelId
     * - Uses recordData map for custom fields instead of hardcoded parcelCode/parcelName
     * - Uses overlapPercentOfInput (renamed from overlapPercentOfNew)
     */
    public static class OverlapInfo {
        private String recordId;
        private Map<String, String> recordData;
        private double overlapAreaHectares;
        private double overlapPercentOfInput;
        private double overlapPercentOfExisting;
        private String overlapGeometry;  // GeoJSON of the overlap polygon

        public OverlapInfo() {
            this.recordData = new HashMap<>();
        }

        public String getRecordId() {
            return recordId;
        }

        public void setRecordId(String recordId) {
            this.recordId = recordId;
        }

        public Map<String, String> getRecordData() {
            return recordData;
        }

        public void setRecordData(Map<String, String> recordData) {
            this.recordData = recordData;
        }

        public void addRecordField(String fieldId, String value) {
            if (value != null) {
                this.recordData.put(fieldId, value);
            }
        }

        public double getOverlapAreaHectares() {
            return overlapAreaHectares;
        }

        public void setOverlapAreaHectares(double overlapAreaHectares) {
            this.overlapAreaHectares = overlapAreaHectares;
        }

        public double getOverlapPercentOfInput() {
            return overlapPercentOfInput;
        }

        public void setOverlapPercentOfInput(double overlapPercentOfInput) {
            this.overlapPercentOfInput = overlapPercentOfInput;
        }

        public double getOverlapPercentOfExisting() {
            return overlapPercentOfExisting;
        }

        public void setOverlapPercentOfExisting(double overlapPercentOfExisting) {
            this.overlapPercentOfExisting = overlapPercentOfExisting;
        }

        public String getOverlapGeometry() {
            return overlapGeometry;
        }

        public void setOverlapGeometry(String overlapGeometry) {
            this.overlapGeometry = overlapGeometry;
        }
    }
}
