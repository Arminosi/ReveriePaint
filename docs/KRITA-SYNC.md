# Krita 引擎 fork 与上游同步手册

> 记录 ReveriePaint 所依赖的 Krita 引擎 fork 的位置、补丁构成、同步流程与踩过的坑。
> 补丁本体在 `patches/krita/`，本文件是可重复执行的同步清单。

## 1. 仓库与分支

| 项 | 值 |
|---|---|
| 本地源码 | `/home/lanrhyme/Projects/krita-source` |
| 上游 | `origin` = <https://invent.kde.org/graphics/krita.git>（分支 `master`） |
| fork | `github` = <https://github.com/LanRhyme/krita.git>（HEAD 指向 `reverie-android`） |
| 长期分支 | **`reverie-android`**（唯一），历史上还有 `feat/clipping-mask`，2026-10-27 已统一并删除 |
| 当前基线 | Krita `master` @ `72d3b1e3`（2026-10-27，6.1.0-prealpha） |
| 安全锚点 | tag `pre-sync-20261027`（同步前状态）、fork 上的同名 tag |

分支上是 **9 个线性提交**（不再保留 merge 提交），与 `patches/krita/0001-0009` 一一对应：

1. `feat(android)` 14 个笔刷引擎静态注册 + Android 构建兼容
2. `fix(roundmarker)` KisMarkerPainter 改标准覆盖混合
3. `fix(swap)` 多步撤销 Swap 溢出 qFatal 修复
4. `fix(roundmarker)` 边缘像素改 COMPOSITE_OVER，消除白边
5. `feat(clipping)` 剪切蒙版引擎支持
6. `feat(mypaint)` 静态注册与 Android 构建支持
7. `fix(mypaint)` 传感器懒初始化，修复 dlopen 崩溃
8. `fix(build)` `kis_lockless_stack.h` 补 `#include <QtGlobal>`，修 Qt 6.6.3 下 `Q_DISABLE_COPY` 未定义
9. `fix(android)` 视频编码器空实现桩（Android 无 MLT/FFmpeg），修 `libkritaui.so` 链接失败

## 2. 同步流程

```bash
cd ~/Projects/krita-source
git -c tag.gpgSign=false tag -f pre-sync-$(date +%Y%m%d) HEAD   # 安全锚点
git fetch origin master                                          # 只拉新提交，浅克隆不会 unshallow
git checkout -B reverie-android origin/master
# 依次重放旧分支的 7 个提交（新 SHA 见 fork 或 patches/krita）
for c in <旧提交系列>; do git cherry-pick -n "$c" || { 解冲突; git add -u; }; git commit -F <统一提交说明>; done

git diff --stat reverie-android <同步前的合并/旧 tip>   # 验收：应只差已知的无关差异
cmake --build build-android -j$(nproc)                  # 原生编译验证
git format-patch -o <ReveriePaint-native>/patches/krita origin/master..reverie-android
# 更新 patches/krita/README.md 里的基线 commit，提交 app 仓库
git push --force-with-lease=reverie-android:<旧远端 SHA> github reverie-android
```

判断上游走了多远：

```bash
git rev-list --count origin/master..reverie-android   # 本地领先
git rev-list --count reverie-android..origin/master   # 落后多少提交
git diff --name-only origin/master..reverie-android | sort > /tmp/mine.txt
git diff --name-only <旧基线> origin/master | sort > /tmp/up.txt
comm -12 /tmp/mine.txt /tmp/up.txt                    # 双方都改过的文件 = 冲突候选
```

## 3. 已知冲突点（每次同步必查）

| 文件 | 情况 | 解法 |
|---|---|---|
| `libs/ui/CMakeLists.txt` | 上游的 `if(ANDROID)` 源列表会新增 `KisAndroidMediaEncoderRunnable.cpp`、`KisLibavMediaEncoderRunnable.cpp` | **保持排除**。Android 端 MP4 由应用侧 Kotlin `MediaCodec` 实现（`core/export/Mp4VideoEncoder.kt`），Krita 侧的 LibAV 通道在 0001 里已禁用；只保留 `KisMediaEncoderFormatPreferencesDialog.cpp` |
| `plugins/impex/psd/psd_loader.cpp` | 上游新增 `layer->setUserLocked(...)`，本地新增 `enableClippingLayer(...)`，同一位置 | **两条都保留**（先后顺序无关） |
| `cmake/modules/FindLibMyPaint.cmake` | 上游在移除 `find_package_handle_standard_args` 的 `FOUND_VAR`（新版 ECM 弃用） | 自动合并；本地只加 ANDROID 的 `json-c` 分支，不冲突 |
| `CMakeLists.txt` | 上游改了 LibAV 相关探测 | 自动合并；本地在 ANDROID 下把 LibAV 置为 `FOUND FALSE`、降级 `OPTIONAL` |
| `libs/ui/animation/*` | 上游把视频导出重构成 `KisMediaEncoderWrapper` + 编码器 runnable | Android 只编译 wrapper，另外提供一个空实现桩（见 0009），真实编码器实现不参与 Android 构建 |

## 4. 构建验证

**不要直接 `cmake --build build-android`**：默认目标包含 `plugins/assistants` 等从未参与 Android 出货、
且依赖更新 Qt 才编得过的目标。按出货清单建（`third_party/android-native-libs/libkrita*.so` 对应的 38 个目标）：

