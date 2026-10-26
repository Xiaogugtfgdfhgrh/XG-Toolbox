#!/system/bin/sh
set -u

if [ "$(id -u 2>/dev/null)" != "0" ]; then
  echo "[XG] 需要 Root 权限才能清理 OBB 残留" >&2
  echo "XG_DONE 0"
  exit 2
fi

OBB_ROOT="/sdcard/Android/obb"
OBB_CANONICAL_ROOT="/storage/emulated/0/Android/obb"

installed_raw=$(pm list packages 2>/dev/null)
pm_rc=$?
if [ "$pm_rc" -ne 0 ]; then
  echo "[XG] pm list packages 执行失败（退出码 $pm_rc），为安全起见跳过" >&2
  echo "XG_DONE 0"
  exit 4
fi
installed=$(echo "$installed_raw" | sed 's/^package://')
if [ -z "$installed" ]; then
  echo "[XG] 无法确认已安装列表（pm 输出为空），跳过" >&2
  echo "XG_DONE 0"
  exit 4
fi

freed=0
fail=0
hit=0
skipped=0

for dir in "$OBB_ROOT"/*; do
  [ -d "$dir" ] || continue
  name=$(basename "$dir")
  case "$name" in
    *.*) ;;
    *) continue ;;
  esac

  canonical=$(readlink -f "$dir" 2>/dev/null)
  if [ -z "$canonical" ]; then
    skipped=$((skipped + 1))
    continue
  fi
  rel=${canonical#"$OBB_CANONICAL_ROOT"/}
  case "$rel" in
    "$canonical"|*/*|"")
      skipped=$((skipped + 1))
      continue
      ;;
  esac

  if echo "$installed" | grep -qx "$name"; then
    continue
  fi
  probe=$(pm list packages "$name" 2>/dev/null)
  probe_rc=$?
  if [ "$probe_rc" -ne 0 ]; then
    skipped=$((skipped + 1))
    continue
  fi
  if echo "$probe" | grep -qx "package:$name"; then
    continue
  fi

  recent=$(find "$canonical" -maxdepth 0 -mtime -7 2>/dev/null)
  if [ -n "$recent" ]; then
    skipped=$((skipped + 1))
    continue
  fi

  hit=$((hit + 1))
  before=$(du -sk "$canonical" 2>/dev/null | awk '{print $1}')
  [ -n "$before" ] || before=0
  echo "[XG] 删除已卸载游戏残留：$canonical （$before KB）"
  rm -rf "$canonical" 2>/dev/null || fail=$((fail + 1))
  after=$(du -sk "$canonical" 2>/dev/null | awk '{print $1}')
  [ -n "$after" ] || after=0
  if [ "$before" -gt "$after" ]; then
    freed=$((freed + (before - after) * 1024))
  fi
done

if [ "$hit" -eq 0 ]; then
  echo "[XG] 未发现可清理的 OBB 残留（跳过 $skipped 个）"
fi
if [ "$fail" -gt 0 ]; then
  echo "[XG] $fail 个目录删除失败（部分失败）" >&2
fi
echo "清理已卸载游戏 OBB：释放 $freed 字节"
echo "XG_DONE $freed"
if [ "$fail" -gt 0 ]; then
  exit 3
fi
