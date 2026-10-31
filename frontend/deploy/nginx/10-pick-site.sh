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
    KEY="/etc/letsencrypt/live/${DOMAIN}/privkey.pem"

    # A requested HTTPS deployment must not silently serve authenticated traffic
    # over HTTP. Bootstrap uses TLS_ENABLED=false until init-letsencrypt.sh has
    # written BOTH placeholder files, then starts this TLS configuration.
    if [ ! -f "$CERT" ] || [ ! -f "$KEY" ]; then
        echo "10-pick-site.sh: TLS_ENABLED=true requires both fullchain.pem and privkey.pem; refusing to start." >&2
        echo "10-pick-site.sh: run ./deploy/init-letsencrypt.sh on the host to bootstrap the certificate." >&2
        echo "10-pick-site.sh: use TLS_ENABLED=false only for an explicit HTTP bootstrap." >&2
        exit 1
    fi

    echo "10-pick-site.sh: serving https for ${DOMAIN}"
    cp "$AVAILABLE/site-tls.conf.template" "$TEMPLATES/default.conf.template"
else
    echo "10-pick-site.sh: serving plain http (TLS_ENABLED=${TLS_ENABLED})"
    cp "$AVAILABLE/site-http.conf.template" "$TEMPLATES/default.conf.template"
fi
