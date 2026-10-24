package de.fhaachen.voicehome;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import de.fhaachen.voicehome.control.HomeService;
import de.fhaachen.voicehome.device.DeviceGateway;
import de.fhaachen.voicehome.device.SimulatedDeviceGateway;
import de.fhaachen.voicehome.storage.ConfigurationStore;

@SpringBootApplication
public class VoiceHomeApplication {
    public static void main(String[] args) {
        SpringApplication.run(VoiceHomeApplication.class, args);
    }

    @Bean
    DeviceGateway deviceGateway() {
        return new SimulatedDeviceGateway();
    }

    @Bean
    ConfigurationStore configurationStore(@Value("${voicehome.configuration-file}") String file) {
        return new ConfigurationStore(Path.of(file));
    }

    @Bean
    HomeService homeService(ConfigurationStore store, DeviceGateway gateway,
                            @Value("${voicehome.minimum-confidence}") double minimumConfidence) {
        return new HomeService(store, gateway, minimumConfidence);
    }
}
