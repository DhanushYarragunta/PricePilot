# ════════════════════════════════════════════════════════════════════════════
#  PricePilot backend image
#
#  Two stages, for two different reasons. The build stage needs Maven, a JDK and
#  roughly 400 MB of downloaded dependencies; the runtime needs a JRE and one jar.
#  Shipping the build stage would mean putting a compiler and the whole local
#  repository on a t3.small, and it would mean the running container has the
#  tools to rebuild itself, which is not a property worth having on a public box.
# ════════════════════════════════════════════════════════════════════════════

# ─────────────────────────────── build ───────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /build

# The pom alone first, and `go-offline` before any source is copied.
#
# This is the one layer-ordering trick that matters here. Docker caches per
# instruction, so copying the whole project in one COPY would re-download every
# dependency on every source change — several minutes each time, and on a small
# EC2 box with a slow link it is the difference between a two-minute deploy and a
# twelve-minute one. With the pom in its own layer, dependencies are only fetched
# again when the pom itself changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# -DskipTests, deliberately and with a caveat.
#
# Tests belong in CI or on a developer machine, not in a deployment build: a box
# with 2 GB of RAM running a Postgres container alongside should not also be
# starting Spring contexts. The caveat is that this means the image can be built
# from code that does not pass its tests, so `mvn verify` locally is a step in
# the runbook rather than an assumption made here.
RUN mvn -B -q -DskipTests package \
    && mv target/pricepilot-backend-*.jar /build/app.jar

# ────────────────────────────── runtime ──────────────────────────────
FROM eclipse-temurin:17-jre-jammy

# Jammy rather than Alpine. Alpine would save about 70 MB, and would run this on
# musl instead of glibc — which is fine until a native dependency or a DNS
# resolution difference behaves unlike every other machine this ran on. The
# saving is not worth debugging that on a live box.

# curl is here for one reason: the HEALTHCHECK below. Installing it explicitly
# and then cleaning the apt lists keeps the layer small and makes the dependency
# visible, rather than relying on it happening to be in the base image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

# A non-root user, created with a fixed uid/gid.
#
# Fixed rather than auto-assigned because the uid is what appears on files in any
# mounted volume, and a number that changes between rebuilds produces permission
# errors that look like application bugs.
RUN groupadd --gid 10001 pricepilot \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin pricepilot

WORKDIR /app
COPY --from=build --chown=10001:10001 /build/app.jar /app/app.jar

# Where a mounted copy of the extraction rules is expected to live. Created here
# so the directory exists and is writable by the app user even when nothing is
# mounted over it, which keeps the container starting either way.
RUN mkdir -p /app/config && chown 10001:10001 /app/config

USER 10001:10001

EXPOSE 8080

# UTC inside the container, always.
#
# The nightly telemetry prune is a cron expression evaluated in the JVM's local
# time, and the scheduler's intervals are compared against timestamps stored as
# UTC. Leaving the container on whatever the host happens to be set to is how a
# cleanup job ends up running at a different hour than the comment next to it
# claims. It also makes every log line comparable with every database row.
ENV TZ=UTC

# ── JVM flags, each one earning its place on a 2 GB box ──
#
# MaxRAMPercentage=70: the JVM's default is a quarter of available RAM, which on
#   the ~900 MB this container is limited to in docker-compose.yml would leave a
#   225 MB heap and a Spring app that spends its life in GC. 70% leaves headroom
#   for metaspace, thread stacks and the JIT's own allocations, which live
#   outside the heap and are what actually get a container OOM-killed.
# SerialGC: with one or two vCPUs, G1's concurrent threads are competing with
#   the application for the same core. For a workload that is almost entirely
#   waiting on network and Postgres, the simplest collector is the fastest.
# ExitOnOutOfMemoryError: a JVM that has run out of heap is not going to recover,
#   and a container that stays up while failing every request is worse than one
#   that restarts — Compose's restart policy can fix the second and not the first.
# HeapDumpOnOutOfMemoryError writes to /tmp, which is ephemeral: enough to
#   inspect before the restart, and it cannot fill the EBS volume.
# preferIPv4Stack: Docker's embedded DNS answers on IPv4; without this, resolving
#   `db` can try an AAAA record first and add a timeout to every cold connection.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 \
-XX:+UseSerialGC \
-XX:+ExitOnOutOfMemoryError \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp \
-Djava.security.egd=file:/dev/./urandom \
-Djava.net.preferIPv4Stack=true"

# The readiness probe, not the liveness one.
#
# Spring's readiness group reports down while Flyway is still migrating and while
# the context is still refreshing, which is exactly the window in which nginx
# must not forward traffic here. Liveness would answer OK during a migration and
# produce 502s for the first thirty seconds of every deploy.
#
# start-period is generous because the first start on a fresh database runs every
# migration; failures during that window do not count against the retry budget.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=4 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# `exec` so the JVM becomes PID 1 and receives SIGTERM directly.
#
# This is what makes `server.shutdown: graceful` in application.yml mean
# anything. Without exec, the shell is PID 1, the signal never reaches Java, and
# Docker kills the container after ten seconds — in the middle of whatever
# retailer fetch was in flight, with its database transaction unresolved.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
