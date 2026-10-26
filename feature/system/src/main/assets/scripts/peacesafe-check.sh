#!/system/bin/sh
set -u

BACKUP="/data/local/tmp/xg/peacesafe-backup-$(date +%Y%m%d_%H%M%S)"
HIT=0

hit() {
  HIT=$((HIT + 1))
  echo "[可疑] $1"
}

backup_path() {
  [ -e "$1" ] || return 0
  mkdir -p "$BACKUP" 2>/dev/null
  cp -a "$1" "$BACKUP/" 2>/dev/null
}

if [ "$(id -u 2>/dev/null)" != "0" ]; then
  echo "[XG] 需要 Root 权限"
  echo "XG_DONE 0"
  exit 2
fi

if [ -d /data/system/devicehub ]; then
  hit "发现 /data/system/devicehub"
  backup_path /data/system/devicehub
  echo "[XG] 已备份并删除 /data/system/devicehub"
  rm -rf /data/system/devicehub
else
  echo "[正常] 未发现 /data/system/devicehub"
fi

if [ -d /data/adb/service.d ]; then
  files=$(grep -l -e devicehub -e 360nb /data/adb/service.d/* 2>/dev/null)
  if [ -n "$files" ]; then
    for f in $files; do
      hit "发现自启脚本 $f"
      backup_path "$f"
      echo "[XG] 已备份并删除 $f"
      rm -f "$f"
    done
  else
    echo "[正常] service.d 无相关脚本"
  fi
fi

pkgs=$(pm list packages 2>/dev/null | grep -E 'devicehub|360nb' | sed 's/^package://')
if [ -n "$pkgs" ]; then
  for p in $pkgs; do
    hit "发现应用 $p"
    echo "[XG] 卸载 $p"
    pm uninstall --user 0 "$p" 2>/dev/null
  done
else
  echo "[正常] 未安装 devicehub/360nb 应用"
fi

procs=$(ps -A 2>/dev/null | grep -iE 'devicehub|360nb' | grep -v grep)
if [ -n "$procs" ]; then
  hit "发现相关进程"
  echo "$procs"
  for pid in $(echo "$procs" | awk '{print $2}'); do
    kill -9 "$pid" 2>/dev/null
    echo "[XG] 已结束进程 $pid"
  done
else
  echo "[正常] 无相关进程"
fi

c2=$(awk 'NR > 1 { print $3 }' /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -iE '2D737F4A|4A7F732D')
if [ -n "$c2" ]; then
  hit "发现与 45.115.127.74 相关的 TCP 记录"
fi

for x in /data/local/tmp/.dh-fetch.* /data/data/keybox_update /data/adb/teesim; do
  if [ -e "$x" ]; then
    hit "发现残留 $x"
    backup_path "$x"
    echo "[XG] 已备份并删除 $x"
    rm -rf "$x"
  fi
done

if [ "$HIT" -eq 0 ]; then
  echo "检查完成：未发现 DeviceHub/360nb 可疑项"
else
  echo "清理完成：发现并处理 $HIT 项；备份目录 $BACKUP"
fi
echo "XG_DONE 0"
