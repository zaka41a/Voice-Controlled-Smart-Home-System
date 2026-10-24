package de.fhaachen.voicehome.device;

import java.util.HashMap;
import java.util.Map;
import de.fhaachen.voicehome.domain.HomeModel.Action;
import de.fhaachen.voicehome.domain.HomeModel.Device;
import de.fhaachen.voicehome.domain.HomeModel.Outcome;
import de.fhaachen.voicehome.domain.HomeModel.SimulationMode;
import de.fhaachen.voicehome.domain.HomeModel.State;

/** No hardware, speech recognition, network connection, or physical actuation is performed. */
public final class SimulatedDeviceGateway implements DeviceGateway {
    private final Map<String, State> states = new HashMap<>();

    @Override
    public synchronized Actuation execute(Device device, Action action) {
        if (device.simulationMode() == SimulationMode.OFFLINE) {
            return new Actuation(Outcome.FAILED, State.UNKNOWN,
                    "L’appareil simulé est indisponible. Aucune action n’a été effectuée.");
        }
        State target = State.valueOf(action.name());
        states.put(device.id(), target);
        if (device.simulationMode() == SimulationMode.UNCONFIRMED) {
            return new Actuation(Outcome.UNCONFIRMED, State.UNKNOWN,
                    "Commande transmise au simulateur, sans confirmation de l’état.");
        }
        return new Actuation(Outcome.CONFIRMED, target,
                target == State.ON ? "Allumage confirmé par le simulateur." : "Extinction confirmée par le simulateur.");
    }

    @Override
    public synchronized State readState(Device device) {
        return device.simulationMode() == SimulationMode.CONFIRMED
                ? states.getOrDefault(device.id(), State.OFF) : State.UNKNOWN;
    }

    @Override
    public synchronized void forget(String deviceId) {
        states.remove(deviceId);
    }
}
