package io.github.yghfv.saltneri.plugin.transport;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Account-owned decoded audio. No URL or upstream headers are accepted by this port. */
public interface DecodedAudioSource extends AutoCloseable {
    long length();
    byte[] read(ByteRange range, BooleanSupplier active) throws IOException;
    @Override void close();
}
