/*
 * ReverieCore 进程级单例与 Qt 初始化
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 这些符号从 libreverie_jni 移到了 libreverie_core: 引擎的创建 (QCoreApplication、
 * Krita 注册表、tile 内存配置) 属于核心库职责, JNI 层只应做参数搬运。
 * 拆库后 libreverie_jni 通过 NEEDED libreverie_core 拿到它们 (见 docs/plans/CORE-SPLIT.md P0.5)。
 */
#ifndef REVERIECOREINSTANCE_H
#define REVERIECOREINSTANCE_H

class ReverieCore;
class KisImageConfig;

/** 进程内唯一引擎实例 (首次调用会惰性创建 Qt app 与引擎) */
ReverieCore *core();

/** 仅初始化 Qt app (不创建引擎), 供需要在引擎存在前改配置的调用点使用 */
void reverieEnsureQtApp();

/** 按设备内存给 Krita tile 缓存定软/硬上限 */
void reverieTuneTileMemory(KisImageConfig &config);

#endif // REVERIECOREINSTANCE_H
