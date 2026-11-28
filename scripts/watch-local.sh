#!/bin/zsh
# Daily source check, run from this Mac (GitHub's US servers get blocked by several sites).
#
#   scripts/watch-local.sh install     schedule it every day at 08:00 (a missed run happens on wake)
#   scripts/watch-local.sh uninstall   remove the schedule
#   scripts/watch-local.sh run         check now (what the schedule runs)
#
# It works in its own clean copy of the "code" branch (your working folder is never touched), saves
# the status table + history to the "watch" branch, then starts the GitHub workflow with notify=true
# so the Telegram message is sent with the bot token kept in GitHub. Needs: gh (logged in), Java, Android SDK.
set -u

REPO_SLUG="Latrixz/cloudstream-asian"
REPO="https://github.com/$REPO_SLUG.git"
BASE="$HOME/Library/Application Support/csasian-watch"
LABEL="com.csasian.watch"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
[ -n "${JAVA_HOME:-}" ] || export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
# Push with gh's login (works from launchd, no separate git credentials needed).
GIT_AUTH=(-c credential.helper= -c "credential.helper=!gh auth git-credential")

case "${1:-run}" in
install)
  mkdir -p "$BASE" "$HOME/Library/LaunchAgents"
  here="$(cd "$(dirname "$0")/.." && pwd)"
  cp "$0" "$BASE/run.sh" && chmod +x "$BASE/run.sh"   # a stable copy: the clone below changes while it runs
  sdk="$(grep '^sdk.dir=' "$here/local.properties" 2>/dev/null | cut -d= -f2-)"
  echo "sdk.dir=${sdk:-$HOME/Library/Android/sdk}" > "$BASE/local.properties"
  cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/zsh</string><string>$BASE/run.sh</string><string>run</string></array>
  <key>StartCalendarInterval</key><dict><key>Hour</key><integer>8</integer><key>Minute</key><integer>0</integer></dict>
  <key>StandardOutPath</key><string>$BASE/watch.log</string>
  <key>StandardErrorPath</key><string>$BASE/watch.log</string>
  <key>ProcessType</key><string>Background</string>
</dict></plist>
EOF
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null
  launchctl bootstrap "gui/$(id -u)" "$PLIST" && echo "Scheduled daily at 08:00. Log: $BASE/watch.log"
  exit ;;
uninstall)
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null; rm -f "$PLIST"; echo "Schedule removed."
  exit ;;
run) ;;
*) echo "usage: $0 [install|uninstall|run]"; exit 2 ;;
esac

echo "=== $(date '+%F %T') source check"
mkdir -p "$BASE" && cd "$BASE" || exit 1

# Fresh copies of the code and the status history (only this folder's own clones are reset).
[ -d code/.git ] || git clone -q --branch code "$REPO" code || exit 1
git -C code fetch -q origin code && git -C code reset -q --hard origin/code && git -C code clean -qfdx -e .gradle -e build -e local.properties
cp "$BASE/local.properties" code/local.properties
[ -d state/.git ] || git clone -q --branch watch "$REPO" state || exit 1
git -C state fetch -q origin watch && git -C state reset -q --hard origin/watch

cd code
rm -rf build/watch
./gradlew watch --parallel --continue -q > "$BASE/gradle.log" 2>&1
sleep 60   # then re-check the failures once: sites are often just flaky
tasks=$(python3 -c "import json,glob;print(' '.join(':'+d['module']+':testReleaseUnitTest' for d in map(lambda p: json.load(open(p)), glob.glob('build/watch/*.json')) if d['status'] not in ('playable','unverified')))")
echo "Re-checking: ${tasks:-none}"
[ -z "$tasks" ] || ./gradlew ${=tasks} --parallel --continue -q >> "$BASE/gradle.log" 2>&1
n=$(ls build/watch/*.json 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" -lt 10 ]; then echo "Only $n results; the build probably failed (see gradle.log). Not reporting."; exit 1; fi

REPORT_URL="https://github.com/$REPO_SLUG/blob/watch/README.md" TELEGRAM_MESSAGE_FILE="$BASE/state/telegram.txt" \
  python3 scripts/watch-report.py build/watch "$BASE/state" | sed -n 3p

cd "$BASE/state"
git add -A
git -c user.name="Source watcher (Mac)" -c user.email="actions@github.com" commit -q -m "Source check $(date +%F) (Mac)"
git "${GIT_AUTH[@]}" push -q origin HEAD:watch || { echo "push failed"; exit 1; }
gh workflow run watch.yml -R "$REPO_SLUG" --ref code -f notify=true && echo "Telegram report requested."
