package de.fhaachen.voicehome.storage;

import java.util.ArrayList;
import java.util.List;
import de.fhaachen.voicehome.domain.HomeModel.CommandResult;

/** Lightweight repository for isolated control tests. The application uses JdbcHomeStore. */
public final class InMemoryEvents implements EventRepository {
    private final List<CommandResult> events = new ArrayList<>();
    @Override public void append(CommandResult event) {
        events.addFirst(event);
        if (events.size() > 100) { events.removeLast(); }
    }
    @Override public List<CommandResult> recent() { return List.copyOf(events); }
    @Override public void markRead(List<String> ids) {
        events.replaceAll(event -> ids.contains(event.id()) ? event.withRead(true) : event);
    }
}
