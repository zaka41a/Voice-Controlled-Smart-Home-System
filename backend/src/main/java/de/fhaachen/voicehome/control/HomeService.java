package de.fhaachen.voicehome.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import de.fhaachen.voicehome.device.DeviceGateway;
import de.fhaachen.voicehome.domain.HomeModel.*;
import static de.fhaachen.voicehome.domain.HomeModel.COMMAND_CATALOG;
import de.fhaachen.voicehome.storage.ConfigurationRepository;
import de.fhaachen.voicehome.storage.EventRepository;
import de.fhaachen.voicehome.storage.InMemoryEvents;

/** Small, serialized control core. It does not depend on HTTP, a UI, or an ML runtime. */
public final class HomeService {
    public static final class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }
    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
    public static final class InvalidInput extends IllegalArgumentException {
        private final String field;
        public InvalidInput(String field, String message) { super(message); this.field = field; }
        public String field() { return field; }
    }

    private final ConfigurationRepository store;
    private final DeviceGateway gateway;
    private final double minimumConfidence;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Mapping> mappings = new LinkedHashMap<>();
    private final Map<String, State> requested = new LinkedHashMap<>();
    private final Map<String, Instant> lastCommand = new LinkedHashMap<>();
    private final EventRepository events;

    public HomeService(ConfigurationRepository store, DeviceGateway gateway, double minimumConfidence) {
        this(store, gateway, minimumConfidence, new InMemoryEvents());
    }

    public HomeService(ConfigurationRepository store, DeviceGateway gateway, double minimumConfidence, EventRepository events) {
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1) {
            throw new IllegalArgumentException("The confidence threshold must be between 0 and 1.");
        }
        this.store = store;
        this.gateway = gateway;
        this.minimumConfidence = minimumConfidence;
        this.events = events;
        Configuration config = store.load().orElseGet(() -> {
            Configuration initial = new Configuration(2,
                    List.of(new Device("living-room-light", "Living room light", "Living room", SimulationMode.CONFIRMED),
                            new Device("front-door", "Front door", "Entrance", SimulationMode.CONFIRMED, DeviceType.DOOR),
                            new Device("living-room-window", "Living room window", "Living room", SimulationMode.CONFIRMED, DeviceType.WINDOW)),
                    List.of(new Mapping("LIGHT_ON", "living-room-light", Action.ON),
                            new Mapping("LIGHT_OFF", "living-room-light", Action.OFF),
                            new Mapping("DOOR_OPEN", "front-door", Action.OPEN),
                            new Mapping("DOOR_CLOSE", "front-door", Action.CLOSE),
                            new Mapping("WINDOW_OPEN", "living-room-window", Action.OPEN),
                            new Mapping("WINDOW_CLOSE", "living-room-window", Action.CLOSE)));
            store.save(initial);
            return initial;
        });
        if ((config.schemaVersion() != 1 && config.schemaVersion() != 2) || config.devices() == null || config.mappings() == null) {
            throw new IllegalArgumentException("Unsupported configuration format.");
        }
        for (Device device : config.devices()) {
            validateDevice(device);
            if (devices.putIfAbsent(device.id(), device) != null) {
                throw new IllegalArgumentException("Duplicate device ID in the configuration.");
            }
        }
        for (Mapping mapping : config.mappings()) {
            validateMapping(mapping);
            if (mappings.putIfAbsent(mapping.commandId(), mapping) != null) {
                throw new IllegalArgumentException("Duplicate command ID in the configuration.");
            }
        }
        if (config.schemaVersion() == 1) {
            addDefaultCommands(DeviceType.LIGHT, "living-room-light", "Living room light", "Living room", "LIGHT", Action.ON, Action.OFF);
            addDefaultCommands(DeviceType.DOOR, "front-door", "Front door", "Entrance", "DOOR", Action.OPEN, Action.CLOSE);
            addDefaultCommands(DeviceType.WINDOW, "living-room-window", "Living room window", "Living room", "WINDOW", Action.OPEN, Action.CLOSE);
            persist(devices, mappings);
        }
    }

    private void addDefaultCommands(DeviceType type, String preferredId, String name, String room, String prefix, Action positive, Action negative) {
        String id = devices.values().stream().filter(device -> device.type() == type || (type == DeviceType.LIGHT && device.type() == DeviceType.PLUG)).map(Device::id).findFirst().orElse(null);
        if (id == null) {
            id = preferredId;
            for (int suffix = 2; devices.containsKey(id); suffix++) { id = preferredId + "-" + suffix; }
            devices.put(id, new Device(id, name, room, SimulationMode.CONFIRMED, type));
        }
        mappings.putIfAbsent(prefix + "_" + positive.name(), new Mapping(prefix + "_" + positive.name(), id, positive));
        mappings.putIfAbsent(prefix + "_" + negative.name(), new Mapping(prefix + "_" + negative.name(), id, negative));
    }

    public synchronized Dashboard dashboard() {
        return new Dashboard(listDevices(), listMappings(), events.recent(), new Settings(true, minimumConfidence, 3, COMMAND_CATALOG));
    }

    public synchronized void markNotificationsRead(List<String> ids) {
        if (ids == null || ids.size() > 100) {
            throw new IllegalArgumentException("Provide up to 100 notification IDs.");
        }
        for (String id : ids) {
            if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw new IllegalArgumentException("Invalid notification ID.");
            }
        }
        events.markRead(ids);
    }

    public synchronized List<DeviceView> listDevices() {
        return devices.values().stream().map(this::view).toList();
    }

    public synchronized DeviceView getDevice(String id) {
        return view(requireDevice(id));
    }

    public synchronized List<Mapping> listMappings() {
        return mappings.values().stream().sorted(Comparator.comparing(Mapping::commandId)).toList();
    }

    public synchronized DeviceView putDevice(Device device) {
        validateDevice(device);
        for (Mapping mapping : mappings.values()) {
            if (mapping.deviceId().equals(device.id())) { validateTarget(mapping, device); }
        }
        Map<String, Device> updated = new LinkedHashMap<>(devices);
        updated.put(device.id(), device);
        persist(updated, mappings);
        Device previous = devices.put(device.id(), device);
        if (previous != null && previous.type() != device.type()) {
            gateway.forget(device.id()); requested.remove(device.id()); lastCommand.remove(device.id());
        }
        return view(device);
    }

    public synchronized void deleteDevice(String id) {
        requireDevice(id);
        if (mappings.values().stream().anyMatch(mapping -> mapping.deviceId().equals(id))) {
            throw new Conflict("Remove the command mappings for this device before deleting it.");
        }
        Map<String, Device> updated = new LinkedHashMap<>(devices);
        updated.remove(id);
        persist(updated, mappings);
        devices.remove(id);
        requested.remove(id);
        lastCommand.remove(id);
        gateway.forget(id);
    }

    public synchronized Mapping putMapping(Mapping mapping) {
        validateMapping(mapping);
        Map<String, Mapping> updated = new LinkedHashMap<>(mappings);
        updated.put(mapping.commandId(), mapping);
        persist(devices, updated);
        mappings.put(mapping.commandId(), mapping);
        return mapping;
    }

    public synchronized void deleteMapping(String id) {
        if (!mappings.containsKey(id)) {
            throw new NotFound("Command mapping not found.");
        }
        Map<String, Mapping> updated = new LinkedHashMap<>(mappings);
        updated.remove(id);
        persist(devices, updated);
        mappings.remove(id);
    }

    public synchronized CommandResult handle(RecognizedCommand command) {
        long started = System.nanoTime();
        if (command == null || command.commandId() == null || command.commandId().isBlank()) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "A command ID is required.", started);
        }
        Double confidence = command.confidence();
        if (confidence == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "A confidence score between 0 and 1 is required.", started);
        }
        if (confidence < minimumConfidence) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "Confidence is too low. No device command was sent.", started);
        }
        Mapping mapping = mappings.get(command.commandId());
        if (mapping == null) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "No mapping exists for this command. No device command was sent.", started);
        }
        Device device = devices.get(mapping.deviceId());
        if (device == null) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "The target device no longer exists. No device command was sent.", started);
        }
        requested.put(device.id(), mapping.action().targetState());
        lastCommand.put(device.id(), Instant.now());
        DeviceGateway.Actuation actuation = gateway.execute(device, mapping.action());
        return record(command, mapping, actuation.outcome(), actuation.reportedState(), actuation.message(), started);
    }

    private CommandResult record(RecognizedCommand command, Mapping mapping, Outcome outcome,
                                 State state, String message, long started) {
        Double score = command == null ? null : command.confidence();
        if (score != null && !Double.isFinite(score)) { score = null; }
        CommandResult result = new CommandResult(UUID.randomUUID().toString(), Instant.now(),
                command == null ? null : command.commandId(), score,
                mapping == null ? null : mapping.deviceId(), mapping == null ? null : mapping.action(), outcome,
                mapping == null ? State.UNKNOWN : mapping.action().targetState(), state, message,
                (System.nanoTime() - started) / 1_000_000.0, false);
        events.append(result);
        return result;
    }

    private DeviceView view(Device device) {
        return new DeviceView(device.id(), device.name(), device.room(), device.simulationMode(),
                requested.getOrDefault(device.id(), State.UNKNOWN), gateway.readState(device), lastCommand.get(device.id()), device.type());
    }

    private Device requireDevice(String id) {
        Device device = devices.get(id);
        if (device == null) { throw new NotFound("Device not found."); }
        return device;
    }

    private void validateDevice(Device device) {
        if (device == null || device.id() == null || !device.id().matches("[a-z0-9][a-z0-9-]{0,47}")) {
            throw new InvalidInput("id", "The device ID must contain 1 to 48 lowercase letters, numbers or hyphens.");
        }
        requireText(device.name(), 80, "name", "Device name");
        requireText(device.room(), 60, "room", "Room name");
        if (device.simulationMode() == null) { throw new InvalidInput("simulationMode", "Select a simulation behavior."); }
    }

    private void validateMapping(Mapping mapping) {
        if (mapping == null || mapping.commandId() == null || !mapping.commandId().matches("[A-Z][A-Z0-9_]{0,47}")) {
            throw new IllegalArgumentException("The command ID must contain 1 to 48 uppercase letters, numbers or underscores.");
        }
        if (mapping.action() == null) { throw new InvalidInput("action", "Select a device action."); }
        if (!devices.containsKey(mapping.deviceId())) { throw new InvalidInput("deviceId", "Select an existing device."); }
        validateTarget(mapping, devices.get(mapping.deviceId()));
    }

    private void validateTarget(Mapping mapping, Device device) {
        if (!device.type().supports(mapping.action())) {
            throw new IllegalArgumentException("This action is not supported by the device type. Update incompatible mappings first.");
        }
        boolean valid = COMMAND_CATALOG.stream().filter(command -> command.id().equals(mapping.commandId()))
                .allMatch(command -> command.types().contains(device.type()) && command.action() == mapping.action());
        if (!valid) { throw new IllegalArgumentException("The selected command, device type and action do not match."); }
    }

    private static void requireText(String value, int maximum, String field, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new InvalidInput(field, label + " is required (maximum " + maximum + " characters).");
        }
    }

    private void persist(Map<String, Device> configuredDevices, Map<String, Mapping> configuredMappings) {
        store.save(new Configuration(2, new ArrayList<>(configuredDevices.values()), new ArrayList<>(configuredMappings.values())));
    }
}
