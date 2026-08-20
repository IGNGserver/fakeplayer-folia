package io.github.hello09x.fakeplayer.core.util.update;

import com.google.gson.Gson;
import io.github.hello09x.devtools.core.version.InvalidVersionException;
import io.github.hello09x.devtools.core.version.Version;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class UpdateChecker {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final static Gson gson = new Gson();

    private final String author;

    private final String repository;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UpdateChecker(@NotNull String author, @NotNull String repository) {
        this.author = author;
        this.repository = repository;
    }

    public static boolean isNew(@NotNull String local, @NotNull String remote) {
        Version a, b;
        try {
            a = Version.parse(local);
            b = Version.parse(remote);
        } catch (InvalidVersionException e) {
            return false;
        }

        return a.compareTo(b) < 0;
    }

    public @NotNull Release getLastRelease() throws IOException, InterruptedException {
        var url = String.format("https://api.github.com/repos/%s/%s/releases/latest", this.author, this.repository);
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Accept", "application/vnd.github+json")
                                 .header("User-Agent", "fakeplayer-update-checker")
                                 .GET()
                                 .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                throw new IllegalStateException("Not 200 response: " + response.statusCode());
            }
        }

        try (var bodyStream = response.body()) {
            var body = bodyStream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new IOException("GitHub release response is too large");
            }
            var release = gson.fromJson(new String(body, StandardCharsets.UTF_8), Release.class);
            if (release == null || release.getTagName() == null || release.getTagName().isBlank()) {
                throw new IOException("GitHub release response has no tag_name");
            }
            return release;
        }
    }


}
