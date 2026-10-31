# ════════════════════════════════════════════════════════════════════════════
#  PricePilot frontend image
#
#  Vite produces static files. Nothing needs to run them, so the runtime stage is
#  nginx with a directory of assets — and since nginx is already there, it is also
#  the thing that terminates TLS and proxies /api to the backend. One container
#  fewer to reason about on a box with 2 GB of RAM.
# ════════════════════════════════════════════════════════════════════════════

# ─────────────────────────────── build ───────────────────────────────
FROM node:20-alpine AS build

WORKDIR /build

# `npm ci`, not `npm install`, and the lockfile copied on its own first.
#
# ci installs exactly what package-lock.json pins and fails if the lockfile and
# the manifest disagree, which is the behaviour wanted in a build: a deploy that
# silently picks up a new minor version of a dependency is a deploy whose output
# nobody can reproduce. Separate COPY so changing a component does not reinstall
# node_modules.
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY . .

RUN npm run build

# ────────────────────────────── runtime ──────────────────────────────
FROM nginx:1.27-alpine

# Restrict the official entrypoint's substitution to the one template setting.
# nginx variables such as $request_uri and $app_upstream must remain untouched.
ENV NGINX_ENVSUBST_FILTER="^DOMAIN$"

# Alpine is right here in a way it was not for the JVM: nginx is a C binary with
# no runtime to surprise anybody, and the image is 50 MB instead of 190 MB.

# The official nginx image runs envsubst over /etc/nginx/templates/*.template at
# container start and writes the result into /etc/nginx/conf.d/. That is how
# ${DOMAIN} becomes a real server_name without baking a hostname into the image —
# which matters because the same image has to work on localhost, on an EC2 public
# DNS name, and on a real domain.
#
# Both candidate site configs are staged in /etc/nginx/available, and
# 10-pick-site.sh copies exactly one of them into /etc/nginx/templates before the
# image's own envsubst step runs. Leaving both in templates/ would give nginx two
# servers on port 80 and it would refuse to start.
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/site-tls.conf.template  /etc/nginx/available/site-tls.conf.template
COPY deploy/nginx/site-http.conf.template /etc/nginx/available/site-http.conf.template
COPY deploy/nginx/10-pick-site.sh /docker-entrypoint.d/10-pick-site.sh

# Set in the image rather than left to whoever mounts it. A script in
# /docker-entrypoint.d that is not executable is skipped silently by the entrypoint
# — no warning, no error — and the container then starts with no site config at
# all. Worse, a git checkout on Windows does not reliably preserve the bit, so
# relying on the file's permissions in the repository would make this work on one
# machine and not another.
RUN chmod +x /docker-entrypoint.d/10-pick-site.sh

# Remove nginx's stock index so a missing application index cannot pass health.
RUN rm -rf /usr/share/nginx/html/*
COPY --from=build /build/dist /usr/share/nginx/html

# A writable directory for the ACME challenge files certbot drops here. The
# compose file mounts a shared volume over it; creating it means the container
# still starts if that mount is absent.
RUN mkdir -p /var/www/certbot

EXPOSE 80 443

# /healthz checks index.html locally without redirecting to HTTPS. The probe
# therefore works before certificate bootstrap and with TLS enabled, and fails
# when the application build is missing. Backend readiness is checked separately.
HEALTHCHECK --interval=30s --timeout=4s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/healthz || exit 1
