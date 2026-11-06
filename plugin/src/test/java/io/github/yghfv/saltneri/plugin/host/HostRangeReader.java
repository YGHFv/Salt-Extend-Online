package io.github.yghfv.saltneri.plugin.host;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;

/** Reflection keeps proprietary host implementations out of both source and Maven dependencies. */
public final class HostRangeReader implements AutoCloseable {
    private final Object source;
    private HostRangeReader(Object source) { this.source = source; }

    public static HostRangeReader open(URI localUri) throws Exception {
        Class<?> type = Class.forName("com.xuncorp.voxzen.cast.HttpRangeSource");
        Object companion = type.getField("Companion").get(null);
        Object source = invoke(companion, "open", new Class<?>[]{String.class, int.class, int.class},
                localUri.toString(), 3000, 3000);
        return new HostRangeReader(source);
    }

    public long length() throws Exception { return (long)invoke(source, "getLength", new Class<?>[0]); }
    public boolean seek(long position) throws Exception {
        return (boolean)invoke(source, "seek", new Class<?>[]{long.class}, position);
    }
    public int read(byte[] bytes) throws Exception {
        return (int)invoke(source, "read", new Class<?>[]{byte[].class, int.class, int.class}, bytes, 0, bytes.length);
    }
    @Override public void close() throws Exception { invoke(source, "close", new Class<?>[0]); }

    private static Object invoke(Object owner, String method, Class<?>[] parameters, Object... arguments) throws Exception {
        try { return owner.getClass().getMethod(method, parameters).invoke(owner, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
}
