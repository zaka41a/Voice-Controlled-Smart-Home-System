package de.fhaachen.voicehome.storage;

import java.util.Optional;
import de.fhaachen.voicehome.domain.HomeModel.Configuration;

public interface ConfigurationRepository {
    Optional<Configuration> load();
    void save(Configuration configuration);
}
