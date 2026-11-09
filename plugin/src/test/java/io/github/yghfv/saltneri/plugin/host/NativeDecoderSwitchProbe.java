package io.github.yghfv.saltneri.plugin.host;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit isolated-process probe: synthetic AAC only, BASS no-sound device, no Salt process access. */
public final class NativeDecoderSwitchProbe {
    public interface Bass extends Library {
        boolean BASS_Init(int device, int frequency, int flags, Pointer window, Pointer cls);
        int BASS_ChannelGetData(int handle, Pointer buffer, int length);
        boolean BASS_ChannelSetPosition(int handle, long position, int mode);
        boolean BASS_StreamFree(int handle);
        boolean BASS_Free();
    }
    public interface Mixer extends Library {
        int BASS_Mixer_StreamCreate(int frequency, int channels, int flags);
        boolean BASS_Mixer_StreamAddChannel(int mixer, int channel, int flags);
    }

    public static void main(String[] args) throws Exception {
        Bass bass = Native.load(args[0], Bass.class);
        if (!bass.BASS_Init(0, 44100, 0, null, null)) throw new AssertionError("No-sound device unavailable");
        byte[] media = Files.readAllBytes(Path.of(args[1]));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media/", exchange -> {
            try (exchange) {
                String[] range = exchange.getRequestHeaders().getFirst("Range").substring(6).split("-", -1);
                int first = Integer.parseInt(range[0]);
                int last = range[1].isEmpty() ? media.length - 1 : Math.min(media.length - 1, Integer.parseInt(range[1]));
                exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + media.length);
                exchange.sendResponseHeaders(206, last - first + 1);
                exchange.getResponseBody().write(media, first, last - first + 1);
            }
        });
        server.start();
        try (Memory buffer = new Memory(65536)) {
            for (int iteration = 0; iteration < 8; iteration++) {
                ScopedNativeStreams factory = new ScopedNativeStreams(NativeDecoderSwitchProbe.class.getClassLoader());
                URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media/" + "a".repeat(64));
                Object raw = factory.create(uri, 0x200000);
                kotlin.ResultKt.throwOnFailure(raw);
                int handle = (int)raw;
                try {
                    if (bass.BASS_ChannelGetData(handle, buffer, 65536) <= 0) throw new AssertionError("Initial decode failed");
                    factory.revoke(uri);
                    factory.close();
                    if (!bass.BASS_ChannelSetPosition(handle, 0, 0)) throw new AssertionError("Sealed input seek failed");
                    if (iteration % 2 != 0) {
                        if (bass.BASS_ChannelGetData(handle, buffer, 65536) <= 0) throw new AssertionError("Mid-track decode failed");
                        // finally frees a still-decoding source, like an explicit next-track command.
                        System.out.println("PASS: decoder mid-track free iteration " + (iteration + 1));
                        continue;
                    }
                    int count = 0;
                    long pcm = 0;
                    do {
                        count = bass.BASS_ChannelGetData(handle, buffer, 65536);
                        if (count == 0) throw new AssertionError("Decoder stalled after revoke");
                        if (count > 0) pcm += count;
                    } while (count != -1 && pcm < 64L * 1024 * 1024);
                    if (count != -1 || pcm == 0) throw new AssertionError("Decoder did not reach complete EOF");
                } finally {
                    if (!bass.BASS_StreamFree(handle)) throw new AssertionError("Native free failed");
                    factory.close();
                }
                System.out.println("PASS: decoder revoke/free iteration " + (iteration + 1));
            }
            Mixer mixing = Native.load(Path.of(args[0]).resolveSibling("bassmix.dll").toString(), Mixer.class);
            int mixer = mixing.BASS_Mixer_StreamCreate(44100, 2, 0x200000);
            if (mixer == 0) throw new AssertionError("Decode mixer unavailable");
            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread audio = new Thread(() -> {
                try (Memory output = new Memory(4096)) {
                    while (running.get()) {
                        bass.BASS_ChannelGetData(mixer, output, 4096);
                        java.util.concurrent.locks.LockSupport.parkNanos(1000000);
                    }
                }
            }, "synthetic-no-sound-mixer");
            audio.setDaemon(true);
            audio.start();
            try {
                for (int iteration = 0; iteration < 16; iteration++) {
                    ScopedNativeStreams factory = new ScopedNativeStreams(NativeDecoderSwitchProbe.class.getClassLoader());
                    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media/" + "a".repeat(64));
                    Object raw = factory.create(uri, 0x200000);
                    kotlin.ResultKt.throwOnFailure(raw);
                    int handle = (int)raw;
                    try {
                        if (!mixing.BASS_Mixer_StreamAddChannel(mixer, handle, 0)) throw new AssertionError("Mixer add blocked/failed");
                        Thread.sleep(20);
                        factory.revoke(uri);
                    } finally {
                        if (!bass.BASS_StreamFree(handle)) throw new AssertionError("Mixer source free failed");
                        factory.close();
                    }
                }
            } finally {
                running.set(false);
                audio.join(3000);
                if (audio.isAlive()) throw new AssertionError("Mixer renderer failed to stop");
                bass.BASS_StreamFree(mixer);
            }
            System.out.println("PASS: 16 actual concurrent mixer add/revoke/free switches");
        } finally { server.stop(0); bass.BASS_Free(); }
        System.out.println("PASS: actual BASS/MF revoke/seek/full EOF/mid-track free; synthetic AAC, no audio output");
    }
}
