package de.fhaachen.voicehome.domain;

import java.time.Instant;
import java.util.List;

/** Transport-neutral command IDs and explicit actions form the first integration boundary. */
public final class HomeModel {
    private HomeModel() { }

    public enum Action {
        ON, OFF, OPEN, CLOSE;
        public State targetState() {
            return switch (this) {
                case ON -> State.ON;
                case OFF -> State.OFF;
                case OPEN -> State.OPEN;
                case CLOSE -> State.CLOSED;
            };
        }
    }
    public enum State { ON, OFF, OPEN, CLOSED, UNKNOWN }
    public enum SimulationMode { CONFIRMED, UNCONFIRMED, OFFLINE }
    public enum Outcome { CONFIRMED, REJECTED, FAILED, UNCONFIRMED }
    public enum DeviceType {
        LIGHT, PLUG, DOOR, WINDOW;
        public boolean supports(Action action) {
            return switch (this) {
                case LIGHT, PLUG -> action == Action.ON || action == Action.OFF;
                case DOOR, WINDOW -> action == Action.OPEN || action == Action.CLOSE;
            };
        }
        public State initialState() { return this == DOOR || this == WINDOW ? State.CLOSED : State.OFF; }
    }

    public record Device(String id, String name, String room, SimulationMode simulationMode, DeviceType type) {
        public Device {
            // Previous configurations represented lamps and did not contain a type.
            if (type == null) { type = DeviceType.LIGHT; }
        }
        public Device(String id, String name, String room, SimulationMode simulationMode) {
            this(id, name, room, simulationMode, DeviceType.LIGHT);
        }
    }
    public record Mapping(String commandId, String deviceId, Action action) { }
    public record RecognizedCommand(String commandId, Double confidence) { }
    public record CommandDefinition(String id, Action action, List<DeviceType> types) { }

    // One catalogue is used by validation and advertised to the UI for configuration.
    public static final List<CommandDefinition> COMMAND_CATALOG = List.of(
            new CommandDefinition("DOOR_OPEN", Action.OPEN, List.of(DeviceType.DOOR)),
            new CommandDefinition("DOOR_CLOSE", Action.CLOSE, List.of(DeviceType.DOOR)),
            new CommandDefinition("WINDOW_OPEN", Action.OPEN, List.of(DeviceType.WINDOW)),
            new CommandDefinition("WINDOW_CLOSE", Action.CLOSE, List.of(DeviceType.WINDOW)),
            new CommandDefinition("LIGHT_ON", Action.ON, List.of(DeviceType.LIGHT, DeviceType.PLUG)),
            new CommandDefinition("LIGHT_OFF", Action.OFF, List.of(DeviceType.LIGHT, DeviceType.PLUG)));

    public record DeviceView(String id, String name, String room, SimulationMode simulationMode,
                             State requestedState, State reportedState, Instant lastCommandAt, DeviceType type) { }

    public record CommandResult(String id, Instant timestamp, String commandId, Double confidence,
                                String deviceId, Action action, Outcome outcome, State requestedState,
                                State reportedState, String message, double durationMs, boolean read) {
        public CommandResult withRead(boolean value) {
            return new CommandResult(id, timestamp, commandId, confidence, deviceId, action, outcome,
                    requestedState, reportedState, message, durationMs, value);
        }
    }

    public record Configuration(int schemaVersion, List<Device> devices, List<Mapping> mappings) { }
    public record Settings(boolean simulation, double minimumConfidence, int apiVersion,
                           List<CommandDefinition> commandCatalog) { }
    public record Dashboard(List<DeviceView> devices, List<Mapping> mappings,
                            List<CommandResult> events, Settings settings) { }
}
