#!/system/bin/sh
# InnerDesk privileged daemon. Started once per boot via adb or Shizuku.
# Also tears down overlay_display_devices if the app process is gone.
trap '' HUP
PIDFILE=/data/local/tmp/idx-shizuku-watch.pid
LOG=/data/local/tmp/idx-shizuku-watch.log
NICE=idxprivd
PKG=dev.zanderp.innerdesk

if [ -f "$PIDFILE" ]; then
  old=$(cat "$PIDFILE")
  if [ -n "$old" ] && [ -d "/proc/$old" ] && [ "$old" != "$$" ]; then
    if tr '\0' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -q idx-shizuku-watch; then
      kill "$old" 2>/dev/null
      sleep 0.2
    fi
  fi
fi
echo $$ > "$PIDFILE"
echo -1000 > /proc/$$/oom_score_adj 2>/dev/null

APK=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
APP_UID=$(pm list packages -U "$PKG" 2>/dev/null | head -1 | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
APP_UID=${APP_UID%%,*}
[ -n "$APP_UID" ] || APP_UID=0

echo "$(date) watchdog start pid=$$ apk=$APK uid=$APP_UID" >> "$LOG"

app_alive() {
  [ "$APP_UID" -gt 0 ] || return 0
  if ps -A -o UID= 2>/dev/null | grep -qw "$APP_UID"; then
    return 0
  fi
  for s in /proc/[0-9]*/status; do
    [ -r "$s" ] || continue
    uid=$(sed -n 's/^Uid:[[:space:]]*\([0-9]*\).*/\1/p' "$s" | head -1)
    [ "$uid" = "$APP_UID" ] && return 0
  done
  return 1
}

overlay_on() {
  v=$(settings get global overlay_display_devices 2>/dev/null | tr -d '\r')
  [ -n "$v" ] && [ "$v" != "null" ] && [ "$v" != "none" ]
}

tear_overlay() {
  settings put global overlay_display_devices none >/dev/null 2>&1
  settings delete global overlay_display_devices >/dev/null 2>&1
  settings put global overlay_display_devices "" >/dev/null 2>&1
  settings delete global overlay_display_devices >/dev/null 2>&1
}

MISS=0
while true; do
  echo -1000 > /proc/$$/oom_score_adj 2>/dev/null
  existing=$(pidof $NICE 2>/dev/null)
  if [ -n "$existing" ]; then
    for p in $existing; do
      echo -1000 > /proc/$p/oom_score_adj 2>/dev/null
    done
  else
    if [ -z "$APK" ] || [ ! -f "$APK" ]; then
      APK=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
    fi
    if [ "$APP_UID" -le 0 ]; then
      APP_UID=$(pm list packages -U "$PKG" 2>/dev/null | head -1 | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
      APP_UID=${APP_UID%%,*}
      [ -n "$APP_UID" ] || APP_UID=0
    fi
    if [ -n "$APK" ]; then
      echo "$(date) starting $NICE uid=$APP_UID" >> "$LOG"
      CLASSPATH="$APK" setsid /system/bin/app_process /system/bin --nice-name=$NICE dev.zanderp.innerdesk.PrivDaemon "$APP_UID" </dev/null >> "$LOG" 2>&1 &
    else
      echo "$(date) no apk" >> "$LOG"
    fi
  fi

  if overlay_on; then
    if app_alive; then
      MISS=0
    else
      MISS=$((MISS + 1))
      echo "$(date) overlay orphan miss=$MISS uid=$APP_UID" >> "$LOG"
      if [ "$MISS" -ge 3 ]; then
        echo "$(date) tearing down orphan overlay" >> "$LOG"
        tear_overlay
        MISS=0
      fi
    fi
  else
    MISS=0
  fi
  sleep 2
done
