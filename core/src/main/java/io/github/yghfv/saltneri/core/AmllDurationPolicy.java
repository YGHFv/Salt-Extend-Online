// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from NeriPlayer AmllMatchPolicy.kt, revision fd7fdb757821.
// Copyright (C) NeriPlayer developers. See THIRD_PARTY_NOTICES.md.
package io.github.yghfv.saltneri.core;

public final class AmllDurationPolicy {
    private AmllDurationPolicy() {}

    /** candidateMs is the last lyric time, not necessarily the audio duration. */
    public static boolean compatible(long expectedMs, long candidateMs) {
        if (expectedMs <= 0 || candidateMs <= 0) return true;
        boolean shorter = candidateMs < expectedMs;
        long tolerance = shorter
                ? Math.min(60_000, Math.max(30_000, expectedMs / 100 * 15 + expectedMs % 100 * 15 / 100))
                : Math.min(30_000, Math.max(12_000, expectedMs / 10));
        long delta = shorter ? expectedMs - candidateMs : candidateMs - expectedMs;
        return delta <= tolerance;
    }

    /** Mirrors AmllLyricsResolver's additional unknown-duration gate. */
    public static boolean accepted(long expectedMs, long candidateMs, boolean requireDurationMatch) {
        if (expectedMs <= 0 || candidateMs <= 0) return !requireDurationMatch;
        return compatible(expectedMs, candidateMs);
    }
}
