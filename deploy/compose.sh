#!/usr/bin/env bash
# Load dotenv as literal data once, then disable Compose's second dotenv parse.
# Exported overrides and all command arguments retain their original values.
set -euo pipefail

cd "$(dirname "$0")/.."
if [ ! -f .env ]; then
    echo "No .env file. Prepare the server configuration before running Compose." >&2
    exit 1
fi

exec python3 scripts/with-env.py .env docker compose --env-file /dev/null "$@"
