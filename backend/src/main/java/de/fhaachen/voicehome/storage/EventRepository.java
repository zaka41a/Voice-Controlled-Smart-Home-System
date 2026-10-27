package de.fhaachen.voicehome.storage;

import java.util.List;
import de.fhaachen.voicehome.domain.HomeModel.CommandResult;

public interface EventRepository {
    void append(CommandResult event);
    List<CommandResult> recent();
    void markRead(List<String> ids);
}
