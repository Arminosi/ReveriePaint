#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Krita 上游同步门禁
#
# 作用: 在真正 merge/rebase 之前，用 3-way 合并预演回答三个问题:
#   1. 上游这几个月动了多少提交、双方各自领先多少
#   2. 合并会不会冲突，冲突是否都落在 patches/krita/hooks.txt 里预期的文件上
#   3. 我们补丁提供的「契约符号」在合并结果里是否还活着（剪切蒙版入口、静态注册、
#      QtGlobal 修补、视频编码桩、LibAV 禁用、marker painter 的 COMPOSITE_OVER 等）
#
# 用法:
#   scripts/check_krita_sync.sh                 # 默认 ~/Projects/krita-source + origin/master
#   scripts/check_krita_sync.sh --no-fetch      # 不拉上游，用现有的 origin/master
#   scripts/check_krita_sync.sh --krita /path/to/krita --fork reverie-android --upstream origin/master
#
# 退出码: 0 通过 / 1 有失败项 (冲突越界或契约丢失) / 2 环境问题
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOKS_TXT="$REPO_ROOT/patches/krita/hooks.txt"
KRITA="${KRITA_SRC:-$HOME/Projects/krita-source}"
FORK=""
UPSTREAM="origin/master"
DO_FETCH=1

while [ $# -gt 0 ]; do
	case "$1" in
		--krita) KRITA="$2"; shift 2 ;;
		--fork) FORK="$2"; shift 2 ;;
		--upstream) UPSTREAM="$2"; shift 2 ;;
		--no-fetch) DO_FETCH=0; shift ;;
		-h|--help) sed -n '2,20p' "$0"; exit 0 ;;
		*) echo "未知参数: $1" >&2; exit 2 ;;
	esac
done

[ -d "$KRITA/.git" ] || { echo "不是 git 仓库: $KRITA" >&2; exit 2; }
[ -f "$HOOKS_TXT" ] || { echo "缺少 hooks 清单: $HOOKS_TXT" >&2; exit 2; }

GIT_VERSION_OK=$(git -C "$KRITA" merge-tree -h 2>&1 | grep -c -- "--write-tree" || true)
[ "$GIT_VERSION_OK" -gt 0 ] || { echo "git 版本过旧 (需要 merge-tree --write-tree, git >= 2.38)" >&2; exit 2; }

cd "$KRITA" || exit 2
[ -n "$FORK" ] || FORK="$(git rev-parse --abbrev-ref HEAD)"

echo "== Krita 同步体检"
echo "   仓库   : $KRITA"
echo "   本地   : $FORK @ $(git log -1 --format='%h %cs %s' "$FORK" | cut -c1-72)"
if [ "$DO_FETCH" = 1 ]; then
	echo "   上游   : 正在 fetch ${UPSTREAM#origin/} …"
	git fetch --no-tags -q "${UPSTREAM%%/*}" "${UPSTREAM#*/}" || { echo "fetch 失败"; exit 2; }
fi
echo "   上游   : $UPSTREAM @ $(git log -1 --format='%h %cs %s' "$UPSTREAM" | cut -c1-72)"

BASE=$(git merge-base "$FORK" "$UPSTREAM" 2>/dev/null) || { echo "找不到 merge-base（浅克隆？）"; exit 2; }
echo "   分叉点 : $(git log -1 --format='%h %cs' "$BASE")"
echo "   领先/落后: $(git rev-list --count "$UPSTREAM".."$FORK") / $(git rev-list --count "$FORK".."$UPSTREAM")"

echo
echo "== 3-way 合并预演"
MERGE_OUT=$(git merge-tree --write-tree --name-only "$FORK" "$UPSTREAM" 2>/dev/null)
MERGE_RC=$?
MERGED_TREE=$(printf '%s\n' "$MERGE_OUT" | head -1)
CONFLICTS=$(printf '%s\n' "$MERGE_OUT" | tail -n +2 | sed '/^$/,$d' | grep -v '^$' || true)
CONFLICT_COUNT=$(printf '%s' "$CONFLICTS" | grep -c . || true)

FAIL=0
if [ "$CONFLICT_COUNT" -eq 0 ]; then
	echo "   结果   : 无冲突 (合并树 $MERGED_TREE)"
