package de.fhaachen.voicehome.control;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import de.fhaachen.voicehome.device.DeviceGateway;
import de.fhaachen.voicehome.device.SimulatedDeviceGateway;
import de.fhaachen.voicehome.domain.HomeModel.*;
import de.fhaachen.voicehome.storage.ConfigurationStore;

class HomeServiceTest {
    @TempDir Path directory;
    HomeService service;
    CountingGateway gateway;

    @BeforeEach
    void prepare() {
        gateway = new CountingGateway();
        service = new HomeService(new ConfigurationStore(directory.resolve("configuration.json")), gateway, .8);
    }

    @Test
    void explicitOnIsRepeatableAndOffIsDistinct() {
        assertThat(service.handle(new RecognizedCommand("LIGHT_ON", .95)).outcome()).isEqualTo(Outcome.CONFIRMED);
        service.handle(new RecognizedCommand("LIGHT_ON", .95));
        assertThat(service.getDevice("living-room-light").reportedState()).isEqualTo(State.ON);
        service.handle(new RecognizedCommand("LIGHT_OFF", .95));
        assertThat(service.getDevice("living-room-light").reportedState()).isEqualTo(State.OFF);
        assertThat(gateway.calls).isEqualTo(3);
    }

    @Test
    void rejectionNeverReachesTheDeviceOrChangesTheRequestedState() {
        for (RecognizedCommand command : new RecognizedCommand[] {
                new RecognizedCommand("LIGHT_ON", .79), new RecognizedCommand("UNSUPPORTED", 1.0),
                new RecognizedCommand("LIGHT_ON", null), new RecognizedCommand("LIGHT_ON", Double.NaN),
                new RecognizedCommand("LIGHT_ON", Double.POSITIVE_INFINITY), new RecognizedCommand("LIGHT_ON", 1.01),
                new RecognizedCommand("LIGHT_ON", -.1), new RecognizedCommand("", .9) }) {
            assertThat(service.handle(command).outcome()).isEqualTo(Outcome.REJECTED);
        }
        assertThat(gateway.calls).isZero();
        assertThat(service.getDevice("living-room-light").requestedState()).isEqualTo(State.UNKNOWN);
        assertThat(service.getDevice("living-room-light").reportedState()).isEqualTo(State.OFF);
    }

    @Test
    void configuredThresholdIsInclusive() {
        assertThat(service.handle(new RecognizedCommand("LIGHT_ON", .8)).outcome()).isEqualTo(Outcome.CONFIRMED);
    }

