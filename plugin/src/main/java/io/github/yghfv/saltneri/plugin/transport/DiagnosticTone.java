package io.github.yghfv.saltneri.plugin.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Fixed quiet 2-second PCM fixture. No user files, codecs, accounts or network sources. */
public final class DiagnosticTone {
    private DiagnosticTone() { }
    public static byte[] wave() {
        int rate = 44100;
        int frames = rate * 2;
        ByteBuffer wave = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 2);
        wave.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wave.putShort((short)1).putShort((short)1).putInt(rate).putInt(rate * 2).putShort((short)2).putShort((short)16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 2);
        for (int index = 0; index < frames; index++) {
            double envelope = Math.min(1.0, Math.min(index, frames - 1 - index) / (rate * 0.05));
            wave.putShort((short)Math.round(1600 * envelope * Math.sin(2 * Math.PI * 440 * index / rate)));
        }
        return wave.array();
    }
}