```bash
cd ~/Projects/krita-source
ninja -C build-android -j$(nproc) \
    lib/libkritaui.so lib/libkritaimage.so lib/libkritapigment.so lib/libkritaflake.so \
    lib/libkritawidgetutils.so lib/libkritawidgets.so lib/libkritastore.so lib/libkritaimpex.so \
    lib/libkritaresources.so lib/libkritaresourcewidgets.so lib/libkritacommand.so \
    lib/libkritaglobal.so lib/libkritaversion.so lib/libkritaplugin.so lib/libkritamultiarch.so \
    lib/libkritasurfacecolormanagementapi.so lib/libkritametadata.so lib/libkritalibbrush.so \
    lib/libkritalibkra.so lib/libkritalibpaintop.so lib/libkritapsd.so lib/libkritapsdutils.so \
    lib/libkritabasicflakes.so lib/kritalcmsengine.so \
    lib/kritacolorsmudgepaintop.so lib/kritacurvepaintop.so lib/kritadeformpaintop.so \
    lib/kritaexperimentpaintop.so lib/kritafilterop.so lib/kritagridpaintop.so \
    lib/kritahairypaintop.so lib/kritahatchingpaintop.so lib/kritamypaintop.so \
    lib/kritaparticlepaintop.so lib/kritaroundmarkerpaintop.so lib/kritasketchpaintop.so \
    lib/kritaspraypaintop.so lib/kritatangentnormalpaintop.so
```

2026-10-27 这次同步就是按上述目标集验证通过的（228 个编译步骤，0 错误）。

如果之后还要验证 app 侧 JNI 是否跟着编得过，再补一步（用 gradle 既有的 CMake 配置目录）：

```bash
cd ~/Projects/ReveriePaint-native
D=$(ls -td app/.cxx/Release/*/arm64-v8a | head -1)
ninja -C "$D" -j$(nproc) reverie_jni
```

注意 `lib/libkritadefaultpaintops_static.a` 也被 app 直接链接：它的 mtime 若比源码新（比如从提交里
捞回来的构建目录），ninja 不会重建，链 JNI 时会报 `undefined symbol:
KisPaintOpSettingsWidget::setImage(...)` 这类已删 API 的错。先 `ninja -C build-android
lib/libkritadefaultpaintops_static.a` 刷新它再链。

构建目录 `build-android/` 的关键配置（`CMakeCache.txt`）：

- 工具链 `~/ReveriePaint-android/toolchain/qt-chainload.cmake`
- `Qt6_DIR=~/Qt6/6.6.3/android_arm64_v8a/lib/cmake/Qt6`
- `ANDROID_ABI=arm64-v8a`、`ANDROID_NDK_ROOT=~/Android/Sdk/ndk/25.2.9519653`
- 依赖前缀 `~/ReveriePaint-android/deps`

产物替换到 `third_party/android-native-libs/` 后，`./scripts/build_native.sh` 才能打出与新引擎一致的 APK。

## 5. 踩过的坑

1. **`tag.gpgSign=true`**：这个仓库开了标签签名，脚本里 `git tag -f` 会要求提交说明而失败。
   用 `git -c tag.gpgSign=false tag ...`，或者写 `git tag -a -m "..."`。
2. **`git add -A` 会把 `build-android*/` 吃进提交**（该仓库没忽略这些构建目录），随后
   `git reset --hard` 会连带删除这些构建产物，构建目录直接报废。
   - 提交前只用 `git add -u` 或显式列文件
   - 万一删了：从悬空提交里捞回来
     `git archive <含构建产物的悬空提交> build-android build-android-armv7 build-desktop configure_armv7.sh | tar -x -C .`
3. **浅克隆**：`.git/shallow` 边界在基线提交上，`git fetch origin master` 只补新提交不会 unshallow；
   但 `git merge-base` 到更早的提交（如 `krita/6.0` 分叉点）会查不到。
4. **上游重建历史**：KDE 偶尔 rebase `master`，此时只能用 `format-patch` + `cherry-pick` 重放，
   不要指望 fast-forward 或 merge。
5. **Qt 6.6.3 的隐式包含比上游 CI 少**：`libs/global/kis_lockless_stack.h` 只包含
   `<QAtomicPointer>` 却用 `Q_DISABLE_COPY`（Qt 6 里该宏在 `qtclasshelpermacros.h`，
   由 `qglobal.h` 提供，而 qatomic 链不传递包含），因此凡是把它作为第一个 Qt 头文件的
   TU（如 `CmykU16ColorSpace.cpp`）会在我们这套工具链上编译失败，已用 0008 补上
   `#include <QtGlobal>`。同步后出现「unknown type name 'Q_DISABLE_COPY' / 'QAtomicInt'」
   一类错误时先查这个。
6. **恢复构建目录后 moc/生成文件会陈旧**：从提交里捞回 `build-*` 后其 mtime 比源码新，
   ninja 会跳过 moc 重生成，报「no member named X in ...」的假错误。用
   `git ls-files -z | xargs -0 touch` 刷新源码时间戳再编（代价是近似全量重编）。

## 6. 版本线

- 2026-07-26 之前：基线 `a4d196ef`（6.1.0-prealpha）
- 2026-10-27：同步到 `72d3b1e3`（6.1.0-prealpha，+529 提交），合并 13 个双方都改过的文件、
  2 处手工解冲突，历史整理为 7 个线性提交并删除 `feat/clipping-mask`
