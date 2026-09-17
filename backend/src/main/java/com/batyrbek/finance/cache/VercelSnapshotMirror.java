package com.batyrbek.finance.cache;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VercelSnapshotMirror {
    private static final Logger log = LoggerFactory.getLogger(VercelSnapshotMirror.class);
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;
    private final String secret;
    private final Map<String, Instant> mirroredVersions = new ConcurrentHashMap<>();

    public VercelSnapshotMirror(ObjectMapper objectMapper,
                                @Value("${stock.backup.url:}") String endpoint,
                                @Value("${stock.backup.secret:}") String secret) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public void backup(String kind, String ticker, Instant updatedAt, Object data) {
        if (endpoint.isBlank() || secret.isBlank()) return;
        String identity = kind + ":" + ticker;
        if (updatedAt.equals(mirroredVersions.get(identity))) return;
        mirroredVersions.put(identity, updatedAt);
        try {
            String body = objectMapper.writeValueAsString(new SnapshotRequest(ticker, kind, data));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null || response.statusCode() < 200 || response.statusCode() >= 300) {
                            mirroredVersions.remove(identity, updatedAt);
                            log.warn("Finance snapshot backup failed for {}/{}", kind, ticker);
                        }
                    });
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            mirroredVersions.remove(identity, updatedAt);
            log.warn("Finance snapshot backup could not be prepared for {}/{}", kind, ticker);
        }
    }

    private record SnapshotRequest(String ticker, String kind, Object data) {}
}
