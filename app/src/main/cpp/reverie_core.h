/*
 * ReverieCore C ABI —— 应用核心库的稳定边界 (P0: 只读快照)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 设计约定 (见 docs/plans/CORE-SPLIT.md):
 *  1. 这个头文件只允许出现 C 类型: 不暴露 QString / KisNode / 模板。
 *  2. 引擎是唯一权威: 读走不可变快照 (带单调 rev), 写走 rp_cmd_* (后续阶段)。
 *  3. 快照带引用计数, 引擎内部对象永不出界; 调用方必须成对 release。
 *
 * 当前实现范围: 文档信息 + 图层快照。写命令 (rp_cmd_*) 与帧/选区快照在 P1/P2 落地。
 */
#ifndef REVERIE_CORE_H
#define REVERIE_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RP_ABI_VERSION 1u
#define RP_NAME_CAP 64
#define RP_BLEND_CAP 32

typedef struct rp_doc rp_doc;
typedef struct rp_snapshot rp_snapshot;

/** 图层标志位 (位图, 与 kotlin 侧一一对应) */
enum {
    RP_LAYER_VISIBLE         = 1u << 0,
    RP_LAYER_LOCKED          = 1u << 1,
    RP_LAYER_ALPHA_LOCKED    = 1u << 2,
    RP_LAYER_GROUP           = 1u << 3,
    RP_LAYER_STROKE          = 1u << 4,
    RP_LAYER_BACKGROUND      = 1u << 5,
    RP_LAYER_SOLOED          = 1u << 6,
    RP_LAYER_CLIPPED         = 1u << 7,
    RP_LAYER_ALPHA_INHERITED = 1u << 8,
    RP_LAYER_PASS_THROUGH    = 1u << 9,
    RP_LAYER_SOLO_SHADOWED   = 1u << 10 /* solo 生效时该层在面板上显示为隐藏 */
};

/**
 * 单个图层的只读快照 (POD, 定长 152 字节, 布局固定 —— 字段偏移写在这里,
 * Kotlin 侧按同样偏移解析, 不做结构体内存猜测):
 *   0   id(u64)        8   opacity(f64)
 *   16  index(i32)     20  node_type     24 depth        28 color_label
 *   32  stroke_size    36  stroke_position 40 stroke_opacity
 *   44  stroke_color   48  fill_color     52 flags
 *   56  name[64]       120 blend_mode[32]
 */
typedef struct rp_layer_snapshot {
    uint64_t id;
    double   opacity;        /* 0..1, 原值直传避免精度丢失 */
    int32_t  index;
    int32_t  node_type;
    int32_t  depth;
    int32_t  color_label;
    int32_t  stroke_size;
    int32_t  stroke_position;
    int32_t  stroke_opacity;
    uint32_t stroke_color;
    uint32_t fill_color;
    uint32_t flags;          /* RP_LAYER_* 位图 */
    char     name[RP_NAME_CAP];
    char     blend_mode[RP_BLEND_CAP];
} rp_layer_snapshot;

#ifdef __cplusplus
static_assert(sizeof(rp_layer_snapshot) == 152, "rp_layer_snapshot layout changed; update Kotlin parser");
#endif

/** 文档级快照信息 */
typedef struct rp_doc_info {
    uint32_t layer_count;
    int32_t  current_layer;
    uint32_t solo_active;    /* 0/1 */
    uint32_t reserved;
} rp_doc_info;

uint32_t rp_abi_version(void);

/** 取引擎单例句柄 (进程内唯一, 不需要也不能 close) */
rp_doc *rp_doc_get(void);

/**
 * 生成当前状态的不可变快照。返回 NULL 表示引擎未就绪。
 * 调用方负责 rp_snapshot_release()。
 */
const rp_snapshot *rp_doc_snapshot(rp_doc *doc);
void rp_snapshot_release(const rp_snapshot *snap);

uint32_t rp_snapshot_rev(const rp_snapshot *snap);
int rp_snapshot_info(const rp_snapshot *snap, rp_doc_info *out);
int rp_snapshot_layer_count(const rp_snapshot *snap);
/** 拷贝第 index 层到 out; 越界返回 0 */
int rp_snapshot_layer(const rp_snapshot *snap, int index, rp_layer_snapshot *out);

/** 最近一次失败的人类可读原因 (仅调试用, 非线程安全) */
const char *rp_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* REVERIE_CORE_H */
