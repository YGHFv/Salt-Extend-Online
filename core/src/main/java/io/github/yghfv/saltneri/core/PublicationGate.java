package io.github.yghfv.saltneri.core;

import java.util.Objects;

/** Guards publication across song changes, account changes and plugin stop/start. */
public final class PublicationGate {
    public record Ticket(long generation, TrackKey track, String accountScope, long revision) {}

    private long generation;
    private Ticket current;

    public synchronized Ticket begin(TrackKey track, String accountScope, long revision) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(accountScope, "accountScope");
        current = new Ticket(++generation, track, accountScope, revision);
        return current;
    }

    public synchronized void invalidate() {
        generation++;
        current = null;
    }

    /** The publisher must be a short in-memory update, not network or UI dispatch. */
    public synchronized boolean publish(Ticket ticket, Runnable publisher) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(publisher, "publisher");
        if (!ticket.equals(current)) return false;
        publisher.run();
        return true;
    }
}
