/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "../../app/src/main/cpp/StrokeTailFlush.h"

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
    using reverie::StrokeTailFlush;
    check(StrokeTailFlush::allowed(true, "experimentbrush", false, false), "default on");
    check(!StrokeTailFlush::allowed(false, "experimentbrush", false, false), "switch off");
    check(!StrokeTailFlush::allowed(true, "paintbrush", false, false), "not experiment");
    check(!StrokeTailFlush::allowed(true, "experimentbrush", true, false), "displace");
    check(!StrokeTailFlush::allowed(true, "experimentbrush", false, true), "speed");
    return g_failures == 0 ? 0 : 1;
}
