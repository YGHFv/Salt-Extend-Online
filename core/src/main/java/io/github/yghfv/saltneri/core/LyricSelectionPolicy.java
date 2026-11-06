// SPDX-License-Identifier: GPL-3.0-or-later
// Selection branches adapted from NeriPlayer PlayerLyricsProvider.getLyrics().
// Copyright (C) NeriPlayer developers. See THIRD_PARTY_NOTICES.md.
package io.github.yghfv.saltneri.core;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Selection only: loaders must perform parsing, identity checks and error handling. */
public final class LyricSelectionPolicy {
    public enum Source { USER, NETEASE, BILI_CC, KUGOU, QQ, LRCLIB, AMLL }
    public enum StoredState { ABSENT, PRESENT, CLEARED }
    public enum Outcome { FOUND, NONE, CLEARED }

    /** All language tracks stay together when the original source changes. */
    public record Bundle(Source source, String original, String translated, String romanized,
                         boolean wordTimed) {
        public Bundle {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(translated, "translated");
            Objects.requireNonNull(romanized, "romanized");
            if (original.isBlank()) throw new IllegalArgumentException("Empty original lyrics");
        }
    }

    public record Stored(StoredState state, Optional<Bundle> bundle) {
        public Stored {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(bundle, "bundle");
            if ((state == StoredState.PRESENT) != bundle.isPresent()) {
                throw new IllegalArgumentException("Stored state and payload disagree");
            }
        }

        public static Stored absent() { return new Stored(StoredState.ABSENT, Optional.empty()); }
        public static Stored cleared() { return new Stored(StoredState.CLEARED, Optional.empty()); }
        public static Stored present(Bundle bundle) {
            return new Stored(StoredState.PRESENT, Optional.of(bundle));
        }
    }

    public record Options(boolean preferWordTimed, boolean amllEnabled) {}

    public record Selection(Outcome outcome, Optional<Bundle> bundle) {
        public Selection {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(bundle, "bundle");
            if ((outcome == Outcome.FOUND) != bundle.isPresent()) {
                throw new IllegalArgumentException("Outcome and payload disagree");
            }
        }
    }

    private LyricSelectionPolicy() {}

    public static Selection select(Optional<Bundle> confirmedManual,
                                   Supplier<Optional<Bundle>> preferred,
                                   Stored stored,
                                   Supplier<Optional<Bundle>> platform,
                                   Supplier<Optional<Bundle>> amll,
                                   Options options) {
        Objects.requireNonNull(confirmedManual, "confirmedManual");
        Objects.requireNonNull(preferred, "preferred");
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(amll, "amll");
        Objects.requireNonNull(options, "options");
        if (confirmedManual.isPresent()) return found(confirmedManual.get());
        Optional<Bundle> preferredResult = preferred.get();
        if (preferredResult.isPresent()) return found(preferredResult.get());
        if (stored.state() == StoredState.CLEARED) {
            return new Selection(Outcome.CLEARED, Optional.empty());
        }
        if (stored.state() == StoredState.PRESENT) return found(stored.bundle().orElseThrow());
        Optional<Bundle> nativeResult = platform.get();
        if (nativeResult.isPresent() && (nativeResult.get().source() == Source.BILI_CC
                || nativeResult.get().wordTimed())) return found(nativeResult.get());
        if (options.preferWordTimed() && options.amllEnabled()) {
            Optional<Bundle> upgrade = amll.get();
            if (upgrade.isPresent() && upgrade.get().wordTimed()) return found(upgrade.get());
        }
        return nativeResult.map(LyricSelectionPolicy::found)
                .orElseGet(() -> new Selection(Outcome.NONE, Optional.empty()));
    }

    private static Selection found(Bundle bundle) {
        return new Selection(Outcome.FOUND, Optional.of(bundle));
    }
}
