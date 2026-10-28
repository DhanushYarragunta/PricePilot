#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
#  Obtain the first Let's Encrypt certificate.
#
#  Run once, on the EC2 box, after DNS points at it:
#      ./deploy/init-letsencrypt.sh
#
#  ── The problem this solves ──
#  nginx will not start if its ssl_certificate file is missing, and certbot
#  cannot complete an HTTP-01 challenge without a web server answering on port
#  80. Each needs the other to go first.
#
#  The way out is a throwaway self-signed certificate: nginx starts with it,
#  certbot uses that running nginx to prove domain control, the real certificate
#  replaces the fake one, nginx reloads. Every nginx-plus-certbot setup does some
#  version of this, and skipping it is why a first HTTPS deployment usually
#  presents as a container restart loop with "cannot load certificate" buried in
#  the logs.
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo "No .env file. Copy .env.example to .env and fill it in first." >&2
    exit 1
fi

# `set -a` exports everything the file defines, which is what makes DOMAIN and
# CERTBOT_EMAIL visible to the rest of this script without parsing it by hand.
set -a
# shellcheck disable=SC1091
. ./.env
set +a

DOMAIN="${DOMAIN:-}"
CERTBOT_EMAIL="${CERTBOT_EMAIL:-}"
STAGING="${STAGING:-0}"

if [ -z "$DOMAIN" ] || [ "$DOMAIN" = "localhost" ]; then
    cat >&2 <<'MSG'
DOMAIN is not set to a real hostname in .env.

Let's Encrypt only issues certificates for names you can prove you control, so
this needs a domain whose A record points at this instance. An
ec2-*.compute.amazonaws.com hostname will be refused.

If you do not have a domain yet, that is fine: leave TLS_ENABLED=false and the
site runs over plain HTTP. Come back to this script later.
MSG
    exit 1
fi

if [ -z "$CERTBOT_EMAIL" ]; then
    echo "CERTBOT_EMAIL is not set in .env. Let's Encrypt needs it to warn you before expiry." >&2
    exit 1
fi

# ── A sanity check worth the three seconds ──
#
# The commonest failure is running this before DNS has propagated, and certbot's
# own error for that is a wall of JSON about an unauthorized challenge. Resolving
# the name here and comparing it with the instance's public IP turns that into a
# sentence. It is a warning rather than a hard stop, because split-horizon DNS and
# proxies both make this check wrong in ways that are nobody's fault.
RESOLVED="$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1; exit}' || true)"
PUBLIC_IP="$(curl -fsS --max-time 5 https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]' || true)"
if [ -n "$RESOLVED" ] && [ -n "$PUBLIC_IP" ] && [ "$RESOLVED" != "$PUBLIC_IP" ]; then
    echo "Warning: ${DOMAIN} resolves to ${RESOLVED} but this box is ${PUBLIC_IP}." >&2
    echo "If DNS was changed in the last hour this is probably just propagation." >&2
    read -r -p "Continue anyway? [y/N] " reply
    [ "$reply" = "y" ] || [ "$reply" = "Y" ] || exit 1
elif [ -z "$RESOLVED" ]; then
    echo "Warning: ${DOMAIN} does not resolve from this box yet." >&2
    read -r -p "Continue anyway? [y/N] " reply
    [ "$reply" = "y" ] || [ "$reply" = "Y" ] || exit 1
fi

LIVE_PATH="/etc/letsencrypt/live/${DOMAIN}"

echo "──> Checking for an existing certificate"
if docker compose run --rm --entrypoint sh certbot \
       -c "[ -f ${LIVE_PATH}/fullchain.pem ] && [ ! -f ${LIVE_PATH}/.self-signed ]" 2>/dev/null; then
    echo "A real certificate for ${DOMAIN} already exists."
    echo "Renewal is handled automatically by the certbot container; nothing to do."
    exit 0
fi

echo "──> Writing a temporary self-signed certificate so nginx can start"
# The marker file is the point of this block. Without it, a second run of this
# script would find a fullchain.pem, conclude the job was done, and leave the
# fake certificate in place — a site serving a browser warning that looks exactly
# like a successful setup from the logs.
docker compose run --rm --entrypoint sh certbot -c "\
    mkdir -p '${LIVE_PATH}' && \
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
        -keyout '${LIVE_PATH}/privkey.pem' \
        -out '${LIVE_PATH}/fullchain.pem' \
        -subj '/CN=${DOMAIN}' 2>/dev/null && \
    touch '${LIVE_PATH}/.self-signed'"

echo "──> Starting nginx with the placeholder"
# TLS_ENABLED is forced on for this one start regardless of what .env says,
# because the ACME challenge has to be answered by the config that will later
# serve the real certificate.
TLS_ENABLED=true docker compose up -d --build web
sleep 5

echo "──> Removing the placeholder and requesting the real certificate"
# Deleted first, deliberately. certbot would otherwise see an existing
# certificate for this name and ask an interactive question about renewing versus
# replacing it — which hangs a script nobody is watching.
docker compose run --rm --entrypoint sh certbot -c "rm -rf '${LIVE_PATH}' '/etc/letsencrypt/archive/${DOMAIN}' '/etc/letsencrypt/renewal/${DOMAIN}.conf'"

STAGING_FLAG=""
if [ "$STAGING" != "0" ]; then
    # Let's Encrypt allows five failed authorisations per hostname per hour, and
    # a typo in DOMAIN burns through that quickly. Staging issues an untrusted
    # certificate with far looser limits, which is the right way to rehearse:
    #   STAGING=1 ./deploy/init-letsencrypt.sh
    echo "    (using the staging server — the certificate will NOT be trusted)"
    STAGING_FLAG="--staging"
fi

docker compose run --rm --entrypoint certbot certbot \
    certonly --webroot -w /var/www/certbot \
    $STAGING_FLAG \
    --email "$CERTBOT_EMAIL" \
    -d "$DOMAIN" \
    --rsa-key-size 2048 \
    --agree-tos \
    --no-eff-email \
    --non-interactive

echo "──> Reloading nginx with the real certificate"
docker compose exec web nginx -s reload

cat <<MSG

Done. https://${DOMAIN} is live.

Two things left:
  1. Set TLS_ENABLED=true in .env, so a future 'docker compose up' keeps HTTPS.
  2. Set APP_PUBLIC_URL and CORS_ALLOWED_ORIGINS to https://${DOMAIN} and run
     'docker compose up -d app' — otherwise links in alert emails still point at
     the old address.

Renewal needs nothing from you: the certbot container tries twice a day, and the
web container reloads nginx every six hours to pick up a renewed certificate.
MSG
