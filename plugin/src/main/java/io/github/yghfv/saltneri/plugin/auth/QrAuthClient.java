package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;

public interface QrAuthClient extends AutoCloseable {
    enum State { WAITING_SCAN, WAITING_CONFIRMATION, EXPIRED, CONFIRMED }
    record Session(String key, URI qrContent, String chainId, Instant expiresAt) {
        @Override public String toString() { return "QrSession[secret nonce redacted]"; }
    }
    record Poll(State state, AccountCredentials credentials) {
        @Override public String toString() { return "QrPoll[" + state + ", secrets redacted]"; }
    }
    Session create(RequestScope request) throws IOException;
    Poll poll(Session session, RequestScope request) throws IOException;
    AccountCredentials verify(AccountCredentials saved, RequestScope request) throws IOException;
    default AccountCredentials verifyCookies(java.util.List<ScopedCookies.Entry> entries,java.time.Clock clock,RequestScope request) throws IOException {
        throw new IOException("Cookie login unavailable");
    }
    default byte[] qrImage(Session session) { return null; }
    @Override void close();
}
