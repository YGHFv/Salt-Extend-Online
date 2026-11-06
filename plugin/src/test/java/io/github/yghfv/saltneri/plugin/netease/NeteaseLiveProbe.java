package io.github.yghfv.saltneri.plugin.netease;

import io.github.yghfv.saltneri.plugin.net.RequestScope;

/** Explicit public CLI probe only; never opens Salt, a browser, a real account or a full download. */
public final class NeteaseLiveProbe {
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--explicit-public-probe")) {
            System.err.println("Requires --explicit-public-probe followed by a NetEase song ID/official song URL."); System.exit(2); return;
        }
        try (RequestScope request = new RequestScope(() -> true)) {
            var result = new NeteaseClient().resolve(NeteaseTarget.parse(args[1]), NeteaseClient.Quality.STANDARD, "cli-anonymous-probe", request);
            System.out.println("PASS: verified-TLS anonymous NetEase API + single-byte CDN probe; " + result.key().stableId());
            System.out.println("Audio: " + result.mimeType() + " / " + result.level() + " / " + result.bitrateKbps()
                    + " kbps; duration=" + result.durationMillis() + "ms; bytes=" + result.lease().length());
            System.out.println("No signed URL printed, full audio downloaded, host loaded, queue changed or account accessed.");
        } catch (NeteaseClient.PlatformRejected denied) {
            System.err.println("STOP: " + denied.getMessage() + ". No retry, login bypass or proxy fallback."); System.exit(3);
        } catch (Exception failure) {
            System.err.println("STOP: anonymous probe failed (" + failure.getClass().getSimpleName() + "). No TLS bypass or signed URL output."); System.exit(4);
        }
    }
}
