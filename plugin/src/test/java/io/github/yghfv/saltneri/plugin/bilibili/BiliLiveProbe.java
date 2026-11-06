package io.github.yghfv.saltneri.plugin.bilibili;

import io.github.yghfv.saltneri.plugin.net.RequestScope;

/** Opt-in CLI only. Never registers a listener, touches Salt or prints signed CDN URLs. */
public final class BiliLiveProbe {
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--explicit-public-probe")) {
            System.err.println("Requires --explicit-public-probe followed by a direct Bilibili ID/video URL.");
            System.exit(2); return;
        }
        try (RequestScope scope = new RequestScope(() -> true)) {
            var resolved = new BiliClient().resolve(BiliTarget.parse(args[1]), BiliAudioSelector.Quality.HIGH, "cli-anonymous-probe", scope);
            System.out.println("PASS: verified-TLS anonymous API + single-byte CDN probe; " + resolved.key().stableId());
            System.out.println("Audio: " + resolved.mimeType() + " / " + resolved.codec() + " / " + resolved.bitrateKbps()
                    + " kbps; duration=" + resolved.durationMillis() + "ms; bytes=" + resolved.lease().length());
            System.out.println("No signed URL printed, full audio downloaded, host loaded, queue changed or account accessed.");
        } catch (BiliClient.PlatformRejected denied) {
            System.err.println("STOP: " + denied.getMessage() + ". No retries, login bypass or proxy fallback."); System.exit(3);
        } catch (Exception failure) {
            System.err.println("STOP: anonymous probe failed (" + failure.getClass().getSimpleName() + "). No TLS bypass or signed URL output."); System.exit(4);
        }
    }
}