else
	echo "   结果   : $CONFLICT_COUNT 个文件冲突"
	UNEXPECTED=0
	while IFS= read -r f; do
		[ -n "$f" ] || continue
		if grep -qx -- "$f" <(grep -v '^#' "$HOOKS_TXT") ; then
			echo "     ~ $f   (预期内, 按 HOOKS.md 的策略人工解冲突)"
		else
			echo "     ! $f   (不在 hooks.txt 里 —— 上游改到了我们没碰过的文件, 需要确认)"
			UNEXPECTED=$((UNEXPECTED + 1))
		fi
	done <<< "$CONFLICTS"
	[ "$UNEXPECTED" -eq 0 ] || { echo "   失败   : 出现 $UNEXPECTED 个越界冲突"; FAIL=1; }
fi

echo
echo "== 补丁契约检查 (在合并结果树上验证)"
check_contains() { # path, pattern, label
	local path="$1" pattern="$2" label="$3"
	local content
	content=$(git cat-file -p "$MERGED_TREE:$path" 2>/dev/null) || content=""
	# 注意: 不要用 `git cat-file | grep -q`, pipefail 下 grep 提前退出会让整条管道失败
	if grep -q -- "$pattern" <<<"$content"; then
		printf '   ok   %s\n' "$label"
	else
		printf '   LOST %s  (%s 里找不到 %s)\n' "$label" "$path" "$pattern"
		FAIL=1
	fi
}
check_exists() { # path, label
	if git cat-file -e "$MERGED_TREE:$1" 2>/dev/null; then
		printf '   ok   %s\n' "$2"
	else
		printf '   LOST %s  (%s 不存在)\n' "$2" "$1"
		FAIL=1
	fi
}

# --- 剪切蒙版 (按现状保留, 只验入口还在) ---
check_contains libs/image/kis_layer.h                 'enableClippingLayer'  '剪切蒙版: KisLayer 入口'
check_contains libs/image/kis_layer.cc                'enableClippingLayer'  '剪切蒙版: KisLayer 实现'
check_contains libs/image/kis_layer_projection_plane.cpp 'clipping'          '剪切蒙版: 投影递归'
check_contains libs/image/kis_node.cpp                'clipping'             '剪切蒙版: 节点链表'
check_contains plugins/impex/libkra/kis_kra_loader.cpp  'enableClippingLayer' '剪切蒙版: KRA 读回'
check_contains plugins/impex/libkra/kis_kra_tags.h    'clipping'             '剪切蒙版: KRA 标签'
check_contains plugins/impex/psd/psd_loader.cpp       'enableClippingLayer'  '剪切蒙版: PSD 读回'

# --- 笔刷 / 存储修复 ---
check_contains libs/image/kis_marker_painter.cpp      'COMPOSITE_OVER'       'roundmarker: COMPOSITE_OVER 混合'
check_contains libs/image/tiles3/swap/kis_chunk_allocator.cpp 'KisChunkAllocator' 'swap: 分配器修复'
check_contains libs/global/kis_lockless_stack.h       '#include <QtGlobal>'  'Qt 6.6: QtGlobal 修补'
check_contains plugins/paintops/mypaint/MyPaintSensorPack.cpp 'MyPaintSensorPack' 'mypaint: 传感器懒初始化'

# --- Android 构建 ---
check_contains CMakeLists.txt                         'FFmpeg disabled'      'Android: LibAV 禁用'
check_contains libs/ui/CMakeLists.txt                 'KisMediaEncoderRunnables_stub.cpp' 'Android: 视频编码桩已接线'
check_exists   libs/ui/animation/KisMediaEncoderRunnables_stub.cpp 'Android: 视频编码桩文件'
check_contains cmake/modules/FindLibMyPaint.cmake     'json-c'                'Android: mypaint 依赖'
for d in colorsmudge curvebrush defaultpaintops deform experiment filterop gridbrush \
         hairy hatching mypaint particle roundmarker sketch spray tangentnormal; do
	check_exists "plugins/paintops/$d/register_static.cpp" "Android: $d 静态注册"
done

echo
if [ "$FAIL" -eq 0 ]; then
	echo "== 通过: 冲突在预期范围内, 补丁契约完整"
	echo "   下一步: git -C $KRITA checkout -B $FORK $UPSTREAM && git cherry-pick <旧提交系列>"
	exit 0
else
	echo "== 未通过: 先按 patches/krita/HOOKS.md 处理后重跑"
	exit 1
fi
