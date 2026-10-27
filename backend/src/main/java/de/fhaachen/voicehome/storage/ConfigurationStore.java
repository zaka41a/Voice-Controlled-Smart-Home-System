package de.fhaachen.voicehome.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import de.fhaachen.voicehome.domain.HomeModel.Configuration;

/** Persists configuration only. Runtime observations must not survive as fresh device states. */
public final class ConfigurationStore implements ConfigurationRepository {
    private final Path file;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ConfigurationStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    public Optional<Configuration> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readString(file), Configuration.class));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read configuration: " + file, exception);
        }
    }

    public void save(Configuration configuration) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "voicehome-", ".tmp");
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot save configuration.", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Preserve original error. */ }
            }
        }
    }
}
