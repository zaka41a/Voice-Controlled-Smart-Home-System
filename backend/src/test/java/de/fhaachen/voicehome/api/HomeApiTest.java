package de.fhaachen.voicehome.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HomeApiTest {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("voicehome.configuration-file", () -> directory.resolve("configuration.json").toString());
        registry.add("voicehome.minimum-confidence", () -> "0.8");
    }

    @Test
    void uiAssetsAndDashboardAreServedByTheSameApplication() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.simulation").value(true))
                .andExpect(jsonPath("$.settings.minimumConfidence").value(.8));
    }

    @Test
    void commandAcceptanceAndRejectionAreObservableThroughTheApi() throws Exception {
        mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content("""
                {"commandId":"LIGHT_ON","confidence":0.95}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                .andExpect(jsonPath("$.reportedState").value("ON"));
        mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content("""
                {"commandId":"LIGHT_OFF","confidence":0.2}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("REJECTED"));
        mvc.perform(get("/api/devices/living-room-light")).andExpect(jsonPath("$.reportedState").value("ON"));
    }

    @Test
    void invalidPayloadsAndMissingResourcesReturnUsefulHttpErrors() throws Exception {
        mvc.perform(put("/api/devices/api-invalid").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"","room":"Salon","simulationMode":"CONFIRMED"}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/api/devices/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/devices/living-room-light")).andExpect(status().isConflict());
    }

    @Test
    void configurableDeviceAndMappingCanBeCreatedUsedAndRemoved() throws Exception {
        mvc.perform(put("/api/devices/api-lamp").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Lampe API","room":"Bureau","simulationMode":"UNCONFIRMED"}
                """)).andExpect(status().isOk());
        mvc.perform(put("/api/mappings/API_ON").contentType(MediaType.APPLICATION_JSON).content("""
                {"deviceId":"api-lamp","action":"ON"}
                """)).andExpect(status().isOk());
        mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content("""
                {"commandId":"API_ON","confidence":0.95}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.reportedState").value("UNKNOWN"));
        mvc.perform(delete("/api/mappings/API_ON")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/devices/api-lamp")).andExpect(status().isNoContent());
    }
}