    @Test
    void unconfirmedDeliveryDoesNotBecomeConfirmedState() {
        service.putDevice(new Device("living-room-light", "Lampe", "Salon", SimulationMode.UNCONFIRMED));
        CommandResult result = service.handle(new RecognizedCommand("LIGHT_ON", .95));
        assertThat(result.outcome()).isEqualTo(Outcome.UNCONFIRMED);
        assertThat(result.requestedState()).isEqualTo(State.ON);
        assertThat(result.reportedState()).isEqualTo(State.UNKNOWN);
        assertThat(service.getDevice("living-room-light").reportedState()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void offlineDeviceReportsFailureAndUnknownState() {
        service.putDevice(new Device("living-room-light", "Lampe", "Salon", SimulationMode.OFFLINE));
        CommandResult result = service.handle(new RecognizedCommand("LIGHT_ON", .95));
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reportedState()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void configurationAndRoutingSurviveRestartButRuntimeHistoryDoesNot() {
        service.putDevice(new Device("desk-light", "Lampe du bureau", "Bureau", SimulationMode.CONFIRMED));
        service.putMapping(new Mapping("DESK_ON", "desk-light", Action.ON));
        service.handle(new RecognizedCommand("DESK_ON", .9));
        HomeService restored = new HomeService(new ConfigurationStore(directory.resolve("configuration.json")), new SimulatedDeviceGateway(), .8);
        assertThat(restored.getDevice("desk-light").name()).isEqualTo("Lampe du bureau");
        assertThat(restored.getDevice("desk-light").requestedState()).isEqualTo(State.UNKNOWN);
        assertThat(restored.dashboard().events()).isEmpty();
        assertThat(restored.handle(new RecognizedCommand("DESK_ON", .9)).deviceId()).isEqualTo("desk-light");
    }

    @Test
    void referencedDeviceCannotBeDeletedAndUnknownTargetsCannotBeConfigured() {
        assertThatThrownBy(() -> service.deleteDevice("living-room-light")).isInstanceOf(HomeService.Conflict.class);
        assertThatThrownBy(() -> service.putMapping(new Mapping("OTHER_ON", "missing", Action.ON))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.getDevice("living-room-light").id()).isEqualTo("living-room-light");
        assertThat(service.listMappings()).anyMatch(mapping -> mapping.commandId().equals("LIGHT_ON"));
        service.deleteMapping("LIGHT_ON");
        service.deleteMapping("LIGHT_OFF");
        service.deleteDevice("living-room-light");
        assertThat(service.listDevices()).noneMatch(device -> device.id().equals("living-room-light"));
    }

    @Test
    void malformedExistingConfigurationIsNotSilentlyReplaced() throws Exception {
        Path file = directory.resolve("corrupt.json");
        Files.writeString(file, "not JSON");
        assertThatThrownBy(() -> new HomeService(new ConfigurationStore(file), gateway, .8)).isInstanceOf(RuntimeException.class);
        assertThat(Files.readString(file)).isEqualTo("not JSON");
    }

    @Test
    void invalidDeviceConfigurationDoesNotOverwritePersistedData() throws Exception {
        String before = Files.readString(directory.resolve("configuration.json"));
        assertThatThrownBy(() -> service.putDevice(new Device("INVALID ID", "Test", "Salon", SimulationMode.CONFIRMED))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(directory.resolve("configuration.json"))).isEqualTo(before);
    }

    @Test
    void eventHistoryIsBoundedAndOrderedNewestFirst() {
        for (int index = 0; index < 110; index++) service.handle(new RecognizedCommand("UNKNOWN_" + index, .9));
        assertThat(service.dashboard().events()).hasSize(100);
        assertThat(service.dashboard().events().getFirst().commandId()).isEqualTo("UNKNOWN_109");
        assertThat(service.dashboard().events().getLast().commandId()).isEqualTo("UNKNOWN_10");
    }

    @Test
    void allSixCommandsProduceTheExpectedDeviceStates() {
        String[] commands = {"DOOR_OPEN", "DOOR_CLOSE", "WINDOW_OPEN", "WINDOW_CLOSE", "LIGHT_ON", "LIGHT_OFF"};
        State[] states = {State.OPEN, State.CLOSED, State.OPEN, State.CLOSED, State.ON, State.OFF};
        for (int index = 0; index < commands.length; index++) {
            CommandResult result = service.handle(new RecognizedCommand(commands[index], .95));
            assertThat(result.outcome()).as(commands[index]).isEqualTo(Outcome.CONFIRMED);
            assertThat(result.requestedState()).isEqualTo(states[index]);
            assertThat(result.reportedState()).isEqualTo(states[index]);
            assertThat(service.getDevice(result.deviceId()).reportedState()).isEqualTo(states[index]);
        }
        assertThat(gateway.calls).isEqualTo(6);
    }

    @Test
    void incompatibleMappingsAndDeviceTypeChangesAreRejected() {
        assertThatThrownBy(() -> service.putMapping(new Mapping("DOOR_OPEN", "living-room-light", Action.ON)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.putMapping(new Mapping("LIGHT_ON", "front-door", Action.OPEN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.putMapping(new Mapping("DOOR_CLOSE", "front-door", Action.ON)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.putDevice(new Device("front-door", "Door", "Entrance", SimulationMode.CONFIRMED, DeviceType.LIGHT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.getDevice("front-door").type()).isEqualTo(DeviceType.DOOR);
        assertThat(gateway.calls).isZero();
    }

    static final class CountingGateway implements DeviceGateway {
        private final SimulatedDeviceGateway delegate = new SimulatedDeviceGateway();
        int calls;
        @Override public Actuation execute(Device device, Action action) { calls++; return delegate.execute(device, action); }
        @Override public State readState(Device device) { return delegate.readState(device); }
    }
}
