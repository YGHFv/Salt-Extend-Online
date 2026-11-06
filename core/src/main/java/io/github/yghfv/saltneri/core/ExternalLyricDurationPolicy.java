// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from NeriPlayer ExternalLyricMatchPolicy.kt, revision fd7fdb757821.
// Copyright (C) NeriPlayer developers. See THIRD_PARTY_NOTICES.md.
package io.github.yghfv.saltneri.core;

public final class ExternalLyricDurationPolicy {
    private ExternalLyricDurationPolicy() {}

    public static boolean compatible(long expectedMs, long candidateMs) {
        if (expectedMs <= 0 || candidateMs <= 0) return false;
        // Split the arithmetic to retain integer rounding without overflowing long.
        long sixPercent = expectedMs / 100 * 6 + expectedMs % 100 * 6 / 100;
        long tolerance = Math.min(15_000, Math.max(7_000, sixPercent));
        long delta = expectedMs >= candidateMs ? expectedMs - candidateMs : candidateMs - expectedMs;
        return delta <= tolerance;
    }
}
