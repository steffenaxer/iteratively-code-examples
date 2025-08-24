package io.iteratively.matsim;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TripLoader {

    private static final HttpClient client = HttpClient.newHttpClient();

    public static String downloadFromUrl(String url, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
