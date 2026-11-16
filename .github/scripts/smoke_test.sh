#!/usr/bin/env bash
# Janky Paper - boots a freshly built server jar once before CI publishes it (build.yml, "Smoke Test").
#
# Servers update themselves to new releases (updates.auto-update), so a jar that compiles and passes the unit tests
# but cannot start must never reach the ci-builds pre-release. This starts the Paperclip jar in an empty folder with a
# Paper-style config (so the config migration runs too), waits for "Done (", stops it from the console and fails on
# a crash, a non-zero exit, a crash report or a missing/wrong build id.
#
#   smoke_test.sh <paperclip jar> [expected Janky-Build-Id]
#
# Environment: SMOKE_DIR (work folder, default $RUNNER_TEMP/janky-smoke), SMOKE_TIMEOUT (seconds to wait for start-up,
# default 600), SMOKE_JAVA (java command, default java).
set -euo pipefail

jar="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
expected_id="${2:-}"
dir="${SMOKE_DIR:-${RUNNER_TEMP:-/tmp}/janky-smoke}"
timeout="${SMOKE_TIMEOUT:-600}"
java="${SMOKE_JAVA:-java}"

fail() {
    echo "::error::Smoke test: $1"
    if [ -f "$dir/server.log" ]; then
        echo "----- last 150 lines of the server log -----"
        tail -n 150 "$dir/server.log"
    fi
    exit 1
}

rm -rf "$dir"
mkdir -p "$dir/config"
cd "$dir"
echo "eula=true" > eula.txt
# Paper's old defaults, as on a server switched from Paper: the config migration must move them and keep the server up
cat > server.properties <<'EOF'
online-mode=false
server-ip=127.0.0.1
server-port=25599
max-players=20
level-type=minecraft\:flat
generate-structures=false
view-distance=3
simulation-distance=3
spawn-protection=0
EOF

: > console.txt
# -Dpaper.disableStartupVersionCheck: no update check (and so no auto-update) against GitHub from CI
tail -f console.txt | { "$java" -Xms1G -Xmx2G -Dpaper.disableStartupVersionCheck=true -jar "$jar" --nogui > server.log 2>&1; echo $? > java.exit; } &
pid=$!

start=$SECONDS
until grep -q 'Done (' server.log 2>/dev/null; do
    if ! kill -0 "$pid" 2>/dev/null; then
        fail "the server exited before start-up finished"
    fi
    if (( SECONDS - start > timeout )); then
        kill "$pid" 2>/dev/null || true
        fail "no 'Done (' within ${timeout}s"
    fi
    sleep 2
done
echo "Started in $(( SECONDS - start ))s"

echo "stop" >> console.txt
stopped=$SECONDS
while kill -0 "$pid" 2>/dev/null; do
    if (( SECONDS - stopped > 120 )); then
        kill -9 "$pid" 2>/dev/null || true
        fail "the server did not stop within 120s"
    fi
    sleep 1
done
echo >> console.txt # the tail feeding the console only notices its reader is gone on its next write
wait "$pid" || true # the pipeline's status is tail's SIGPIPE; java's own is in java.exit
code="$(cat java.exit 2>/dev/null || echo missing)"
[ "$code" = "0" ] || fail "the server exited with code $code"

if ls crash-reports/*.txt >/dev/null 2>&1; then
    cat crash-reports/*.txt
    fail "a crash report was written"
fi
if grep -q -E 'Exception in server tick loop|Encountered an unexpected exception|Failed to start the minecraft server' server.log; then
    fail "the log shows a server crash"
fi
grep -q 'max-players=2000000' server.properties || fail "the config migration did not move max-players to the Janky default"
grep -q '^config-version:' config/janky-paper.yml || fail "config/janky-paper.yml has no config-version"

if [ -n "$expected_id" ]; then
    server_jar="$(find versions -name '*.jar' | head -n 1)"
    [ -n "$server_jar" ] || fail "Paperclip did not unpack a server jar"
    id="$(unzip -p "$server_jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Janky-Build-Id: //p')"
    [ "$id" = "$expected_id" ] || fail "the jar's Janky-Build-Id is '$id', expected '$expected_id'"
    echo "Build id: $id"
fi
echo "Smoke test passed"
