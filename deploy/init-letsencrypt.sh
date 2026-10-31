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

# Dotenv is data, never shell code. Re-enter once with the existing safe loader;
# explicitly exported variables retain precedence and every argument is preserved.
if [ "${1:-}" != "--pricepilot-dotenv-loaded" ]; then
    exec python3 scripts/with-env.py .env bash deploy/init-letsencrypt.sh \
        --pricepilot-dotenv-loaded "$@"
fi
shift

# Check only certificate-bootstrap settings here. This neither contacts a
# provider nor prints configuration values; it rejects unsafe domain text before
# that text is used in the container-side shell commands below.
python3 deploy/check-config.py --tls-bootstrap

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
# Only this script's marked placeholder may be replaced automatically. Preserve
# unmarked partial certificate material for operator repair, including broken
# symlinks and archive/renewal state left by an interrupted certificate request.
CERT_PROBE=$(cat <<'CERTIFICATE_PROBE'
exists() { [ -e "$1" ] || [ -L "$1" ]; }
if [ -f "$1/.self-signed" ]; then
    if exists "$2" || exists "$3"; then printf incomplete; else printf placeholder; fi
elif [ -f "$1/fullchain.pem" ] && [ -f "$1/privkey.pem" ]; then
    printf complete
elif exists "$1" || exists "$2" || exists "$3"; then
    printf incomplete
else
    printf missing
fi
CERTIFICATE_PROBE
)
if ! CERT_STATE="$(./deploy/compose.sh run --rm --entrypoint sh certbot -c "$CERT_PROBE" sh \
        "$LIVE_PATH" "/etc/letsencrypt/archive/${DOMAIN}" "/etc/letsencrypt/renewal/${DOMAIN}.conf")"; then
    echo "Could not inspect existing certificate state. No certificate files were changed." >&2
    exit 1
fi
case "$CERT_STATE" in
    complete)
        echo "An existing certificate and private key are present for ${DOMAIN}."
        echo "Renewal is handled by the certbot container; no bootstrap replacement is needed."
        exit 0
        ;;
    incomplete)
        echo "Existing certificate material is incomplete or unmarked. Stop and repair it before retrying; nothing was overwritten or deleted." >&2
        exit 1
        ;;
    missing|placeholder) ;;
    *)
        echo "Certificate state could not be verified. No certificate files were changed." >&2
        exit 1
        ;;
esac

echo "──> Writing a temporary self-signed certificate so nginx can start"
# The marker file is the point of this block. Without it, a second run of this
# script would find a fullchain.pem, conclude the job was done, and leave the
# fake certificate in place — a site serving a browser warning that looks exactly
# like a successful setup from the logs.
./deploy/compose.sh run --rm --entrypoint sh certbot -c "\
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
TLS_ENABLED=true ./deploy/compose.sh up -d --build web
sleep 5

echo "──> Removing the placeholder and requesting the real certificate"
# Deleted first, deliberately. certbot would otherwise see an existing
# certificate for this name and ask an interactive question about renewing versus
# replacing it — which hangs a script nobody is watching.
./deploy/compose.sh run --rm --entrypoint sh certbot -c "rm -rf '${LIVE_PATH}' '/etc/letsencrypt/archive/${DOMAIN}' '/etc/letsencrypt/renewal/${DOMAIN}.conf'"

STAGING_FLAG=""
if [ "$STAGING" != "0" ]; then
    # Let's Encrypt allows five failed authorisations per hostname per hour, and
    # a typo in DOMAIN burns through that quickly. Staging issues an untrusted
    # certificate with far looser limits, which is the right way to rehearse:
    #   STAGING=1 ./deploy/init-letsencrypt.sh
    echo "    (using the staging server — the certificate will NOT be trusted)"
    STAGING_FLAG="--staging"
fi

./deploy/compose.sh run --rm --entrypoint certbot certbot \
    certonly --webroot -w /var/www/certbot \
    $STAGING_FLAG \
    --email "$CERTBOT_EMAIL" \
    -d "$DOMAIN" \
    --rsa-key-size 2048 \
    --agree-tos \
    --no-eff-email \
    --non-interactive

echo "──> Reloading nginx with the real certificate"
./deploy/compose.sh exec web nginx -s reload

cat <<MSG

Done. https://${DOMAIN} is live.

Two things left:
  1. Set TLS_ENABLED=true, APP_PUBLIC_URL=https://${DOMAIN} and
     CORS_ALLOWED_ORIGINS=https://${DOMAIN} in the server .env.
  2. Run 'python3 deploy/check-config.py', then recreate app and web using the
     README deployment instructions. A simple restart does not reload changed environment
     values. TLS mode now refuses to start if either certificate file is missing.

Renewal needs nothing from you: the certbot container tries twice a day, and the
web container reloads nginx every six hours to pick up a renewed certificate.
MSG
