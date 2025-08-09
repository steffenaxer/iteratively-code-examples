package io.iteratively.matsim;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ChicagoTNPDownloader {

    private static final HttpClient client = HttpClient.newHttpClient();

    public static String downloadTrips(String token, int limit, int offset) throws IOException, InterruptedException {
        String apiUrl = String.format(
                "https://data.cityofchicago.org/resource/6dvr-xwnh.json?$limit=%d&$offset=%d",
                limit, offset
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("X-App-Token", token)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch data: HTTP " + response.statusCode());
        }

        return response.body();
    }
}
