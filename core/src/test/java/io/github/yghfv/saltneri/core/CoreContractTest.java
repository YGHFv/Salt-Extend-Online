package io.github.yghfv.saltneri.core;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import io.github.yghfv.saltneri.core.LyricSelectionPolicy.Bundle;
import io.github.yghfv.saltneri.core.LyricSelectionPolicy.Options;
import io.github.yghfv.saltneri.core.LyricSelectionPolicy.Outcome;
import io.github.yghfv.saltneri.core.LyricSelectionPolicy.Source;
import io.github.yghfv.saltneri.core.LyricSelectionPolicy.Stored;

/** No test framework or network is needed to exercise the initial contracts. */
public final class CoreContractTest {
    private static int checks;
    private static final Supplier<Optional<Bundle>> EMPTY = Optional::empty;
    private static final Supplier<Optional<Bundle>> FORBIDDEN = () -> {
        throw new AssertionError("An unnecessary loader was invoked");
    };

    public static void main(String[] args) {
        identities();
        durations();
        amllDurations();
        thumbnails();
        selection();
        publication();
        bridge();
        System.out.println("PASS: " + checks + " core contract checks");
    }

    private static void identities() {
        eq("netease:song:42", TrackKey.netease("42").stableId());
        eq("bilibili:video:BV1xx411c7mD:cid:100", TrackKey.bilibili("BV1xx411c7mD", 100).stableId());
        check(!TrackKey.bilibili("BV1xx411c7mD", 100).equals(TrackKey.bilibili("BV1xx411c7mD", 101)));
        eq(TrackKey.netease("42"), TrackKey.netease("42"));
        rejects(IllegalArgumentException.class, () -> TrackKey.netease("0"));
        rejects(IllegalArgumentException.class, () -> TrackKey.netease("0042"));
        rejects(IllegalArgumentException.class, () -> TrackKey.netease("42?token=secret"));
        rejects(IllegalArgumentException.class, () -> TrackKey.bilibili("av123", 100));
        rejects(IllegalArgumentException.class, () -> TrackKey.bilibili("BV1xx411c7mD", 0));
    }

    private static void durations() {
        check(!ExternalLyricDurationPolicy.compatible(0, 100_000));
        check(!ExternalLyricDurationPolicy.compatible(100_000, -1));
        check(ExternalLyricDurationPolicy.compatible(100_000, 107_000));
        check(!ExternalLyricDurationPolicy.compatible(100_000, 107_001));
        check(ExternalLyricDurationPolicy.compatible(200_000, 188_000));
        check(!ExternalLyricDurationPolicy.compatible(200_000, 187_999));
        check(ExternalLyricDurationPolicy.compatible(600_000, 615_000));
        check(!ExternalLyricDurationPolicy.compatible(600_000, 615_001));
        check(ExternalLyricDurationPolicy.compatible(Long.MAX_VALUE, Long.MAX_VALUE - 15_000));
        check(!ExternalLyricDurationPolicy.compatible(Long.MAX_VALUE, 1));
    }

    private static void thumbnails() {
        eq("https://i0.hdslb.com/a.jpg@320w_180h_1c.webp", BiliThumbnail.build("//i0.hdslb.com/a.jpg", 320, 180));
        eq("https://i0.hdslb.com/a.jpg@100w_100h_1c.webp?q=1#x",
                BiliThumbnail.build("https://i0.hdslb.com/a.jpg@20w_20h.webp?q=1#x", 100, 100));
        eq("https://i0.hdslb.com/a@b/photo.jpg@100w_100h_1c.webp",
                BiliThumbnail.build("https://i0.hdslb.com/a@b/photo.jpg", 100, 100));
        eq("https://x.biliimg.com/a.jpg@100w_100h_1c.webp",
                BiliThumbnail.build("https://x.biliimg.com/a.jpg", 100, 100));
        String other = "https://example.com/a.jpg";
        eq(other, BiliThumbnail.build(other, 100, 100));
        String lookalike = "https://i0.hdslb.com.evil.example/a.jpg";
        eq(lookalike, BiliThumbnail.build(lookalike, 100, 100));
        eq("//i0.hdslb.com/a.jpg", BiliThumbnail.build("//i0.hdslb.com/a.jpg", 0, 100));
        eq(" ", BiliThumbnail.build(" ", 100, 100));
        eq("not a uri", BiliThumbnail.build("not a uri", 100, 100));
    }

    private static void amllDurations() {
        check(AmllDurationPolicy.compatible(200_000, 170_000));
        check(!AmllDurationPolicy.compatible(200_000, 169_999));
        check(AmllDurationPolicy.compatible(200_000, 220_000));
        check(!AmllDurationPolicy.compatible(200_000, 220_001));
        check(AmllDurationPolicy.compatible(600_000, 540_000));
        check(!AmllDurationPolicy.compatible(600_000, 539_999));
        check(AmllDurationPolicy.compatible(600_000, 630_000));
        check(!AmllDurationPolicy.compatible(600_000, 630_001));
        check(AmllDurationPolicy.compatible(0, 100_000));
        check(!AmllDurationPolicy.accepted(0, 100_000, true));
        check(AmllDurationPolicy.accepted(0, 100_000, false));
        check(AmllDurationPolicy.compatible(Long.MAX_VALUE, Long.MAX_VALUE - 60_000));
    }

