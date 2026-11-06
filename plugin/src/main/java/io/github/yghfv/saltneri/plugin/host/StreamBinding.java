package io.github.yghfv.saltneri.plugin.host;

import java.net.URI;

/** Session port; native attachment and its offline test double share the same lifecycle. */
public interface StreamBinding extends AutoCloseable {
    void register(String path, URI localUri);
    void unregister(String path);
    ReversibleInterceptorLists attach(Object engine) throws ReflectiveOperationException;
    boolean isAttached();
    @Override void close();
}
