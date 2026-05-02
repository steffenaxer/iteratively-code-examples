package io.iteratively.jobEstimator.calibration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Fetches NUTS-3 employment data from Eurostat's JSON API and resolves
 * NUTS-3 codes from WGS84 coordinates via GISCO.
 *
 * <p>Dataset: nama_10r_3empers (Employment in persons by NUTS-3 region).
 */
public final class EurostatClient {

    private static final Logger LOG = LogManager.getLogger(EurostatClient.class);

    private static final String EMPLOYMENT_URL =
            "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/nama_10r_3empers";
    private static final String GISCO_NUTS_URL =
            "https://gisco-services.ec.europa.eu/nuts/find-nuts.py";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Resolves the NUTS-3 code for a WGS84 coordinate via GISCO.
     *
     * @param lon longitude (WGS84)
     * @param lat latitude (WGS84)
     * @return NUTS-3 code (e.g. "DE911"), or empty if lookup fails
     */
    public Optional<String> findNutsCode(double lon, double lat) {
        try {
            String url = String.format(Locale.ROOT, "%s?x=%.4f&y=%.4f&year=2021&level=3", GISCO_NUTS_URL, lon, lat);
            LOG.info("GISCO NUTS lookup at ({}, {})", String.format("%.4f", lon), String.format("%.4f", lat));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("GISCO returned HTTP {}", response.statusCode());
                return Optional.empty();
            }

            JSONObject root = new JSONObject(response.body());
            JSONArray features = root.getJSONArray("features");
            if (features.isEmpty()) {
                LOG.warn("No NUTS-3 region found at ({}, {})", lon, lat);
                return Optional.empty();
            }

            String nutsCode = features.getJSONObject(0).getString("id");
            String label = features.getJSONObject(0).optJSONObject("properties") != null
                    ? features.getJSONObject(0).getJSONObject("properties").optString("id", nutsCode)
                    : nutsCode;
            LOG.info("Resolved NUTS-3: {} for coordinates ({}, {})", nutsCode, String.format("%.4f", lon), String.format("%.4f", lat));
            return Optional.of(nutsCode);
        } catch (Exception e) {
            LOG.error("GISCO NUTS lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetches total employment (domestic concept, thousand persons) for a NUTS-3 region.
     * Returns the most recent year available, converted to absolute persons.
     *
     * @param nutsCode NUTS-3 code (e.g. "DE911" for Braunschweig)
     * @return employment in persons, or empty if unavailable
     */
    public OptionalDouble fetchEmployment(String nutsCode) {
        try {
            String url = EMPLOYMENT_URL + "?geo=" + nutsCode
                    + "&unit=THS&wstatus=EMP&nace_r2=TOTAL&sinceTimePeriod=2018";
            LOG.info("Fetching Eurostat employment for NUTS-3 '{}'", nutsCode);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("Eurostat API returned HTTP {}: {}", response.statusCode(), response.body());
                return OptionalDouble.empty();
            }

            return parseLatestValue(response.body(), nutsCode);
        } catch (Exception e) {
            LOG.error("Failed to fetch Eurostat data for {}: {}", nutsCode, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    private OptionalDouble parseLatestValue(String json, String nutsCode) {
        JSONObject root = new JSONObject(json);
        JSONObject value = root.optJSONObject("value");
        if (value == null || value.isEmpty()) {
            LOG.warn("No values in Eurostat response for {}", nutsCode);
            return OptionalDouble.empty();
        }

        JSONObject dimension = root.getJSONObject("dimension");
        JSONObject timeDim = dimension.getJSONObject("time").getJSONObject("category").getJSONObject("index");

        String latestYear = null;
        int latestTimeIdx = -1;
        for (String timeLabel : timeDim.keySet()) {
            int idx = timeDim.getInt(timeLabel);
            if (idx > latestTimeIdx) {
                String valueKey = String.valueOf(idx);
                if (value.has(valueKey) && !value.isNull(valueKey)) {
                    latestTimeIdx = idx;
                    latestYear = timeLabel;
                }
            }
        }

        if (latestYear == null) {
            LOG.warn("No valid data points for {}", nutsCode);
            return OptionalDouble.empty();
        }

        double thousands = value.getDouble(String.valueOf(latestTimeIdx));
        double persons = thousands * 1000.0;
        LOG.info("Eurostat: {} ({}) = {} thousand persons = {} employed",
                nutsCode, latestYear, String.format("%.2f", thousands), String.format("%.0f", persons));
        return OptionalDouble.of(persons);
    }
}
