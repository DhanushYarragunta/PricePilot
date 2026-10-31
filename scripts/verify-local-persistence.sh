#!/usr/bin/env bash
# Test migrations and persistence on a disposable loopback-only PostgreSQL cluster.
# Uses fake test data. Does not load .env, access providers, or touch the app database.
set -euo pipefail
PRICEPILOT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for executable in initdb pg_ctl createdb python3 java; do
  if ! command -v "$executable" >/dev/null; then
    echo "Missing prerequisite: $executable. Install local PostgreSQL/Java tools first." >&2
    exit 1
  fi
done
PRICEPILOT_TEST_DIR="$(mktemp -d "/tmp/pricepilot-persistence.XXXXXX")"
PRICEPILOT_TEST_PORT="$(python3 - <<'PY'
import socket
with socket.socket() as server:
    server.bind(('127.0.0.1', 0))
    print(server.getsockname()[1])
PY
)"
cleanup() {
  if [ -f "$PRICEPILOT_TEST_DIR/data/postmaster.pid" ]; then
    pg_ctl -D "$PRICEPILOT_TEST_DIR/data" -m immediate stop >/dev/null 2>&1 || true
  fi
  # Only the directory created by mktemp above is removed.
  rm -rf "$PRICEPILOT_TEST_DIR"
}
trap cleanup EXIT
mkdir "$PRICEPILOT_TEST_DIR/socket"
if ! initdb -D "$PRICEPILOT_TEST_DIR/data" --username=pricepilot_test -A trust --no-locale -E UTF8 > "$PRICEPILOT_TEST_DIR/init.log" 2>&1; then
  cat "$PRICEPILOT_TEST_DIR/init.log"
  exit 1
fi
if ! pg_ctl -D "$PRICEPILOT_TEST_DIR/data" -l "$PRICEPILOT_TEST_DIR/server.log" \
  -o "-h 127.0.0.1 -k $PRICEPILOT_TEST_DIR/socket -p $PRICEPILOT_TEST_PORT" -w start; then
  cat "$PRICEPILOT_TEST_DIR/server.log"
  exit 1
fi
createdb -h 127.0.0.1 -p "$PRICEPILOT_TEST_PORT" -U pricepilot_test pricepilot_test
cd "$PRICEPILOT_ROOT/backend"
PRICEPILOT_AGENT="${HOME}/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.13/byte-buddy-agent-1.14.13.jar"
PRICEPILOT_JVM_ARGS=()
if [ -f "$PRICEPILOT_AGENT" ]; then PRICEPILOT_JVM_ARGS=("-DargLine=-javaagent:$PRICEPILOT_AGENT"); fi
PP_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$PRICEPILOT_TEST_PORT/pricepilot_test" \
PP_TEST_DB_USERNAME=pricepilot_test PP_TEST_DB_PASSWORD='' \
./mvnw -o -B -ntp "${PRICEPILOT_JVM_ARGS[@]}" -Dtest=BuyingPlanPersistenceIntegrationTest test
