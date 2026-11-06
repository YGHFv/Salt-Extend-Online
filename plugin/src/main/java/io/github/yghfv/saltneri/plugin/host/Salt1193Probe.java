package io.github.yghfv.saltneri.plugin.host;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/** Passive profile check: never reads controller singletons or changes native state. */
public final class Salt1193Probe {
    private static final String RESOURCE = "/host-profiles/windows-steam-1.19.3.properties";
    private static final String ITEM = "com.xuncorp.pisces.PiscesMediaItem";
    private static final String CONTROLLER = "com.xuncorp.voxzen.service.PlaybackController";
    private static final String INTERCEPTOR = "androidx.compose.ui.\u015b";
    private static final String STREAM = "com.xuncorp.voxzen.cast.HttpRangeBassStream";
    private static final String OWNER = "com.xuncorp.voxzen.cast.HttpRangeStreamOwner";
    private static final String TAGS = "com.xuncorp.voxzen.tag.TagParser";

    public record Result(boolean matched, String detail, MediaFactory mediaFactory) {}

    /** Validated constructor mapping only; creating an item does not enqueue it. */
    public static final class MediaFactory {
        private final Constructor<?> constructor;
        private final Method[] getters;

        private MediaFactory(Constructor<?> constructor, Method[] getters) {
            this.constructor = constructor;
            this.getters = getters.clone();
        }

        public Object create(String id, String title, String artist, String album, String albumArtist, String path)
                throws ReflectiveOperationException {
            return constructor.newInstance(id, title, artist, album, albumArtist, path);
        }

        public String[] read(Object item) throws ReflectiveOperationException {
            String[] values = new String[getters.length];
            for (int i = 0; i < getters.length; i++) values[i] = (String) getters[i].invoke(item);
            return values;
        }
    }

    private Salt1193Probe() {}

    public static Result inspect(ClassLoader hostLoader, String version, String channel) {
        if (!"1.19.3".equals(version) || !"Steam".equals(channel)) {
            return new Result(false, "No profile for " + channel + " " + version, null);
        }
        try {
            Properties profile = new Properties();
            try (InputStream input = Salt1193Probe.class.getResourceAsStream(RESOURCE)) {
                if (input == null) throw new IOException("Missing host fingerprint resource");
                profile.load(input);
            }
            Map<String, Class<?>> classes = new HashMap<>();
            int verified = 0;
            for (String key : profile.stringPropertyNames()) {
                if (!key.startsWith("class.")) continue;
                String name = key.substring(6);
                String hash;
                try (InputStream input = hostLoader.getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new IOException("Missing host class " + name);
                    hash = digest(input);
                }
                if (!hash.equals(profile.getProperty(key))) {
                    return new Result(false, "Host fingerprint changed: " + name, null);
                }
                // Initialization would start the audio service for PlaybackController.
                classes.put(name, Class.forName(name, false, hostLoader));
                verified++;
            }
            if (verified != 17) throw new IOException("Incomplete host profile");
            Class<?> item = classes.get(ITEM);
            Constructor<?> constructor = item.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, String.class);
            String[] names = {"\u037f", "\u0528", "\u0529", "\u052a", "\u052b", "\u052c"};
            Method[] getters = new Method[names.length];
            for (int i = 0; i < names.length; i++) getters[i] = method(item, names[i], String.class);
            method(classes.get(CONTROLLER), "setPlaybackQueue", void.class, java.util.List.class, int.class);
            method(classes.get(CONTROLLER), "addToPlay", void.class, item);
            method(classes.get(CONTROLLER), "play", void.class);
            method(classes.get(CONTROLLER), "refreshCurrentCover", void.class);
            Class<?> service = classes.get("com.xuncorp.voxzen.service.PlaybackService");
            Class<?> simple = classes.get("androidx.compose.ui.\u0aae");
            Class<?> player = classes.get("com.xuncorp.pisces.PiscesPlayer");
            if (service.getSuperclass() != simple) throw new IllegalArgumentException("Player service superclass changed");
            method(simple, "getPlayer", player);
            var serviceField = classes.get(CONTROLLER).getDeclaredField("service");
            if (serviceField.getType() != service || !Modifier.isStatic(serviceField.getModifiers())
                    || !Modifier.isFinal(serviceField.getModifiers())) throw new IllegalArgumentException("Controller service changed");
            var playerField = simple.getDeclaredField("player");
            if (playerField.getType() != player || !Modifier.isFinal(playerField.getModifiers())) {
                throw new IllegalArgumentException("Player lifetime changed");
            }
            Method onPlayerSet = service.getDeclaredMethod("onPlayerSet", player);
            if (onPlayerSet.getReturnType() != void.class || Modifier.isStatic(onPlayerSet.getModifiers())
                    || Modifier.isAbstract(onPlayerSet.getModifiers()) || Modifier.isNative(onPlayerSet.getModifiers())) {
                throw new IllegalArgumentException("Unsupported player callback");
            }
            Class<?> interceptor = classes.get(INTERCEPTOR);
            if (!interceptor.isInterface()) throw new IllegalArgumentException("Stream interceptor is not an interface");
            method(interceptor, "createStream-_2kQ-ls", Object.class,
                    Class.forName("com.xuncorp.pisces.PlatformContext", false, hostLoader), item,
                    long.class, long.class, int.class,
                    Class.forName("kotlin.coroutines.Continuation", false, hostLoader));
            method(classes.get(STREAM), "create-0E7RQCE", Object.class, String.class, int.class, classes.get(OWNER));
            method(classes.get(TAGS), "readImage-0E7RQCE", Object.class, String.class, int.class, int.class);
            method(classes.get(TAGS), "readImageToByteArray", byte[].class, classes.get("com.xuncorp.spc.v.Vri"));
            method(classes.get(TAGS), "readImageToByteArrayOrThrow$Voxzen_composeApp", byte[].class,
                    classes.get("com.xuncorp.spc.v.Vri"));
            return new Result(true, "Steam 1.19.3: 17 class fingerprints and native entry signatures match; "
                    + "online playback is not connected", new MediaFactory(constructor, getters));
        } catch (ReflectiveOperationException | IOException | LinkageError | IllegalArgumentException | SecurityException
                 | NoSuchAlgorithmException failure) {
            return new Result(false, "Host probe failed: " + failure.getClass().getSimpleName(), null);
        }
    }

    private static Method method(Class<?> type, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Host method signature changed: " + name);
        }
        return method;
    }

    private static String digest(InputStream input) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16_384];
        int read;
        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        return HexFormat.of().formatHex(digest.digest());
    }
}
