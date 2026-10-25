/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * StrokeTailFlush.h - 抬笔是否给 experimentbrush 补最后一段扇形
 *
 * 只放判定。真正的 paintLine 留在 ReverieCoreStroke.cpp，因为那里才有 Krita 类型。
 * 位移或速度打开时不补：零长度线段会让位移分支再推一次路径。
 */
#pragma once

#include <string_view>

namespace reverie {

class StrokeTailFlush {
public:
    static bool allowed(bool enabled, std::string_view opId, bool displacementEnabled, bool speedEnabled)
    {
        return enabled && opId == "experimentbrush" && !displacementEnabled && !speedEnabled;
    }
};

} // namespace reverie
