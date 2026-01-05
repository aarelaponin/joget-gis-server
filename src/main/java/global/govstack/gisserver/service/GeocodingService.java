package global.govstack.gisserver.service;

import org.joget.commons.util.LogUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geocoding service using Nominatim (OpenStreetMap).
 *
 * Per spec sections 4.5 and 4.6:
 * - Forward geocoding: search by name, return coordinates
 * - Reverse geocoding: coordinates to place name
 *
 * Implementation requirements:
 * - Cache results for 24 hours
 * - Rate limit: 1 request/second for Nominatim
 * - Proper User-Agent header as required by Nominatim usage policy
 */
public class GeocodingService {

    private static final String CLASS_NAME = GeocodingService.class.getName();

    // Nominatim base URL
    private static final String NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org";

    // User-Agent header required by Nominatim usage policy
    private static final String USER_AGENT = "JogetGISServer/8.1 (GovStack)";

    // Cache configuration: 24 hours in milliseconds
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000;

    // Rate limiting: minimum interval between requests (1 second)
    private static final long MIN_REQUEST_INTERVAL_MS = 1000;

    // Cache storage
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // Last request timestamp for rate limiting
    private volatile long lastRequestTime = 0;

    // Lock for rate limiting
    private final Object rateLimitLock = new Object();

    /**
     * Forward geocode: search for location by name.
     *
     * Per spec section 4.5:
     * - query: Search term (min 3 characters)
     * - limit: Maximum results to return (default 5)
     * - countryCode: ISO country code to bias results
     * - boundingBox: minLon,minLat,maxLon,maxLat to limit search area
     *
     * @param query Search query (required, min 3 chars)
     * @param limit Maximum results (default 5)
     * @param countryCode ISO country code for bias (optional)
     * @param boundingBox Bounding box as "minLon,minLat,maxLon,maxLat" (optional)
     * @return GeocodingResult with results array
     */
    public GeocodingResult geocode(String query, Integer limit, String countryCode, String boundingBox) {
        GeocodingResult result = new GeocodingResult();
        result.setQuery(query);

        // Validate query
        if (query == null || query.trim().length() < 3) {
            result.setError("INVALID_QUERY", "Query must be at least 3 characters");
            return result;
        }

        query = query.trim();
        int maxResults = (limit != null && limit > 0) ? Math.min(limit, 10) : 5;

        // Build cache key
        String cacheKey = "geocode:" + query + ":" + maxResults + ":" +
                          (countryCode != null ? countryCode : "") + ":" +
                          (boundingBox != null ? boundingBox : "");

        // Check cache
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            LogUtil.debug(CLASS_NAME, "Cache hit for geocode: " + query);
            return cached.getResult();
        }

        try {
            // Build Nominatim search URL
            StringBuilder urlBuilder = new StringBuilder(NOMINATIM_BASE_URL);
            urlBuilder.append("/search?format=jsonv2&addressdetails=1");
            urlBuilder.append("&q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            urlBuilder.append("&limit=").append(maxResults);

            if (countryCode != null && !countryCode.isEmpty()) {
                urlBuilder.append("&countrycodes=").append(URLEncoder.encode(countryCode, StandardCharsets.UTF_8));
            }

            if (boundingBox != null && !boundingBox.isEmpty()) {
                // Format: minLon,minLat,maxLon,maxLat -> viewbox=minLon,maxLat,maxLon,minLat
                String[] parts = boundingBox.split(",");
                if (parts.length == 4) {
                    urlBuilder.append("&viewbox=")
                              .append(parts[0]).append(",").append(parts[3]).append(",")
                              .append(parts[2]).append(",").append(parts[1]);
                    urlBuilder.append("&bounded=1");
                }
            }

            // Make request with rate limiting
            String responseBody = makeNominatimRequest(urlBuilder.toString());

            // Parse response
            JSONArray jsonArray = new JSONArray(responseBody);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject place = jsonArray.getJSONObject(i);
                GeocodingResult.GeocodingResultItem item = new GeocodingResult.GeocodingResultItem();

                item.setDisplayName(place.optString("display_name", ""));

                // Location as GeoJSON Point
                double lon = place.optDouble("lon", 0);
                double lat = place.optDouble("lat", 0);
                item.setLongitude(lon);
                item.setLatitude(lat);

                // Type from category/type
                String type = place.optString("type", place.optString("category", "place"));
                item.setType(type);

                // Bounding box if available
                JSONArray bbox = place.optJSONArray("boundingbox");
                if (bbox != null && bbox.length() == 4) {
                    // Nominatim returns: [minLat, maxLat, minLon, maxLon]
                    item.setMinLatitude(bbox.optDouble(0));
                    item.setMaxLatitude(bbox.optDouble(1));
                    item.setMinLongitude(bbox.optDouble(2));
                    item.setMaxLongitude(bbox.optDouble(3));
                }

                result.addResult(item);
            }

            result.setTotalResults(result.getResults().size());

            // Cache successful result
            cache.put(cacheKey, new CacheEntry(result));

        } catch (GeocodingException e) {
            result.setError(e.getCode(), e.getMessage());
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in geocode");
            result.setError("GEOCODING_ERROR", "Failed to geocode: " + e.getMessage());
        }

