package com.xg.toolbox.core.clone.internal;

/**
 * Builds the detached root migration/uninstall script.
 *
 * <p>Kotlin strings use ${} interpolation, so keeping the shell body in a Java
 * text block avoids accidental expansion and makes the exact script testable.</p>
 */
public final class CloneMigrationScript {
    private CloneMigrationScript() {
    }

    public static String build(
            String oldPackage,
            String newPackage,
            String mainActivity,
            String apkPath,
            String scriptPath,
            String installedMarker,
            String goMarker,
            String cancelMarker,
            String statusPath,
            String logPath
    ) {
        String template = """
#!/system/bin/sh
set -u
PATH=/system/bin:/system/xbin:/sbin:/su/bin:/data/adb/magisk
APK=__APK__
OLD=__OLD__
NEW=__NEW__
MAIN=__MAIN__
SCRIPT=__SCRIPT__
INSTALLED=__INSTALLED__
GO=__GO__
CANCEL=__CANCEL__
STATUS=__STATUS__
LOG=__LOG__
exec >> "$LOG" 2>&1
MIGRATION_STARTED=0

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

write_status() {
    printf '%s\\n' "$1" > "$STATUS" 2>/dev/null || true
}

fail() {
    MESSAGE="$1"
    write_status "FAILED:$MESSAGE"
    log "FAILED:$MESSAGE"
    if [ "$MIGRATION_STARTED" = "0" ]; then
        pm uninstall --user 0 "$NEW" >/dev/null 2>&1 || true
    fi
    rm -f "$APK" "$GO" "$CANCEL" "$INSTALLED"
    rm -f "$SCRIPT"
    exit 10
}

log "clone script start old=$OLD new=$NEW"
log "apk=$APK bytes=$(wc -c < "$APK" 2>/dev/null || echo unknown)"

PM_OUT=$(pm install -r -t --user 0 "$APK" 2>&1)
PM_RC=$?
log "pm install rc=$PM_RC output=$PM_OUT"
[ "$PM_RC" -eq 0 ] || fail "pm_install_rc=$PM_RC"
pm path --user 0 "$NEW" | grep -q '^package:' || fail "pm_path_missing_after_install"
echo "INSTALLED" > "$INSTALLED" 2>/dev/null || fail "installed_marker_write_failed"
log "new package installed and pm path verified; waiting for caller commit"

i=0
while [ ! -f "$GO" ]; do
    if [ -f "$CANCEL" ]; then
        fail "cancelled_before_commit"
    fi
    i=$((i+1))
    if [ "$i" -ge 180 ]; then
        fail "commit_timeout"
    fi
    sleep 1
done

log "commit received; grace period before old process is stopped"
sleep 8
MIGRATION_STARTED=1

pm path --user 0 "$NEW" >/dev/null 2>&1 || fail "new_package_disappeared"
am force-stop --user 0 "$OLD" >/dev/null 2>&1 || log "old force-stop rc=$?"
sleep 1

START1=$(am start --user 0 -W -n "$NEW/$MAIN" 2>&1)
START1_RC=$?
log "first new launch rc=$START1_RC output=$START1"
[ "$START1_RC" -eq 0 ] || fail "first_launch_rc=$START1_RC"
echo "$START1" | grep -q 'Status: ok' || fail "first_launch_status_not_ok"
sleep 2
am force-stop --user 0 "$NEW" >/dev/null 2>&1 || true

NEW_UID=$(pm list packages -U --user 0 "$NEW" 2>/dev/null | sed -n 's/.*uid:\\([0-9][0-9]*\\).*/\\1/p' | head -n 1)
NEW_DIR=$(dumpsys package "$NEW" | sed -n 's/.*dataDir=\\([^ ]*\\).*/\\1/p' | head -n 1)
OLD_DIR=$(dumpsys package "$OLD" | sed -n 's/.*dataDir=\\([^ ]*\\).*/\\1/p' | head -n 1)
[ -n "$NEW_UID" ] || NEW_UID=$(stat -c '%u' "/data/user/0/$NEW" 2>/dev/null || true)
[ -n "$NEW_DIR" ] || NEW_DIR="/data/user/0/$NEW"
[ -n "$OLD_DIR" ] || OLD_DIR="/data/user/0/$OLD"
[ -d "$NEW_DIR" ] || NEW_DIR="/data/data/$NEW"
[ -d "$OLD_DIR" ] || OLD_DIR="/data/data/$OLD"
[ -n "$NEW_UID" ] || fail "new_uid_unresolved"
[ -d "$NEW_DIR" ] || fail "new_data_dir_missing"
[ -d "$OLD_DIR" ] || fail "old_data_dir_missing"
log "uid=$NEW_UID newDir=$NEW_DIR oldDir=$OLD_DIR"

mkdir -p "$NEW_DIR/files" || fail "new_files_dir"
MARKER="$NEW_DIR/files/xg_self_clone_state.json"
write_marker() {
    STATE="$1"
    printf '{"schema":1,"state":"%s","old_package":"%s","new_package":"%s","timestamp":"%s"}\\n' \
        "$STATE" "$OLD" "$NEW" "$(date +%s)" > "$MARKER" 2>/dev/null || return 1
    chown "$NEW_UID:$NEW_UID" "$MARKER" 2>/dev/null || true
    chmod 0600 "$MARKER" 2>/dev/null || true
}
write_marker "copying" || log "pre-copy marker write failed"

cd "$OLD_DIR" || fail "old_data_chdir"
find . -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache ! -name lib \
    -exec cp -a {} "$NEW_DIR/" ';' || fail "data_copy_failed"
cd / || true

chown -R "$NEW_UID:$NEW_UID" "$NEW_DIR" || fail "data_chown_failed"
if command -v restorecon >/dev/null 2>&1; then
    restorecon -R "$NEW_DIR" >/dev/null 2>&1 || log "restorecon failed (best effort)"
else
    log "restorecon unavailable (best effort skipped)"
fi
write_marker "data_copied" || fail "marker_after_copy_failed"

START2=$(am start --user 0 -W -n "$NEW/$MAIN" 2>&1)
START2_RC=$?
log "post-migration launch rc=$START2_RC output=$START2"
[ "$START2_RC" -eq 0 ] || fail "post_migration_launch_rc=$START2_RC"
echo "$START2" | grep -q 'Status: ok' || fail "post_migration_launch_status_not_ok"
sleep 5
if command -v pidof >/dev/null 2>&1; then
    pidof "$NEW" >/dev/null 2>&1 || fail "new_process_not_running"
else
    ps -A 2>/dev/null | grep -q "$NEW" || fail "new_process_not_running_ps"
fi
write_marker "launched" || log "launched marker write failed"

UNINSTALL_OUT=$(pm uninstall --user 0 "$OLD" 2>&1)
UNINSTALL_RC=$?
log "old uninstall rc=$UNINSTALL_RC output=$UNINSTALL_OUT"
[ "$UNINSTALL_RC" -eq 0 ] || fail "old_uninstall_rc=$UNINSTALL_RC"
write_marker "old_uninstalled" || log "final marker write failed"

rm -f "$APK" "$GO" "$CANCEL" "$INSTALLED"
write_status "SUCCESS:$NEW"
log "SUCCESS old=$OLD new=$NEW"
rm -f "$SCRIPT"
exit 0
""";
        return template
                .replace("__APK__", shellQuote(apkPath))
                .replace("__OLD__", shellQuote(oldPackage))
                .replace("__NEW__", shellQuote(newPackage))
                .replace("__MAIN__", shellQuote(mainActivity))
                .replace("__SCRIPT__", shellQuote(scriptPath))
                .replace("__INSTALLED__", shellQuote(installedMarker))
                .replace("__GO__", shellQuote(goMarker))
                .replace("__CANCEL__", shellQuote(cancelMarker))
                .replace("__STATUS__", shellQuote(statusPath))
                .replace("__LOG__", shellQuote(logPath));
    }

    /** Quotes an argument for /system/bin/sh. */
    public static String shellQuote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

