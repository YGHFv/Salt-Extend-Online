package io.github.yghfv.saltneri.plugin.auth;

/* WEAPI AES/RSA wire encoding ported from NeriPlayer NeteaseCrypto.
 * Copyright (C) 2025-2025 NeriPlayer developers. GPL-3.0-or-later; see THIRD_PARTY_NOTICES.md. */

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class NeteaseWeapi {
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String PEM = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFb"
            + "t7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZ"
            + "MldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final SecureRandom RANDOM = new SecureRandom();
    private NeteaseWeapi() { }
    public static byte[] form(Map<String, Object> payload) throws IOException {
        StringBuilder key = new StringBuilder(); for (int i = 0; i < 16; i++) key.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        return form(payload, key.toString());
    }
    static byte[] form(Map<String, Object> payload, String secret) throws IOException {
        if (!secret.matches("[a-zA-Z0-9]{16}")) throw new IOException("Invalid WEAPI key");
        byte[] json = BoundedJson.encode(payload);
        try {
            if (json.length > 8192) throw new IOException("WEAPI payload too large");
            String inner = aes(json, "0CoJUm6Qyw8W8jud");
            String params = aes(inner.getBytes(StandardCharsets.US_ASCII), secret);
            RSAPublicKey key = (RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PEM)));
            BigInteger value = new BigInteger(1, new StringBuilder(secret).reverse().toString().getBytes(StandardCharsets.US_ASCII));
            String hex = value.modPow(key.getPublicExponent(), key.getModulus()).toString(16);
            int width = ((key.getModulus().bitLength() + 7) / 8) * 2; hex = "0".repeat(width - hex.length()) + hex;
            return ("params=" + URLEncoder.encode(params, StandardCharsets.UTF_8) + "&encSecKey=" + hex).getBytes(StandardCharsets.US_ASCII);
        } catch (java.security.GeneralSecurityException error) { throw new IOException("WEAPI encryption unavailable"); }
        finally { Arrays.fill(json, (byte)0); }
    }
    private static String aes(byte[] bytes, String key) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"), new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.US_ASCII)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(bytes));
    }
}
