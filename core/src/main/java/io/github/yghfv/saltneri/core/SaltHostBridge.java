package io.github.yghfv.saltneri.core;

import java.util.Objects;
import java.util.Set;

/** Project-owned port. None of these methods is claimed to be a public Salt API. */
public interface SaltHostBridge {
    enum Capability { ONLINE_QUEUE, STREAM_RESOLUTION, COVER_PUBLICATION, ONLINE_PANEL }

    Set<Capability> capabilities();

    void enqueue(TrackKey key);

    static SaltHostBridge unavailable() {
        return new SaltHostBridge() {
            @Override
            public Set<Capability> capabilities() { return Set.of(); }

            @Override
            public void enqueue(TrackKey key) {
                Objects.requireNonNull(key, "key");
                throw new UnsupportedOperationException("Salt online playback adapter is not verified");
            }
        };
    }
}
