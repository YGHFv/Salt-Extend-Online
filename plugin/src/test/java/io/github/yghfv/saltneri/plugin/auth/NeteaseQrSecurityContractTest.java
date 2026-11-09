package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline fixtures only: official device context, session binding and fail-closed risk handling. */
public final class NeteaseQrSecurityContractTest {
    private static final Clock CLOCK = AuthProtocolContractTest.CLOCK;
    private static final String KEY = "abcdefghijklmnop0123456789abcdef";
    private static final String UA = AuthHttp.DEFAULT_USER_AGENT + " Edg/150.0.0.0";
    private static final String COOKIE = "{\"name\":\"sDeviceId\",\"value\":\"fixture-device\",\"domain\":\".music.163.com\",\"path\":\"/\",\"secure\":true,\"session\":true}";
    private static final String SNAPSHOT = "{\"token\":\"SYNTHETIC_DEVICE_TOKEN\",\"deviceId\":\"fixture-device\",\"userAgent\":\"" + UA + "\",\"cookies\":[" + COOKIE + "]}";
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("QR security check " + checks); }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException | CancellationException expected) {
            check(!String.valueOf(expected.getMessage()).contains("SYNTHETIC") && expected.getCause() == null);
        }
    }
    private static NeteaseDeviceSnapshot read(String json) throws IOException { return NeteaseDeviceSnapshot.read(json.getBytes(StandardCharsets.UTF_8), CLOCK); }
    private static AuthProtocolContractTest.Response response(URI uri, String json) throws IOException {
        return new AuthProtocolContractTest.Response(uri, json, List.of(), null);
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(condition.getAsBoolean());
    }
    public static void main(String[] args) throws Exception {
        var snapshot = read(SNAPSHOT);
        check(snapshot.deviceId().equals("fixture-device") && snapshot.userAgent().equals(UA));
        check(!snapshot.toString().contains("TOKEN") && !snapshot.cookies().toString().contains("fixture-device"));
        for (String bad : List.of(
                SNAPSHOT.replace("SYNTHETIC_DEVICE_TOKEN", ""), SNAPSHOT.replace("SYNTHETIC_DEVICE_TOKEN", "bad\\nSYNTHETIC"),
                SNAPSHOT.replace("SYNTHETIC_DEVICE_TOKEN", "x".repeat(4097)), SNAPSHOT.replace("fixture-device", "bad%20id"),
                SNAPSHOT.replace("\"deviceId\":\"fixture-device\"", "\"deviceId\":\"different\""),
                SNAPSHOT.replace("\"deviceId\":\"fixture-device\"", "\"deviceId\":\"" + "x".repeat(129) + "\""),
                SNAPSHOT.replace(UA, "SYNTHETIC_UA"), SNAPSHOT.replace(UA, UA + "\\r\\nInjected: SYNTHETIC"),
                SNAPSHOT.replace(UA, UA + "x".repeat(512)), SNAPSHOT.replace(".music.163.com", ".163.com"),
                SNAPSHOT.replace(".music.163.com", ".music.163.com.evil"), SNAPSHOT.replace("\"/\"", "\"/unrelated\""),
                SNAPSHOT.replace(COOKIE, COOKIE + "," + COOKIE),
                SNAPSHOT.replace(COOKIE, COOKIE + "," + COOKIE.replace("sDeviceId", "MUSIC_U")),
                SNAPSHOT.replace("\"session\":true", "\"session\":false,\"expires\":1"),
                "{\"error\":\"SYNTHETIC_ERROR\"}", "SYNTHETIC_INVALID_JSON", "x".repeat(65537))) rejected(() -> read(bad));

        for (String badId : List.of("bad%", "bad%2", "bad%ZZ", "bad%25id", "bad%252Bid", "bad%0Did", "bad%0aid",
                "bad%09id", "bad%3Bid", "bad%2Cid", "bad%22id", "bad%5Cid", "bad%26id", "bad%3Fid",
                "bad%23id", "bad%00id", "bad%7Fid", "bad%FFid", "bad id", "bad;id", "bad,id", "bad&id"))
            rejected(() -> read(SNAPSHOT.replace("fixture-device", badId)));
        for (String id : List.of("fixture%2Bdevice%2Fpart%3D", "fixture%2bdevice%2fpart%3d", "fixture+device/part=",
                "x".repeat(128), "x".repeat(125) + "%2B")) {
            var escaped = read(SNAPSHOT.replace("fixture-device", id));
            check(escaped.deviceId().equals(id));
            var exchanges = new ArrayList<AuthProtocolContractTest.Response>();
            var fake = new AuthHttp((uri, body) -> {
                var reply = response(uri, uri.getPath().endsWith("unikey") ? "{\"code\":200,\"unikey\":\"" + KEY + "\"}"
                        : "{\"code\":801}");
                exchanges.add(reply); return reply;
            });
            try (var client = new NeteaseQrClient(CLOCK, fake, r -> escaped); var request = new RequestScope(() -> true)) {
                var session = client.create(request);
                String chain = "v1_" + id + "_web_login_" + CLOCK.millis();
                check(session.chainId().equals(chain));
                String rawChain = Arrays.stream(session.qrContent().getRawQuery().split("&"))
                        .filter(p -> p.startsWith("chainId=")).findFirst().orElseThrow().substring(8);
                check(rawChain.equals(URLEncoder.encode(chain, StandardCharsets.UTF_8)));
                check(URLDecoder.decode(rawChain, StandardCharsets.UTF_8).equals(chain));
                check(client.poll(session, request).state() == QrAuthClient.State.WAITING_SCAN);
                check(exchanges.size() == 2 && exchanges.stream().allMatch(r ->
                        r.getRequestProperty("Cookie").contains("sDeviceId=" + id)));
                check(chain.equals(exchanges.getLast().getRequestProperty("x-login-chain-id")));
            }
        }

        var calls = new ArrayList<AuthProtocolContractTest.Response>(); var preparations = new AtomicInteger(); var polls = new AtomicInteger();
        var http = new AuthHttp((uri, body) -> {
            check(preparations.get() == 1);
            AuthProtocolContractTest.Response response;
            if (uri.getPath().endsWith("unikey")) response = response(uri, "{\"code\":200,\"unikey\":\"" + KEY + "\"}");
            else if (uri.getPath().endsWith("login")) {
                int code = new int[]{801, 802, 803}[polls.getAndIncrement()];
                response = new AuthProtocolContractTest.Response(uri, "{\"code\":" + code + "}", List.of(), code == 803 ? "SYNTHETIC_CREDENTIAL" : null);
            } else response = response(uri, "{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":42}}");
            calls.add(response); return response;
        });
        try (var client = new NeteaseQrClient(CLOCK, http, request -> { preparations.incrementAndGet(); return snapshot; }); var request = new RequestScope(() -> true)) {
            var session = client.create(request);
            check(session.chainId().equals("v1_fixture-device_web_login_" + CLOCK.millis()));
            check(session.qrContent().getQuery().contains("chainId=" + session.chainId()));
            check(!session.toString().contains(KEY));
            check(NeteaseQrClient.pollPayload(session, snapshot).get("ydDeviceToken").equals("SYNTHETIC_DEVICE_TOKEN"));
            check(client.poll(session, request).state() == QrAuthClient.State.WAITING_SCAN);
            check(client.poll(session, request).state() == QrAuthClient.State.WAITING_CONFIRMATION);
            var result = client.poll(session, request);
            check(result.credentials().userId().equals("42")); result.credentials().retire();
            check(calls.size() == 5 && calls.stream().allMatch(r -> r.disconnected && UA.equals(r.getRequestProperty("User-Agent"))
                    && r.getRequestProperty("Cookie").contains("sDeviceId=fixture-device")));
            check(calls.subList(1, 4).stream().allMatch(r -> session.chainId().equals(r.getRequestProperty("x-login-chain-id"))));
            rejected(() -> client.poll(session, request)); check(calls.size() == 5);
        }

        // A reconstructed, cross-client or previously closed session must not reach HTTP.
        for (int mode = 0; mode < 3; mode++) {
            var count = new AtomicInteger();
            var fake = new AuthHttp((uri, body) -> { count.incrementAndGet(); return response(uri, "{\"code\":200,\"unikey\":\"" + KEY + "\"}"); });
            try (var first = new NeteaseQrClient(CLOCK, fake, r -> snapshot); var second = new NeteaseQrClient(CLOCK, fake, r -> snapshot);
                 var request = new RequestScope(() -> true); var other = new RequestScope(() -> true)) {
                var session = first.create(request);
                if (mode == 0) rejected(() -> first.poll(new QrAuthClient.Session(session.key(), session.qrContent(), session.chainId(), session.expiresAt()), request));
                if (mode == 1) rejected(() -> second.poll(session, other));
                if (mode == 2) { first.close(); rejected(() -> first.poll(session, request)); rejected(() -> first.create(other)); }
                check(count.get() == 1);
            }
        }
        var now = new java.util.concurrent.atomic.AtomicReference<>(CLOCK.instant());
        Clock mutable = new Clock() { public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now.get(); } };
        var expiryCalls = new AtomicInteger();
        try (var client = new NeteaseQrClient(mutable, new AuthHttp((uri, body) -> {
            expiryCalls.incrementAndGet(); return response(uri, "{\"code\":200,\"unikey\":\"" + KEY + "\"}");
        }), r -> snapshot); var request = new RequestScope(() -> true)) {
            var session = client.create(request); now.set(CLOCK.instant().plusSeconds(180));
            check(client.poll(session, request).state() == QrAuthClient.State.EXPIRED);
            rejected(() -> client.poll(session, request)); check(expiryCalls.get() == 1);
        }

        for (long code : new long[]{8821, -462}) {
            var count = new AtomicInteger();
            try (var controller = new AccountLoginController(AccountPlatform.NETEASE, () -> new NeteaseQrClient(CLOCK, new AuthHttp((uri, body) -> {
                count.incrementAndGet(); return response(uri, uri.getPath().endsWith("unikey") ? "{\"code\":200,\"unikey\":\"" + KEY + "\"}"
                        : "{\"code\":" + code + ",\"message\":\"SYNTHETIC_SECRET\"}");
            }), r -> snapshot), () -> true, null, () -> {}, CLOCK, 10)) {
                controller.start(false); await(() -> controller.snapshot().phase() == AccountLoginController.Phase.VERIFICATION_REQUIRED);
                check(controller.credentials() == null && controller.snapshot().qrContent() == null && !controller.snapshot().status().contains("SYNTHETIC"));
                Thread.sleep(100); check(count.get() == 2);
            }
        }
        var noHttp = new AtomicInteger(); var attempts = new AtomicInteger();
        // A QR confirmation alone is never sufficient, including refresh-token responses.
        for (int mode = 0; mode < 5; mode++) {
            int selected = mode; var count = new AtomicInteger();
            try (var client = new NeteaseQrClient(CLOCK, new AuthHttp((uri, body) -> {
                count.incrementAndGet();
                if (uri.getPath().endsWith("unikey")) return response(uri, "{\"code\":200,\"unikey\":\"" + KEY + "\"}");
                if (uri.getPath().endsWith("login")) return new AuthProtocolContractTest.Response(uri, "{\"code\":803}", List.of(), selected == 0 ? null : "SYNTHETIC_CREDENTIAL");
                return response(uri, switch (selected) {
                    case 1 -> "{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":43}}";
                    case 2 -> "{\"code\":8821,\"message\":\"SYNTHETIC_SECRET\"}";
                    case 3 -> "{\"code\":500,\"message\":\"SYNTHETIC_SECRET\"}";
                    default -> "{\"code\":200,\"account\":null,\"profile\":null}";
                });
            }), r -> snapshot); var request = new RequestScope(() -> true)) {
                var session = client.create(request); rejected(() -> client.poll(session, request));
                rejected(() -> client.poll(session, request)); check(count.get() == (selected == 0 ? 2 : 3));
            }
        }
        try (var controller = new AccountLoginController(AccountPlatform.NETEASE, () -> new NeteaseQrClient(CLOCK,
                new AuthHttp((uri, body) -> { noHttp.incrementAndGet(); throw new IOException("SYNTHETIC"); }),
                r -> { attempts.incrementAndGet(); throw new IOException("SYNTHETIC_TOKEN"); }), () -> true, null, () -> {}, CLOCK, 10)) {
            controller.start(false); await(() -> controller.snapshot().phase() == AccountLoginController.Phase.VERIFICATION_REQUIRED);
            Thread.sleep(100); check(noHttp.get() == 0 && attempts.get() == 1 && controller.snapshot().qrContent() == null);
            check(controller.snapshot().status().contains("Microsoft Edge") && !controller.snapshot().status().contains("SYNTHETIC"));
        }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var controller = new AccountLoginController(AccountPlatform.NETEASE, () -> new NeteaseQrClient(CLOCK,
                new AuthHttp((uri, body) -> { noHttp.incrementAndGet(); throw new IOException("SYNTHETIC"); }), r -> {
                    entered.countDown(); for (;;) { try { release.await(); break; } catch (InterruptedException ignored) { } } return snapshot;
                }), () -> true, null, () -> {}, CLOCK, 10)) {
            controller.start(false); check(entered.await(2, TimeUnit.SECONDS)); controller.cancelLogin(); release.countDown(); Thread.sleep(100);
            check(controller.snapshot().phase() == AccountLoginController.Phase.IDLE && noHttp.get() == 0 && controller.credentials() == null);
        } finally { release.countDown(); }
        var headers = new AuthHttp((uri, body) -> { throw new AssertionError("Invalid header reached HTTP"); });
        try (var request = new RequestScope(() -> true)) {
            var jar = new ScopedCookies(AccountPlatform.NETEASE, CLOCK); URI target = URI.create("https://music.163.com/weapi/login/qrcode/client/login");
            for (var extra : List.of(Map.of("x-login-chain-id", "bad\r\nSYNTHETIC"),
                    Map.of("x-login-chain-id", "bad%0D%0ASYNTHETIC"), Map.of("x-login-chain-id", "bad%252Bid"),
                    Map.of("x-login-chain-id", "bad%20id"), Map.of("x-login-chain-id", "x".repeat(257)),
                    Map.of("x-loginmethod", "other"), Map.of("Cookie", "SYNTHETIC")))
                rejected(() -> headers.exchange(target, new byte[0], jar, extra, request));
            rejected(() -> headers.exchange(target, new byte[0], jar, Map.of(), UA + "\r\nSYNTHETIC", request));
        }
        System.out.println("PASS: " + checks + " NetEase device-context/QR binding/risk-stop/cancellation/redaction checks; synthetic only");
    }
}
