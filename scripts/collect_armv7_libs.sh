#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 刷新 32 位 (armeabi-v7a) 预编译动态库闭包
#
# 与 arm64 的 scripts/refresh-prebuilt-jni.sh 对应, 但 32 位这套是「整包重收」:
# Krita/Qt/KF6 闭包 + ReverieCore 自己的两个 .so 一起落到
# third_party/android-native-libs-armeabi-v7a/。
#
# 为什么必须重收而不是单刷 jni:
#   闭包里的 Krita 库必须与编译 ReverieCore 时用的头文件同源, 否则拆库后
#   的 libreverie_jni.so/libreverie_core.so 会 NEEDED 到旧库上, 运行期
#   dlopen 或首次调用就挂 (2026-10-31 那一版 32 位包正是用 10-18 的旧闭包
#   配 10-28 的 Kotlin)。
#
# 前置:
#   - Krita 已有 armv7 构建目录 (默认 ~/Projects/krita-source/build-android-armv7)
#   - Qt for Android armv7 (默认 ~/Qt6/6.6.3/android_armv7)
#   - deps-armv7 KF6/mypaint 等依赖前缀
#   - ReverieCore 已用 scripts/build_native.sh --abi armeabi-v7a 编出产物
#
# 用法:
#   ./scripts/collect_armv7_libs.sh
#   KRITA_BIN=... QT_ARMV7=... ./scripts/collect_armv7_libs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DEST="${DEST:-$ROOT/third_party/android-native-libs-armeabi-v7a}"
KRITA_BIN="${KRITA_BIN:-$HOME/Projects/krita-source/build-android-armv7}"
DEPS="${DEPS:-$HOME/ReveriePaint-android/deps-armv7}"
QT_ARMV7="${QT_ARMV7:-$HOME/Qt6/6.6.3/android_armv7}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.2.9519653}"
RV_BUILD="${RV_BUILD:-$HOME/rvbuild-armv7}"
REF_ARM64="${REF_ARM64:-$ROOT/third_party/android-native-libs}"

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [ ! -x "$STRIP" ]; then
	echo "找不到 llvm-strip: $STRIP (设置 ANDROID_NDK_HOME 指向 NDK 25.2.9519653)" >&2
	exit 1
fi

for d in "$KRITA_BIN" "$QT_ARMV7" "$DEPS"; do
	if [ ! -d "$d" ]; then
		echo "缺少输入目录: $d (用 KRITA_BIN/QT_ARMV7/DEPS 环境变量覆盖默认路径)" >&2
		exit 1
	fi
done
if ! ls "$KRITA_BIN"/lib/*.so >/dev/null 2>&1; then
	echo "$KRITA_BIN/lib 下没有 .so, 先构建 Krita armv7" >&2
	exit 1
fi

mkdir -p "$DEST"

# 预编译闭包必须是 strip 过的: arm64 那套也是 (AGENTS.md §3), 否则 APK 体积翻十倍
strip_into() {
	local src="$1" name="$2"
	"$STRIP" --strip-unneeded "$src" -o "$DEST/$name"
}

filter_libs() {
	# 排除 cmake 生成的辅助库与测试件
	case "$1" in
		libc++_shared.so | libc++_static.*) return 1 ;;
		*.a | *.cmake | *.o) return 1 ;;
	esac
	return 0
}

echo "== 1/4 Krita 闭包 (strip 后) =="
n=0
for f in "$KRITA_BIN"/lib/*.so; do
	b="$(basename "$f")"
	[[ "$b" == lib* ]] || b="lib$b"
	filter_libs "$b" || continue
	strip_into "$f" "$b"
	n=$((n + 1))
done
echo "   复制 $n 个 Krita 库"

echo "== 2/4 KF6 与三方依赖 =="
for pat in "$DEPS"/lib/libKF6*.so \
	"$DEPS"/lib/libmypaint.so \
	"$DEPS"/lib/libjson-c.so \
	"$DEPS"/lib/libbz2.so \
	"$DEPS"/lib/libpng16.so \
	"$DEPS"/lib/libquazip1-qt6.so \
	"$DEPS"/lib/libintl.so \
	"$DEPS"/lib/libgsl.so \
	"$DEPS"/lib/libgslcblas.so \
	"$DEPS"/lib/liblzma.so \
	"$DEPS"/lib/libzstd.so \
	"$DEPS"/lib/libexpat.so \
	"$DEPS"/lib/libfreetype.so \
	"$DEPS"/lib/libunibreak.so \
	"$DEPS"/lib/libharfbuzz.so \
	"$DEPS"/lib/libfribidi.so \
	"$DEPS"/lib/libraqm.so; do
	[ -f "$pat" ] || continue
	strip_into "$pat" "$(basename "$pat")"
done

echo "== 3/4 Qt 闭包 (与 arm64 保持同一套模块) =="
for f in "$REF_ARM64"/libQt6*.so "$REF_ARM64"/libQtAndroid*.so; do
	[ -f "$f" ] || continue
	b="$(basename "$f")"
	armv7_name="${b//arm64-v8a/armeabi-v7a}"
	if [ -f "$QT_ARMV7/lib/$armv7_name" ]; then
		strip_into "$QT_ARMV7/lib/$armv7_name" "$armv7_name"
	else
		echo "   跳过 (Qt armv7 里没有): $armv7_name" >&2
	fi
done

echo "== 4/4 libc++_shared 与 ReverieCore =="
strip_into "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/arm-linux-androideabi/libc++_shared.so" libc++_shared.so

if [ -f "$RV_BUILD/libreverie_jni.so" ] && [ -f "$RV_BUILD/libreverie_core.so" ]; then
	strip_into "$RV_BUILD/libreverie_jni.so" libreverie_jni.so
	strip_into "$RV_BUILD/libreverie_core.so" libreverie_core.so
	echo "   已写入 ReverieCore 产物 ($RV_BUILD)"
else
	echo "   未找到 ReverieCore 产物 ($RV_BUILD), 先跑 ./scripts/build_native.sh --abi armeabi-v7a" >&2
fi

echo "Collected $(ls -1 "$DEST"/*.so | wc -l) armv7 shared libraries into $DEST"
echo "下一步: ./scripts/check_jni_symbols.sh armeabi-v7a"
