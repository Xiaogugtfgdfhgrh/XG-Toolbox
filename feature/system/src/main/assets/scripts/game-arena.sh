#!/system/bin/sh
set -u

GAME="暗区突围"
PKG="com.tencent.mf.uam"
DATA="/sdcard/Android/data/$PKG"
CANON_ROOT="/storage/emulated/0/Android/data/$PKG"
EXT_DIRS="$DATA/cache $DATA/files/log $DATA/files/logs $DATA/files/crash $DATA/files/tmp"
ANO_USER="/data/user/0/$PKG/files/ano_tmp"
ANO_DATA="/data/data/$PKG/files/ano_tmp"

if [ "$(id -u 2>/dev/null)" != "0" ]; then
  echo "[XG] 需要 Root 权限才能清理" >&2
  echo "XG_DONE 0"
  exit 2
fi

probe=$(pm list packages "$PKG" 2>/dev/null)
probe_rc=$?
if [ "$probe_rc" -ne 0 ]; then
  echo "[XG] pm 不可用，安全跳过" >&2
  echo "XG_DONE 0"
  exit 4
fi
if ! echo "$probe" | grep -qx "package:$PKG"; then
  echo "[XG] $GAME 未安装，跳过"
  echo "XG_DONE 0"
  exit 0
fi

running=0
if command -v pidof >/dev/null 2>&1; then
  if [ -n "$(pidof "$PKG" 2>/dev/null)" ]; then
    running=1
  fi
elif ps -A -o NAME 2>/dev/null | grep -qx "$PKG"; then
  running=1
fi

hit=0
before_total=0
targets=""
for dir in $EXT_DIRS; do
  [ -d "$dir" ] || continue
  canonical=$(readlink -f "$dir" 2>/dev/null)
  [ -n "$canonical" ] || continue
  case "$canonical" in
    "$CANON_ROOT"/*) ;;
    *) continue ;;
  esac
  hit=$((hit + 1))
  rel=${canonical#"$CANON_ROOT"/}
  targets="$targets $rel"
  size=$(du -sk "$canonical" 2>/dev/null | awk '{print $1}')
  [ -n "$size" ] || size=0
  before_total=$((before_total + size))
done

ano_dir=""
if [ "$running" -eq 0 ] && [ -d "$ANO_USER" ]; then
  canonical=$(readlink -f "$ANO_USER" 2>/dev/null)
  case "$canonical" in
    "$ANO_USER"|"$ANO_DATA") ano_dir=$canonical ;;
  esac
fi
if [ -n "$ano_dir" ]; then
  hit=$((hit + 1))
  targets="$targets ano_tmp"
  size=$(du -sk "$ano_dir" 2>/dev/null | awk '{print $1}')
  [ -n "$size" ] || size=0
  before_total=$((before_total + size))
fi

if [ "$running" -eq 1 ]; then
  echo "[XG] $GAME 正在运行，已跳过 ano_tmp（退出游戏后再执行可清理）"
fi
if [ "$hit" -eq 0 ]; then
  echo "[XG] $GAME 暂无可清理缓存/ano_tmp"
  echo "XG_DONE 0"
  exit 0
fi
targets=${targets# }
echo "[XG] $GAME 删除目标：$targets（$before_total KB）"

fail=0
for dir in $EXT_DIRS; do
  [ -d "$dir" ] || continue
  canonical=$(readlink -f "$dir" 2>/dev/null)
  [ -n "$canonical" ] || continue
  case "$canonical" in
    "$CANON_ROOT"/*) ;;
    *) continue ;;
  esac
  find "$canonical" -mindepth 1 -delete 2>/dev/null || fail=$((fail + 1))
done
if [ -n "$ano_dir" ]; then
  find "$ano_dir" -mindepth 1 -delete 2>/dev/null || fail=$((fail + 1))
fi

after_total=0
for dir in $EXT_DIRS; do
  [ -d "$dir" ] || continue
  canonical=$(readlink -f "$dir" 2>/dev/null)
  [ -n "$canonical" ] || continue
  case "$canonical" in
    "$CANON_ROOT"/*) ;;
    *) continue ;;
  esac
  size=$(du -sk "$canonical" 2>/dev/null | awk '{print $1}')
  [ -n "$size" ] || size=0
  after_total=$((after_total + size))
done
if [ -n "$ano_dir" ]; then
  size=$(du -sk "$ano_dir" 2>/dev/null | awk '{print $1}')
  [ -n "$size" ] || size=0
  after_total=$((after_total + size))
fi

freed=0
if [ "$before_total" -gt "$after_total" ]; then
  freed=$(((before_total - after_total) * 1024))
fi

if [ "$fail" -gt 0 ]; then
  echo "[XG] $fail 个目标删除失败（部分失败）" >&2
fi
echo "清理 $GAME：释放 $freed 字节"
echo "XG_DONE $freed"
if [ "$fail" -gt 0 ]; then
  exit 3
fi
