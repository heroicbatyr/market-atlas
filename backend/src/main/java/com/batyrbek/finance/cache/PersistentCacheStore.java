package com.batyrbek.finance.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PersistentCacheStore {
    private static final Logger log = LoggerFactory.getLogger(PersistentCacheStore.class);
    private final ObjectMapper objectMapper;
    private final Path cacheDirectory;

    public PersistentCacheStore(ObjectMapper objectMapper,
                                @Value("${stock.cache.directory:/app/data/cache}") String cacheDirectory) {
        this.objectMapper = objectMapper;
        this.cacheDirectory = Path.of(cacheDirectory).toAbsolutePath().normalize();
    }

    public <T> Optional<T> read(String namespace, String key, Class<T> type, Duration maximumAge) {
        Path file = cacheFile(namespace, key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            PersistedValue persisted = objectMapper.readValue(file.toFile(), PersistedValue.class);
            if (persisted.savedAt() == null || persisted.value() == null
                    || persisted.savedAt().plus(maximumAge).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.treeToValue(persisted.value(), type));
        } catch (IOException | RuntimeException exception) {
            log.warn("Ignoring unreadable persistent finance cache entry {}/{}", namespace, key);
            return Optional.empty();
        }
    }

    public void write(String namespace, String key, Object value) {
        Path file = cacheFile(namespace, key);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(temporary.toFile(), new PersistedValue(Instant.now(), objectMapper.valueToTree(value)));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Unable to persist finance cache entry {}/{}", namespace, key);
        }
    }

    private Path cacheFile(String namespace, String key) {
        if (!namespace.matches("[a-z-]+") || !key.matches("[A-Z0-9.-]{1,10}")) {
            throw new IllegalArgumentException("Invalid persistent cache key");
        }
        return cacheDirectory.resolve(namespace).resolve(key + ".json");
    }

    private record PersistedValue(Instant savedAt, JsonNode value) {}
}