    private static void selection() {
        Bundle user = bundle(Source.USER, false);
        Bundle netease = bundle(Source.NETEASE, false);
        Bundle yrc = bundle(Source.NETEASE, true);
        Bundle cc = bundle(Source.BILI_CC, false);
        Bundle amll = bundle(Source.AMLL, true);
        Options upgrade = new Options(true, true);

        eq(user, LyricSelectionPolicy.select(Optional.of(user), FORBIDDEN, Stored.cleared(),
                FORBIDDEN, FORBIDDEN, upgrade).bundle().orElseThrow());
        // Once the caller's eligibility gate allows it, preferred precedes stored-clear.
        eq(netease, LyricSelectionPolicy.select(Optional.empty(), () -> Optional.of(netease),
                Stored.cleared(), FORBIDDEN, FORBIDDEN, upgrade).bundle().orElseThrow());
        eq(Outcome.CLEARED, LyricSelectionPolicy.select(Optional.empty(), EMPTY, Stored.cleared(),
                FORBIDDEN, FORBIDDEN, upgrade).outcome());
        eq(user, LyricSelectionPolicy.select(Optional.empty(), EMPTY, Stored.present(user),
                FORBIDDEN, FORBIDDEN, upgrade).bundle().orElseThrow());
        eq(cc, select(() -> Optional.of(cc), FORBIDDEN, upgrade).bundle().orElseThrow());
        eq(yrc, select(() -> Optional.of(yrc), FORBIDDEN, upgrade).bundle().orElseThrow());
        eq(amll, select(() -> Optional.of(netease), () -> Optional.of(amll), upgrade).bundle().orElseThrow());
        eq(netease, select(() -> Optional.of(netease), EMPTY, upgrade).bundle().orElseThrow());
        eq(netease, select(() -> Optional.of(netease), FORBIDDEN, new Options(false, true)).bundle().orElseThrow());
        eq(netease, select(() -> Optional.of(netease), FORBIDDEN, new Options(true, false)).bundle().orElseThrow());
        eq(amll, select(EMPTY, () -> Optional.of(amll), upgrade).bundle().orElseThrow());
        eq(Outcome.NONE, select(EMPTY, EMPTY, upgrade).outcome());
        eq(Outcome.NONE, select(EMPTY, FORBIDDEN, new Options(false, true)).outcome());
        eq(netease, select(() -> Optional.of(netease), () -> Optional.of(bundle(Source.AMLL, false)),
                upgrade).bundle().orElseThrow());
        Bundle upgraded = select(() -> Optional.of(netease), () -> Optional.of(amll), upgrade).bundle().orElseThrow();
        eq("AMLL translation", upgraded.translated());
        eq("AMLL romanized", upgraded.romanized());
        rejects(IllegalArgumentException.class, () -> new Bundle(Source.USER, "", "", "", false));
        rejects(IllegalArgumentException.class, () -> new Stored(LyricSelectionPolicy.StoredState.ABSENT,
                Optional.of(user)));
        rejects(IllegalArgumentException.class, () -> new LyricSelectionPolicy.Selection(Outcome.NONE,
                Optional.of(user)));
    }

    private static LyricSelectionPolicy.Selection select(Supplier<Optional<Bundle>> platform,
                                                         Supplier<Optional<Bundle>> amll,
                                                         Options options) {
        return LyricSelectionPolicy.select(Optional.empty(), EMPTY, Stored.absent(), platform, amll, options);
    }

    private static Bundle bundle(Source source, boolean wordTimed) {
        return new Bundle(source, "[00:01.000]original", source + " translation", source + " romanized", wordTimed);
    }

    private static void publication() {
        PublicationGate gate = new PublicationGate();
        AtomicInteger published = new AtomicInteger();
        TrackKey track = TrackKey.netease("42");
        PublicationGate.Ticket first = gate.begin(track, "account-A", 1);
        check(gate.publish(first, published::incrementAndGet));
        eq(1, published.get());
        PublicationGate.Ticket second = gate.begin(TrackKey.netease("43"), "account-A", 1);
        check(!gate.publish(first, published::incrementAndGet));
        PublicationGate.Ticket otherAccount = gate.begin(second.track(), "account-B", 1);
        check(!gate.publish(second, published::incrementAndGet));
        PublicationGate.Ticket edited = gate.begin(otherAccount.track(), "account-B", 2);
        check(!gate.publish(otherAccount, published::incrementAndGet));
        gate.invalidate();
        check(!gate.publish(edited, published::incrementAndGet));
        PublicationGate.Ticket restarted = gate.begin(track, "account-A", 1);
        check(!gate.publish(first, published::incrementAndGet));
        check(gate.publish(restarted, published::incrementAndGet));
        eq(2, published.get());
    }

    private static void bridge() {
        SaltHostBridge bridge = SaltHostBridge.unavailable();
        check(bridge.capabilities().isEmpty());
        rejects(UnsupportedOperationException.class, () -> bridge.enqueue(TrackKey.netease("42")));
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Check " + checks + " failed");
    }

    private static void eq(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError("Check " + checks + ": expected " + expected + ", got " + actual);
        }
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Unexpected failure", failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
