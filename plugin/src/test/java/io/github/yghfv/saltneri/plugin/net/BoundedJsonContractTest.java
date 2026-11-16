package io.github.yghfv.saltneri.plugin.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BoundedJsonContractTest {
    private static int checks;
    private static Object parse(String text) throws IOException { return BoundedJson.parse(text.getBytes(StandardCharsets.UTF_8)); }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception { try { action.run(); throw new AssertionError("Expected rejection"); } catch (IOException expected) { checks++; } }
    public static void main(String[] args) throws Exception {
        var object = BoundedJson.object(parse(" {\"code\":0,\"a\":[true,false,null,1.25e2],\"s\":\"\\u4e2d\\ud83d\\ude00\\n\"} \r\n"));
        check(BoundedJson.integer(object.get("code")) == 0);
        var array = BoundedJson.array(object.get("a"));
        check(array.size() == 4 && array.get(0).equals(true) && array.get(1).equals(false) && array.get(2) == null);
        check(BoundedJson.integer(array.get(3)) == 125);
        check(BoundedJson.string(object.get("s")).equals("\u4e2d\ud83d\ude00\n"));
        check(BoundedJson.integer(parse("9223372036854775807")) == Long.MAX_VALUE);
        check(BoundedJson.integer(parse("-9223372036854775808")) == Long.MIN_VALUE);
        for (String text : java.util.List.of("", "{", "[", "01", "-", "1.", "1e", "1e+", ".1", "NaN", "Infinity", "+1",
                "[1,]", "{\"x\":1,}", "{\"x\":null,\"x\":2}", "true false", "\"\n\"", "\"\\x\"", "\"\\uqqqq\"", "\"\\ud800\"", "\"\\udc00\"",
                "\"\\ud800a\"", "\"\\u\uff10\uff10\uff10\uff10\"", "[".repeat(34) + "0" + "]".repeat(34), "\"" + "x".repeat(65537) + "\"",
                "[" + "0,".repeat(100000) + "0]", "1".repeat(129))) fails(() -> parse(text));
        fails(() -> BoundedJson.parse(new byte[]{(byte)0xc3, 0x28}));
        fails(() -> BoundedJson.parse(new byte[BoundedJson.MAX_BYTES + 1]));
        fails(() -> BoundedJson.integer(parse("9223372036854775808")));
        fails(() -> BoundedJson.integer(parse("1.1")));
        fails(() -> BoundedJson.integer(parse("\"1\"")));
        fails(() -> BoundedJson.array(parse("{}"))); fails(() -> BoundedJson.object(parse("[]")));
        for(String number:java.util.List.of("-1","1731000000.25","1E+100","0.0001")) {
            Object value=parse(number);
            check(BoundedJson.parse(BoundedJson.encode(value)).equals(value));
        }
        fails(()->BoundedJson.encode(new java.math.BigDecimal("1E+1000000")));
        fails(()->BoundedJson.encode(new java.math.BigDecimal("1E-1000000")));
        fails(()->BoundedJson.encode(new java.math.BigDecimal("1".repeat(129))));
        String radar="{\"playlist\":{\"tracks\":[{\"id\":1,\"alg\":null,\"alg\":\"radar\"}]}}";
        fails(()->parse(radar));
        fails(()->BoundedJson.parseNeteaseRecommendations(radar.getBytes(StandardCharsets.UTF_8)));
        var playlist=BoundedJson.object(BoundedJson.object(BoundedJson.parseNeteasePlaylist(radar.getBytes(StandardCharsets.UTF_8))).get("playlist"));
        check(BoundedJson.object(BoundedJson.array(playlist.get("tracks")).getFirst()).get("alg").equals("radar"));
        for(String invalid:java.util.List.of(
                "{\"alg\":null,\"alg\":\"radar\"}",
                "{\"playlist\":{\"alg\":null,\"alg\":\"radar\"}}",
                "{\"data\":{\"playlist\":{\"tracks\":[{\"alg\":null,\"alg\":\"radar\"}]}}}",
                "{\"playlist\":{\"trackIds\":[{\"alg\":null,\"alg\":\"radar\"}]}}",
                "{\"playlist\":{\"tracks\":[{\"al\":{\"alg\":null,\"alg\":\"radar\"}}]}}",
                radar.replace("\"alg\":null","\"alg\":\"first\""),
                radar.replace("\"alg\":\"radar\"","\"alg\":null"),
                radar.replace("\"alg\":\"radar\"","\"alg\":1"),
                radar.replace("\"alg\":\"radar\"","\"alg\":\"radar\",\"alg\":\"third\""),
                radar.replace("\"id\":1","\"id\":1,\"id\":1"))) {
            fails(()->BoundedJson.parseNeteasePlaylist(invalid.getBytes(StandardCharsets.UTF_8)));
        }
        fails(()->BoundedJson.parseNeteasePlaylist(new byte[BoundedJson.MAX_BYTES+1]));
        fails(()->BoundedJson.parseNeteasePlaylist(("[".repeat(34)+"0"+"]".repeat(34)).getBytes(StandardCharsets.UTF_8)));
        System.out.println("PASS: " + checks + " bounded JSON checks");
    }
}
