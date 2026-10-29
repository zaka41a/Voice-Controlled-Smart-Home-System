package de.fhaachen.voicehome.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import de.fhaachen.voicehome.control.HomeService;
import de.fhaachen.voicehome.domain.HomeModel.*;
import de.fhaachen.voicehome.domain.HomeModel.Mapping;

@RestController
@RequestMapping("/api")
public class HomeController {
    public record DeviceInput(String name, String room, SimulationMode simulationMode, DeviceType type) { }
    public record MappingInput(String deviceId, Action action) { }
    public record ReadNotificationsInput(List<String> ids) { }
    private final HomeService service;

    public HomeController(HomeService service) { this.service = service; }

    @GetMapping("/dashboard")
    public Dashboard dashboard() { return service.dashboard(); }

    @GetMapping("/devices")
    public List<DeviceView> devices() { return service.listDevices(); }

    @GetMapping("/devices/{id}")
    public DeviceView device(@PathVariable String id) { return service.getDevice(id); }

    @PutMapping("/devices/{id}")
    public DeviceView configureDevice(@PathVariable String id, @RequestBody DeviceInput input) {
        if (input.type() == null) { throw new HomeService.InvalidInput("type", "Select a device type."); }
        return service.putDevice(new Device(id, input.name(), input.room(), input.simulationMode(), input.type()));
    }

    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDevice(@PathVariable String id) { service.deleteDevice(id); }

    @GetMapping("/mappings")
    public List<Mapping> mappings() { return service.listMappings(); }

    @PutMapping("/mappings/{commandId}")
    public Mapping configureMapping(@PathVariable String commandId, @RequestBody MappingInput input) {
        return service.putMapping(new Mapping(commandId, input.deviceId(), input.action()));
    }

    @DeleteMapping("/mappings/{commandId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMapping(@PathVariable String commandId) { service.deleteMapping(commandId); }

    @PostMapping("/commands")
    public CommandResult command(@RequestBody RecognizedCommand command) { return service.handle(command); }

    @PostMapping("/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@RequestBody ReadNotificationsInput input) { service.markNotificationsRead(input.ids()); }
}