        return result;
    }

    /**
     * Reverse geocode: get place name from coordinates.
     *
     * Per spec section 4.6:
     * - lon: Longitude (required)
     * - lat: Latitude (required)
     * - zoom: Detail level (higher = more specific, default 14)
     *
     * @param longitude Longitude
     * @param latitude Latitude
     * @param zoom Detail level (3-18, default 14)
     * @return ReverseGeocodingResult with place information
     */
    public ReverseGeocodingResult reverseGeocode(double longitude, double latitude, Integer zoom) {
        ReverseGeocodingResult result = new ReverseGeocodingResult();
        result.setLongitude(longitude);
        result.setLatitude(latitude);

        // Validate coordinates
        if (longitude < -180 || longitude > 180) {
            result.setError("INVALID_LONGITUDE", "Longitude must be between -180 and 180");
            return result;
        }
        if (latitude < -90 || latitude > 90) {
            result.setError("INVALID_LATITUDE", "Latitude must be between -90 and 90");
            return result;
        }

        int zoomLevel = (zoom != null && zoom >= 3 && zoom <= 18) ? zoom : 14;

        // Build cache key
        String cacheKey = "reverse:" + longitude + ":" + latitude + ":" + zoomLevel;

        // Check cache
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            LogUtil.debug(CLASS_NAME, "Cache hit for reverse geocode: " + longitude + "," + latitude);
            return (ReverseGeocodingResult) cached.getResult();
        }

        try {
            // Build Nominatim reverse URL
            StringBuilder urlBuilder = new StringBuilder(NOMINATIM_BASE_URL);
            urlBuilder.append("/reverse?format=jsonv2&addressdetails=1");
            urlBuilder.append("&lat=").append(latitude);
            urlBuilder.append("&lon=").append(longitude);
            urlBuilder.append("&zoom=").append(zoomLevel);

            // Make request with rate limiting
            String responseBody = makeNominatimRequest(urlBuilder.toString());

            // Parse response
            JSONObject place = new JSONObject(responseBody);

            // Check for error
            if (place.has("error")) {
                result.setError("NOT_FOUND", place.optString("error", "Location not found"));
                return result;
            }

            result.setDisplayName(place.optString("display_name", ""));

            // Parse address object
            JSONObject address = place.optJSONObject("address");
            if (address != null) {
                ReverseGeocodingResult.Address addr = new ReverseGeocodingResult.Address();

                // Extract common address components
                addr.setVillage(getFirstNonNull(address,
                    "village", "hamlet", "town", "city", "municipality"));
                addr.setDistrict(getFirstNonNull(address,
                    "county", "district", "state_district", "region"));
                addr.setCountry(address.optString("country", null));
                addr.setCountryCode(address.optString("country_code", "").toUpperCase());

                result.setAddress(addr);
            }

            // Cache successful result
            cache.put(cacheKey, new CacheEntry(result));

        } catch (GeocodingException e) {
            result.setError(e.getCode(), e.getMessage());
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in reverse geocode");
            result.setError("GEOCODING_ERROR", "Failed to reverse geocode: " + e.getMessage());
        }

        return result;
    }

    /**
     * Make HTTP request to Nominatim with rate limiting.
     */
    private String makeNominatimRequest(String urlString) throws GeocodingException {
        // Apply rate limiting
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTime;

            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                try {
                    Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GeocodingException("INTERRUPTED", "Request interrupted");
                }
            }

            lastRequestTime = System.currentTimeMillis();
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();

            if (responseCode == 429) {
                throw new GeocodingException("RATE_LIMITED", "Nominatim rate limit exceeded");
            }

            if (responseCode != 200) {
                throw new GeocodingException("HTTP_ERROR", "HTTP error " + responseCode);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            return response.toString();

        } catch (IOException e) {
            throw new GeocodingException("NETWORK_ERROR", "Network error: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Get first non-null value from multiple keys in JSON object.
     */
    private String getFirstNonNull(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, null);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Clear expired cache entries.
     */
    public void cleanCache() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    // ==========================================================================
    // INNER CLASSES
    // ==========================================================================

    /**
     * Cache entry with TTL.
     */
    private static class CacheEntry {
        private final Object result;
        private final long timestamp;

        public CacheEntry(Object result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }

        @SuppressWarnings("unchecked")
        public <T> T getResult() {
            return (T) result;
        }
    }

    /**
     * Exception for geocoding errors.
     */
    public static class GeocodingException extends Exception {
        private final String code;

        public GeocodingException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * Result for forward geocoding.
     */
    public static class GeocodingResult {
        private String query;
        private int totalResults;
        private final java.util.List<GeocodingResultItem> results = new java.util.ArrayList<>();
        private String errorCode;
        private String errorMessage;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public int getTotalResults() {
            return totalResults;
        }

        public void setTotalResults(int totalResults) {
            this.totalResults = totalResults;
        }

        public java.util.List<GeocodingResultItem> getResults() {
            return results;
        }

        public void addResult(GeocodingResultItem item) {
            results.add(item);
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setError(String code, String message) {
            this.errorCode = code;
            this.errorMessage = message;
        }

        public boolean hasError() {
            return errorCode != null;
        }

        /**
         * Individual geocoding result item.
         */
        public static class GeocodingResultItem {
            private String displayName;
            private double longitude;
            private double latitude;
            private String type;
            private Double minLongitude;
            private Double maxLongitude;
            private Double minLatitude;
            private Double maxLatitude;

            public String getDisplayName() {
                return displayName;
            }

            public void setDisplayName(String displayName) {
                this.displayName = displayName;
            }

            public double getLongitude() {
                return longitude;
            }

            public void setLongitude(double longitude) {
                this.longitude = longitude;
            }

            public double getLatitude() {
                return latitude;
            }

            public void setLatitude(double latitude) {
                this.latitude = latitude;
            }

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
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

            public boolean hasBoundingBox() {
                return minLongitude != null && maxLongitude != null &&
                       minLatitude != null && maxLatitude != null;
            }
        }
    }

    /**
     * Result for reverse geocoding.
     */
    public static class ReverseGeocodingResult {
        private double longitude;
        private double latitude;
        private String displayName;
        private Address address;
        private String errorCode;
        private String errorMessage;

        public double getLongitude() {
            return longitude;
        }

        public void setLongitude(double longitude) {
            this.longitude = longitude;
        }

        public double getLatitude() {
            return latitude;
        }

        public void setLatitude(double latitude) {
            this.latitude = latitude;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Address getAddress() {
            return address;
        }

        public void setAddress(Address address) {
            this.address = address;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setError(String code, String message) {
            this.errorCode = code;
            this.errorMessage = message;
        }

        public boolean hasError() {
            return errorCode != null;
        }

        /**
         * Address components.
         */
        public static class Address {
            private String village;
            private String district;
            private String country;
            private String countryCode;

            public String getVillage() {
                return village;
            }

            public void setVillage(String village) {
                this.village = village;
            }

            public String getDistrict() {
                return district;
            }

            public void setDistrict(String district) {
                this.district = district;
            }

            public String getCountry() {
                return country;
            }

            public void setCountry(String country) {
                this.country = country;
            }

            public String getCountryCode() {
                return countryCode;
            }

            public void setCountryCode(String countryCode) {
                this.countryCode = countryCode;
            }
        }
    }
}
