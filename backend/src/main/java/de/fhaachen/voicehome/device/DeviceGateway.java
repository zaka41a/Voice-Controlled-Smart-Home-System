package de.fhaachen.voicehome.device;

import de.fhaachen.voicehome.domain.HomeModel.Action;
import de.fhaachen.voicehome.domain.HomeModel.Device;
import de.fhaachen.voicehome.domain.HomeModel.Outcome;
import de.fhaachen.voicehome.domain.HomeModel.State;

/** Replace the simulator here once the hardware protocol has been agreed. */
public interface DeviceGateway {
    record Actuation(Outcome outcome, State reportedState, String message) { }

    Actuation execute(Device device, Action action);
    State readState(Device device);
    default void forget(String deviceId) { }
}
