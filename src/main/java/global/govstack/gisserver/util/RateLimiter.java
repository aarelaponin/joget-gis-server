package global.govstack.gisserver.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory rate limiter for API endpoints.
 *
 * Per spec section 7.3, implements per-endpoint rate limits:
 * - calculate: 100 requests/minute
 * - validate: 100 requests/minute
 * - simplify: 50 requests/minute
 * - checkOverlap: 20 requests/minute
 * - geocode: 30 requests/minute
 * - reverseGeocode: 30 requests/minute
 * - batchCalculate: 20 requests/minute
 * - nearbyParcels: 60 requests/minute
 * - health: unlimited
 */
public class RateLimiter {

    // Rate limits per endpoint (requests per minute)
    private static final Map<String, Integer> RATE_LIMITS = Map.ofEntries(
        Map.entry("calculate", 100),
        Map.entry("validate", 100),
        Map.entry("simplify", 50),
        Map.entry("checkOverlap", 20),
        Map.entry("geocode", 30),
        Map.entry("reverseGeocode", 30),
        Map.entry("batchCalculate", 20),
        Map.entry("nearbyParcels", 60)
    );

    // Window size in milliseconds (1 minute)
    private static final long WINDOW_MS = 60_000;

    // Storage for request counts per endpoint
    // Key: endpoint name, Value: RequestCounter
    private final Map<String, RequestCounter> counters = new ConcurrentHashMap<>();

    // Singleton instance
    private static volatile RateLimiter instance;

    private RateLimiter() {}

    /**
     * Get the singleton instance.
     */
    public static RateLimiter getInstance() {
        if (instance == null) {
            synchronized (RateLimiter.class) {
                if (instance == null) {
                    instance = new RateLimiter();
                }
            }
        }
        return instance;
    }

    /**
     * Check if request is allowed for the given endpoint.
     *
     * @param endpoint The endpoint name (e.g., "calculate", "validate")
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean isAllowed(String endpoint) {
        Integer limit = RATE_LIMITS.get(endpoint);
        if (limit == null) {
            // No rate limit for this endpoint (e.g., health)
            return true;
        }

        RequestCounter counter = counters.computeIfAbsent(endpoint, k -> new RequestCounter());
        return counter.tryIncrement(limit);
    }

    /**
     * Get the rate limit for an endpoint.
     *
     * @param endpoint The endpoint name
     * @return Rate limit (requests per minute) or -1 if unlimited
     */
    public int getRateLimit(String endpoint) {
        Integer limit = RATE_LIMITS.get(endpoint);
        return limit != null ? limit : -1;
    }

    /**
     * Get remaining requests for an endpoint in current window.
     *
     * @param endpoint The endpoint name
     * @return Remaining requests, or -1 if unlimited
     */
    public int getRemainingRequests(String endpoint) {
        Integer limit = RATE_LIMITS.get(endpoint);
        if (limit == null) {
            return -1;
        }

        RequestCounter counter = counters.get(endpoint);
        if (counter == null) {
            return limit;
        }

        return Math.max(0, limit - counter.getCount());
    }

    /**
     * Get seconds until rate limit resets.
     *
     * @param endpoint The endpoint name
     * @return Seconds until reset, or 0 if not applicable
     */
    public long getSecondsUntilReset(String endpoint) {
        RequestCounter counter = counters.get(endpoint);
        if (counter == null) {
            return 0;
        }

        long elapsed = System.currentTimeMillis() - counter.getWindowStart();
        long remaining = WINDOW_MS - elapsed;
        return Math.max(0, remaining / 1000);
    }

    /**
     * Reset all counters (for testing).
     */
    public void reset() {
        counters.clear();
    }

    /**
     * Thread-safe request counter with sliding window.
     */
    private static class RequestCounter {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger(0);

        /**
         * Try to increment the counter if within rate limit.
         *
         * @param limit The maximum requests per window
         * @return true if increment successful, false if limit exceeded
         */
        public synchronized boolean tryIncrement(int limit) {
            long now = System.currentTimeMillis();
            long start = windowStart.get();

            // Check if we need to reset the window
            if (now - start >= WINDOW_MS) {
                windowStart.set(now);
                count.set(1);
                return true;
            }

            // Check if within limit
            int current = count.get();
            if (current >= limit) {
                return false;
            }

            count.incrementAndGet();
            return true;
        }

        public int getCount() {
            long now = System.currentTimeMillis();
            if (now - windowStart.get() >= WINDOW_MS) {
                return 0;
            }
            return count.get();
        }

        public long getWindowStart() {
            return windowStart.get();
        }
    }
}
