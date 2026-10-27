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
                    "The simulated device is unavailable. No action was performed.");
        }
        State target = action.targetState();
        states.put(device.id(), target);
        if (device.simulationMode() == SimulationMode.UNCONFIRMED) {
            return new Actuation(Outcome.UNCONFIRMED, State.UNKNOWN,
                    "Command delivered to the simulator. Device state is unconfirmed.");
        }
        return new Actuation(Outcome.CONFIRMED, target, switch (target) {
            case ON -> "The simulated device is on.";
            case OFF -> "The simulated device is off.";
            case OPEN -> "The simulated device is open.";
            case CLOSED -> "The simulated device is closed.";
            default -> "The simulated device state is unknown.";
        });
    }

    @Override
    public synchronized State readState(Device device) {
        return device.simulationMode() == SimulationMode.CONFIRMED
                ? states.getOrDefault(device.id(), device.type().initialState()) : State.UNKNOWN;
    }

    @Override
    public synchronized void forget(String deviceId) {
        states.remove(deviceId);
    }
}
