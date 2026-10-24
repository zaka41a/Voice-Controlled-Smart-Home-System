package de.fhaachen.voicehome.control;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import de.fhaachen.voicehome.device.DeviceGateway;
import de.fhaachen.voicehome.domain.HomeModel.*;
import de.fhaachen.voicehome.storage.ConfigurationStore;

/** Small, serialized control core. It does not depend on HTTP, a UI, or an ML runtime. */
public final class HomeService {
    public static final class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }
    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }

    private final ConfigurationStore store;
    private final DeviceGateway gateway;
    private final double minimumConfidence;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Mapping> mappings = new LinkedHashMap<>();
    private final Map<String, State> requested = new LinkedHashMap<>();
    private final Map<String, Instant> lastCommand = new LinkedHashMap<>();
    private final ArrayDeque<CommandResult> events = new ArrayDeque<>();

    public HomeService(ConfigurationStore store, DeviceGateway gateway, double minimumConfidence) {
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1) {
            throw new IllegalArgumentException("Le seuil de confiance doit être compris entre 0 et 1.");
        }
        this.store = store;
        this.gateway = gateway;
        this.minimumConfidence = minimumConfidence;
        Configuration config = store.load().orElseGet(() -> {
            Configuration initial = new Configuration(1,
                    List.of(new Device("living-room-light", "Lampe du salon", "Salon", SimulationMode.CONFIRMED)),
                    List.of(new Mapping("LIGHT_ON", "living-room-light", Action.ON),
                            new Mapping("LIGHT_OFF", "living-room-light", Action.OFF)));
            store.save(initial);
            return initial;
        });
        if (config.schemaVersion() != 1 || config.devices() == null || config.mappings() == null) {
            throw new IllegalArgumentException("Format de configuration non pris en charge.");
        }
        for (Device device : config.devices()) {
            validateDevice(device);
            if (devices.putIfAbsent(device.id(), device) != null) {
                throw new IllegalArgumentException("Identifiant d’appareil en double dans la configuration.");
            }
        }
        for (Mapping mapping : config.mappings()) {
            validateMapping(mapping);
            if (mappings.putIfAbsent(mapping.commandId(), mapping) != null) {
                throw new IllegalArgumentException("Identifiant de commande en double dans la configuration.");
            }
        }
    }

    public synchronized Dashboard dashboard() {
        return new Dashboard(listDevices(), listMappings(), List.copyOf(events), new Settings(true, minimumConfidence));
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
        Map<String, Device> updated = new LinkedHashMap<>(devices);
        updated.put(device.id(), device);
        persist(updated, mappings);
        devices.put(device.id(), device);
        return view(device);
    }

    public synchronized void deleteDevice(String id) {
        requireDevice(id);
        if (mappings.values().stream().anyMatch(mapping -> mapping.deviceId().equals(id))) {
            throw new Conflict("Supprimez d’abord les associations qui utilisent cet appareil.");
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
            throw new NotFound("Association introuvable.");
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
                    "Un identifiant de commande est nécessaire.", started);
        }
        Double confidence = command.confidence();
        if (confidence == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "Le prototype attend un score de confiance entre 0 et 1.", started);
        }
        if (confidence < minimumConfidence) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "Confiance insuffisante. Aucune action n’a été envoyée.", started);
        }
        Mapping mapping = mappings.get(command.commandId());
        if (mapping == null) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "Commande inconnue ou sans association. Aucune action n’a été envoyée.", started);
        }
        Device device = devices.get(mapping.deviceId());
        if (device == null) {
            return record(command, null, Outcome.REJECTED, State.UNKNOWN,
                    "L’appareil associé n’existe plus. Aucune action n’a été envoyée.", started);
        }
        requested.put(device.id(), State.valueOf(mapping.action().name()));
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
                mapping == null ? State.UNKNOWN : State.valueOf(mapping.action().name()), state, message,
                (System.nanoTime() - started) / 1_000_000.0);
        events.addFirst(result);
        while (events.size() > 100) { events.removeLast(); }
        return result;
    }

    private DeviceView view(Device device) {
        return new DeviceView(device.id(), device.name(), device.room(), device.simulationMode(),
                requested.getOrDefault(device.id(), State.UNKNOWN), gateway.readState(device), lastCommand.get(device.id()));
    }

    private Device requireDevice(String id) {
        Device device = devices.get(id);
        if (device == null) { throw new NotFound("Appareil introuvable."); }
        return device;
    }

    private void validateDevice(Device device) {
        if (device == null || device.id() == null || !device.id().matches("[a-z0-9][a-z0-9-]{0,47}")) {
            throw new IllegalArgumentException("L’identifiant doit contenir 1 à 48 lettres minuscules, chiffres ou tirets.");
        }
        requireText(device.name(), 80, "Le nom de l’appareil");
        requireText(device.room(), 60, "Le nom de la pièce");
        if (device.simulationMode() == null) { throw new IllegalArgumentException("Choisissez un comportement simulé."); }
    }

    private void validateMapping(Mapping mapping) {
        if (mapping == null || mapping.commandId() == null || !mapping.commandId().matches("[A-Z][A-Z0-9_]{0,47}")) {
            throw new IllegalArgumentException("La commande doit utiliser 1 à 48 majuscules, chiffres ou underscores.");
        }
        if (mapping.action() == null) { throw new IllegalArgumentException("Choisissez une action ON ou OFF."); }
        if (!devices.containsKey(mapping.deviceId())) { throw new IllegalArgumentException("Choisissez un appareil existant."); }
    }

    private static void requireText(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " est obligatoire (maximum " + maximum + " caractères).");
        }
    }

    private void persist(Map<String, Device> configuredDevices, Map<String, Mapping> configuredMappings) {
        store.save(new Configuration(1, new ArrayList<>(configuredDevices.values()), new ArrayList<>(configuredMappings.values())));
    }
}
