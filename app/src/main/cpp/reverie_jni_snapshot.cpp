/*
 * JNI: 图层快照 (P0 验证用)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 目的: 用一次 C ABI 调用取回整棵图层树的只读快照, 代替 syncLayersFromNative()
 * 里的 n×20 次 JNI 往返; 同时作为状态所有权收敛的第一步 (见 docs/plans/CORE-SPLIT.md)。
 *
 * 协议 (小端):
 *   header: magic("RPSC") abi rev layer_count current_layer solo_active layer_stride reserved
 *   layers: layer_stride 字节/条, 内容为 rp_layer_snapshot
 *
 * 开关: 未设置 = 默认用快照; `0` = 强制旧路径; `1` = 旧路径+比对; `2` = 快照+比对。
 * 关闭时 Kotlin 侧只多一次返回 false 的 JNI 调用, 热路径不受影响。
 */

#include <jni.h>
#include <android/log.h>
#include <sys/system_properties.h>

#include <cstring>

#include "reverie_core.h"

namespace {

constexpr char kMagic[4] = {'R', 'P', 'S', 'C'};
constexpr int kHeaderBytes = 32;

int snapshotMode()
{
    static int cached = -1;
    if (cached >= 0) return cached;
    char value[PROP_VALUE_MAX] = {0};
    const int len = __system_property_get("debug.reverie.snapshot", value);
    // 未设置返回 -1: Kotlin 侧据此默认走快照路径 (0 = 强制旧路径, 1/2 = 比对)
    cached = (len > 0) ? atoi(value) : -1;
    return cached;
}

int payloadSize(const rp_snapshot *snap)
{
    const int count = rp_snapshot_layer_count(snap);
    return kHeaderBytes + count * static_cast<int>(sizeof(rp_layer_snapshot));
}

void writeHeader(uint8_t *dst, const rp_snapshot *snap)
{
    rp_doc_info info{};
    rp_snapshot_info(snap, &info);
    std::memset(dst, 0, kHeaderBytes);
    std::memcpy(dst + 0, kMagic, 4);
    const uint32_t abi = RP_ABI_VERSION;
    const uint32_t rev = rp_snapshot_rev(snap);
    const uint32_t stride = static_cast<uint32_t>(sizeof(rp_layer_snapshot));
    std::memcpy(dst + 4, &abi, 4);
    std::memcpy(dst + 8, &rev, 4);
    std::memcpy(dst + 12, &info.layer_count, 4);
    std::memcpy(dst + 16, &info.current_layer, 4);
    std::memcpy(dst + 20, &info.solo_active, 4);
    std::memcpy(dst + 24, &stride, 4);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_nativeSnapshotMode(JNIEnv *, jobject)
{
    return snapshotMode();
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_nativeSnapshotSize(JNIEnv *, jobject)
{
    // 先试算一次, 拿到精确字节数; 失败返回 -1
    const rp_snapshot *snap = rp_doc_snapshot(rp_doc_get());
    if (!snap) return -1;
    const int size = payloadSize(snap);
    rp_snapshot_release(snap);
    return size;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_nativeSnapshotFill(JNIEnv *env, jobject, jobject directBuffer)
{
    if (!directBuffer) return -1;
    auto *dst = static_cast<uint8_t *>(env->GetDirectBufferAddress(directBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (!dst || capacity <= 0) return -1;

    const rp_snapshot *snap = rp_doc_snapshot(rp_doc_get());
    if (!snap) return -1;

    const int needed = payloadSize(snap);
    if (capacity < needed) {
        rp_snapshot_release(snap);
        // 返回负的需求字节数, Kotlin 侧扩容后重试 -> 稳态只需一次 JNI 调用
        return -needed;
    }

    writeHeader(dst, snap);
    const int count = rp_snapshot_layer_count(snap);
    uint8_t *cursor = dst + kHeaderBytes;
    for (int i = 0; i < count; ++i) {
        rp_layer_snapshot layer{};
        if (!rp_snapshot_layer(snap, i, &layer)) break;
        std::memcpy(cursor, &layer, sizeof(layer));
        cursor += sizeof(layer);
    }

    const jint rev = static_cast<jint>(rp_snapshot_rev(snap));
    rp_snapshot_release(snap);
    return rev;
}

} // extern "C"
