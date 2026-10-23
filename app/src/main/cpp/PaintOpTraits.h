/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * PaintOpTraits.h - 宿主对笔刷引擎的分类
 *
 * isPathEngine 与 usesFineSpacing 故意分开：采样间距的旧列表不含 particlebrush
 * （ReverieCoreStroke.cpp 合并前的 appendStrokeSample）。把 particle 改成 1.5px
 * 会改变大笔刷的采样密度，不属于本次修复。
 */
#pragma once

#include <string_view>

namespace reverie {

class PaintOpTraits {
public:
    static bool isPathEngine(std::string_view id)
    {
        return id == "experimentbrush" || id == "curvebrush" || id == "sketchbrush" ||
               id == "gridbrush" || id == "particlebrush";
    }

    static bool usesFineSpacing(std::string_view id)
    {
        return id == "experimentbrush" || id == "curvebrush" || id == "sketchbrush" ||
               id == "gridbrush";
    }

    // experiment 的写入走 KisPainter::fillPainterPathImpl → bitBlt，
    // takeDirtyRegion() 覆盖实际落墨。宿主未调用 setMirrorInformation，
    // 不考虑 paintop 的 hasMirroring 分支。
    static bool reportsExactDirty(std::string_view id)
    {
        return id == "experimentbrush";
    }

    // 这些引擎的写入会读 painter selection（dab 或 bitBlt）。
    // 其余引擎靠宿主快照后按选区蒙版写回。
    static bool honorsPainterSelection(std::string_view id)
    {
        return id == "paintbrush" || id == "duplicate" || id == "colorsmudge" ||
               id == "experimentbrush";
    }
};

} // namespace reverie
