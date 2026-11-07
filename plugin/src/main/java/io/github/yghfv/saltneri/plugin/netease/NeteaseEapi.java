package io.github.yghfv.saltneri.plugin.netease;

/* EAPI framing adapted from pinned NeriPlayer NeteaseCrypto. GPL-3.0-or-later. */

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class NeteaseEapi {
    public static final String PATH = "/eapi/song/enhance/player/url/v1";
    public static final String LYRIC_PATH = "/eapi/song/lyric/v1";
    private NeteaseEapi() { }
    public static byte[] form(Map<String, Object> payload) throws IOException {
        return form(PATH, payload);
    }
    public static byte[] form(String endpoint, Map<String, Object> payload) throws IOException {
        if (!PATH.equals(endpoint) && !LYRIC_PATH.equals(endpoint)) throw new IOException("Invalid EAPI endpoint");
        byte[] json = BoundedJson.encode(payload); if (json.length > 8192) throw new IOException("EAPI payload too large");
        String data = new String(json, StandardCharsets.UTF_8), path = endpoint.replace("/eapi", "/api");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(
                    ("nobody" + path + "use" + data + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
            String message = path + "-36cd479b6b5-" + data + "-36cd479b6b5-" + digest;
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII), "AES"));
            return ("params=" + HexFormat.of().withUpperCase().formatHex(cipher.doFinal(message.getBytes(StandardCharsets.UTF_8)))).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException failure) { throw new IOException("EAPI encoding unavailable"); }
    }
}
