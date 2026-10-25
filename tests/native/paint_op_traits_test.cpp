/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "../../app/src/main/cpp/PaintOpTraits.h"

#include <cstdio>
#include <string_view>

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
    using reverie::PaintOpTraits;
    const std::string_view path[] = {
        "experimentbrush", "curvebrush", "sketchbrush", "gridbrush", "particlebrush"
    };
    for (std::string_view id : path) {
        check(PaintOpTraits::isPathEngine(id), "path engine");
    }
    check(!PaintOpTraits::isPathEngine("paintbrush"), "paintbrush is not a path engine");
    check(!PaintOpTraits::isPathEngine("colorsmudge"), "colorsmudge is not a path engine");
    check(!PaintOpTraits::isPathEngine(""), "empty id is not a path engine");

    check(!PaintOpTraits::usesFineSpacing("particlebrush"), "particle keeps coarse spacing");
    check(PaintOpTraits::usesFineSpacing("experimentbrush"), "experiment fine spacing");
    check(PaintOpTraits::usesFineSpacing("curvebrush"), "curve fine spacing");
    check(PaintOpTraits::usesFineSpacing("sketchbrush"), "sketch fine spacing");
    check(PaintOpTraits::usesFineSpacing("gridbrush"), "grid fine spacing");
    check(!PaintOpTraits::usesFineSpacing("paintbrush"), "paintbrush coarse spacing");

    check(PaintOpTraits::reportsExactDirty("experimentbrush"), "experiment exact dirty");
    check(!PaintOpTraits::reportsExactDirty("curvebrush"), "curve does not report exact dirty");
    check(!PaintOpTraits::reportsExactDirty("particlebrush"), "particle does not report exact dirty");

    check(PaintOpTraits::honorsPainterSelection("paintbrush"), "paintbrush honors selection");
    check(PaintOpTraits::honorsPainterSelection("duplicate"), "duplicate honors selection");
    check(PaintOpTraits::honorsPainterSelection("colorsmudge"), "colorsmudge honors selection");
    check(PaintOpTraits::honorsPainterSelection("experimentbrush"), "experiment honors selection");
    check(!PaintOpTraits::honorsPainterSelection("curvebrush"), "curve does not honor selection");
    check(!PaintOpTraits::honorsPainterSelection("sketchbrush"), "sketch does not honor selection");

    return g_failures == 0 ? 0 : 1;
}
