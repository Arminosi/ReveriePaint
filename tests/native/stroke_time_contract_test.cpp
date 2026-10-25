/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "../../app/src/main/cpp/StrokeTimeContract.h"

#include <cmath>
#include <cstdio>

static int g_failures = 0;

static void check(bool ok, const char *what)
{
    if (!ok) {
        std::printf("  [FAIL] %s\n", what);
        ++g_failures;
    } else {
        std::printf("  [PASS] %s\n", what);
    }
}

int main()
{
    using reverie::StrokeTimeContract;
    check(StrokeTimeContract::toKritaMs(0.04) == 40.0, "40ms");
    check(StrokeTimeContract::toKritaMs(0.0) == 0.0, "zero");
    check(StrokeTimeContract::toKritaMs(-1.0) == 0.0, "negative");
    const double legacy = 12.0 / ((0.016 - 0.008) * 1000.0);
    const double now = StrokeTimeContract::speedPxPerMs(12.0, 0.008, 0.016);
    check(now == legacy, "speed matches legacy px/ms bitwise");
    const double dtSec = 1.002 - 1.0;
    const double legacyFast = 1.0e6 / ((dtSec > 1e-4 ? dtSec : 1e-4) * 1000.0);
    const double nowFast = StrokeTimeContract::speedPxPerMs(1.0e6, 1.0, 1.002);
    check(nowFast == legacyFast, "large distance matches legacy bitwise");
    const double dtBack = 0.5 - 1.0;
    const double legacyBack = 5.0 / ((dtBack > 1e-4 ? dtBack : 1e-4) * 1000.0);
    check(StrokeTimeContract::speedPxPerMs(5.0, 1.0, 0.5) == legacyBack, "t1 < t0 matches legacy");
    const double dtSame = 1.0 - 1.0;
    const double legacySame = 5.0 / ((dtSame > 1e-4 ? dtSame : 1e-4) * 1000.0);
    check(StrokeTimeContract::speedPxPerMs(5.0, 1.0, 1.0) == legacySame, "t1 == t0 matches legacy");
    check(StrokeTimeContract::toKritaMs(std::nan("")) == 0.0, "nan is zero");
    return g_failures == 0 ? 0 : 1;
}
