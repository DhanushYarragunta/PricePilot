#!/bin/sh
# ════════════════════════════════════════════════════════════════════════════
#  Pick the site configuration before nginx starts.
#
#  The official nginx image runs every executable in /docker-entrypoint.d in
#  lexical order, and one of those scripts — 20-envsubst-on-templates.sh —
#  expands /etc/nginx/templates/*.template into /etc/nginx/conf.d/. This script
#  is numbered 10 so it runs first, and all it does is put exactly one of the two
#  candidate templates where that script will find it.
#
#  Why not ship both templates in /etc/nginx/templates/? Because both declare a
#  server on port 80 and nginx would refuse to start with a duplicate
#  default_server. Why not a single template with the TLS parts commented out?
#  Because nginx has no conditionals, so "switch TLS on" would mean editing a
#  config file by hand on a live box, which is how a working deployment acquires
#  a typo at the worst possible moment.
# ════════════════════════════════════════════════════════════════════════════
set -eu

AVAILABLE=/etc/nginx/available
TEMPLATES=/etc/nginx/templates

mkdir -p "$TEMPLATES"
# Anything left from a previous start of this container, so switching TLS off
# again actually takes effect rather than leaving both files in place.
rm -f "$TEMPLATES"/*.template

TLS_ENABLED="${TLS_ENABLED:-false}"
DOMAIN="${DOMAIN:-localhost}"

if [ "$TLS_ENABLED" = "true" ]; then
    CERT="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"

    # The guard that matters.
    #
    # With TLS_ENABLED=true and no certificate on disk, nginx exits immediately
    # with "cannot load certificate" and the container restart-loops. The symptom
    # — a site that is simply unreachable — gives no hint that the cause is a
    # missing file, and this is the single most likely way a first deployment
    # fails. So rather than start and fail, fall back to plain HTTP and say so
    # loudly in the logs: a site serving over HTTP is recoverable from, and the
    # message names the script that fixes it.
    if [ ! -f "$CERT" ]; then
        echo "10-pick-site.sh: TLS_ENABLED=true but no certificate at ${CERT}." >&2
        echo "10-pick-site.sh: starting on plain HTTP instead so the site is reachable." >&2
        echo "10-pick-site.sh: run ./deploy/init-letsencrypt.sh on the host, then restart web." >&2
        cp "$AVAILABLE/site-http.conf.template" "$TEMPLATES/default.conf.template"
        exit 0
    fi

    echo "10-pick-site.sh: serving https for ${DOMAIN}"
    cp "$AVAILABLE/site-tls.conf.template" "$TEMPLATES/default.conf.template"
else
    echo "10-pick-site.sh: serving plain http (TLS_ENABLED=${TLS_ENABLED})"
    cp "$AVAILABLE/site-http.conf.template" "$TEMPLATES/default.conf.template"
fi
