# syntax=docker/dockerfile:1
#
# Solution Manager launcher image (control plane + data plane + agent gateway, one process).
#
# Build only through runtime/scripts/build-image.sh: it assembles the build context (this file, bin/, and the
# repackaged jar) in a clean directory so nothing else on the machine can reach the image, and it builds with
# SOURCE_DATE_EPOCH so two builds of the same inputs produce the same image id.
#
# The base is pinned by DIGEST, not by tag: a tag moves, and "reproducible" is not a property of a moving base. To update
# it, pull the new tag, read its digest (docker image inspect --format '{{index .RepoDigests 0}}'), change it here and
# record the change in the commit. The digest is the multi-platform index, so amd64 and arm64 builds both resolve.
ARG BASE=eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
FROM ${BASE}

# useradd stamps today's date (days since the epoch) into /etc/shadow's last-change field and SOURCE_DATE_EPOCH does not reach RUN,
# so the field is blanked: otherwise the image digest would change from one day to the next (plan 015 T2.4 review, finding 1).
# --no-log-init keeps useradd from writing the sparse lastlog/faillog entries. A non-root user with a fixed uid, so a mounted secret directory can be made readable to it without being world-readable.
RUN groupadd --system --gid 10001 sm && useradd --system --no-log-init --uid 10001 --gid sm --no-create-home --shell /usr/sbin/nologin sm \
 && sed -i -E 's/^(sm:[^:]*:)[0-9]*:/\1:/' /etc/shadow \
 && mkdir -p /opt/sm/bin /etc/sm /var/lib/sm /usr/share/doc/sm && chmod 0755 /usr/share/doc/sm && chown -R root:root /opt/sm && chown sm:sm /var/lib/sm

COPY --chown=root:root --chmod=0555 bin/ /opt/sm/bin/
COPY --chown=root:root --chmod=0444 launcher.jar /opt/sm/launcher.jar
# The notices the licences require to travel with the image (OR-127 L3: BoringSSL; the OpenSSL clause 3 acknowledgment).
# THIRD-PARTY-NOTICES.md is copied by build-image.sh from backend/ so there is one source of truth.
COPY --chown=root:root --chmod=0444 NOTICE THIRD-PARTY-NOTICES.md /usr/share/doc/sm/

# Everything is configuration by environment (the launcher's application.properties maps each variable); nothing is baked in.
# Mount TLS material, the CA keystore and its password, and the database role passwords as read-only secret files (compose
# mounts them under /run/secrets; the SM_*_PASSWORD_FILE variables name them and bin/sm-secrets.sh reads them, OR-138); the
# passwords are never environment values. Operator tools run through bin/sm-tool. The gateway and readiness ports are the ones the runbook documents.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom" \
    SERVER_PORT=8080 \
    SM_AGENT_GATEWAY_PORT=9443 \
    SM_AGENT_GATEWAY_READINESS_PORT=9444 \
    SM_SHUTDOWN_TIMEOUT=60s

EXPOSE 8080 9443 9444
USER sm:sm
WORKDIR /var/lib/sm

# Ready means the process can serve: the agent-gateway listener is up (its L4 readiness socket accepts), or, with the
# gateway disabled, the console's HTTP port accepts. No unauthenticated HTTP route is added for this.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 CMD ["/opt/sm/bin/sm-healthcheck"]

# SIGTERM is what `docker stop` sends; the JVM runs its shutdown hooks, which drain the gateway (AGW-D18) before exit.
STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/sm/bin/sm-launcher"]
