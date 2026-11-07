package io.github.yghfv.saltneri.plugin.host;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

/** Runs against host classes without starting MainKt or reading app configuration data. */
public final class HostProfileSmokeTest {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = HostProfileSmokeTest.class.getClassLoader();
        Salt1193Probe.Result result = Salt1193Probe.inspect(loader, "1.19.3", "Steam");
        require(result.matched(), result.detail());
        String[] fields = {"saltneri:test", "Test title", "Test artist", "Test album", "Test album artist", "saltneri:test-path"};
        Object item = result.mediaFactory().create(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
        require(Arrays.equals(fields, result.mediaFactory().read(item)), "Native constructor/getter order changed");
        require(Salt1193Probe.inspect(loader, "1.19.4", "Steam").matched(), "Compatible newer version rejected");
        require(Salt1193Probe.inspect(loader, "1.19.3", "MS").matched(), "Compatible alternate channel rejected");
        ClassLoader changed = new ClassLoader(loader) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.equals("com/xuncorp/pisces/PiscesMediaItem.class")) return new ByteArrayInputStream(new byte[]{0});
                return super.getResourceAsStream(name);
            }
        };
        require(Salt1193Probe.inspect(changed, "9.0", "Other").matched(), "Byte resource changes should not gate compatible classes");
        ClassLoader changedParent = new ClassLoader(loader) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.equals("androidx.compose.ui.\u0aae")) throw new ClassNotFoundException("Fixture missing service base");
                return super.loadClass(name,resolve);
            }
        };
        require(!Salt1193Probe.inspect(changedParent, "1.19.3", "Steam").matched(), "Missing service class accepted");
        System.out.println("PASS: host profile, native media field round-trip, version/channel/hash tolerance and missing-capability rejection");
        System.out.println("No playback controller initialized, queue modified, native stream opened or account accessed.");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
