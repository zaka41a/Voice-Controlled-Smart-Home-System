package de.fhaachen.voicehome.domain;

import java.time.Instant;
import java.util.List;

/** Transport-neutral command IDs and explicit actions form the first integration boundary. */
public final class HomeModel {
    private HomeModel() { }

    public enum Action { ON, OFF }
    public enum State { ON, OFF, UNKNOWN }
    public enum SimulationMode { CONFIRMED, UNCONFIRMED, OFFLINE }
    public enum Outcome { CONFIRMED, REJECTED, FAILED, UNCONFIRMED }

    public record Device(String id, String name, String room, SimulationMode simulationMode) { }
    public record Mapping(String commandId, String deviceId, Action action) { }
    public record RecognizedCommand(String commandId, Double confidence) { }

    public record DeviceView(String id, String name, String room, SimulationMode simulationMode,
                             State requestedState, State reportedState, Instant lastCommandAt) { }

    public record CommandResult(String id, Instant timestamp, String commandId, Double confidence,
                                String deviceId, Action action, Outcome outcome, State requestedState,
                                State reportedState, String message, double durationMs) { }

    public record Configuration(int schemaVersion, List<Device> devices, List<Mapping> mappings) { }
    public record Settings(boolean simulation, double minimumConfidence) { }
    public record Dashboard(List<DeviceView> devices, List<Mapping> mappings,
                            List<CommandResult> events, Settings settings) { }
}
