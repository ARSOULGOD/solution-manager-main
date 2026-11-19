FROM alpine:3
RUN apk add --no-cache openssh-server openssh-sftp-server docker-cli curl bash coreutils
# The host's docker.sock gid and the invoking user's uid are passed in by the runbook, so the demo
# user can run `docker` and can write the bind-mounted config directory.
ARG DOCKER_GID
ARG DEMO_UID
RUN (addgroup -g $DOCKER_GID dockersock 2>/dev/null || true) \
 && adduser -D -u $DEMO_UID -s /bin/bash demo \
 && addgroup demo $(getent group $DOCKER_GID | cut -d: -f1) \
 && passwd -u demo \
 && ssh-keygen -A \
 && printf 'PasswordAuthentication no\nPubkeyAuthentication yes\nAllowUsers demo\nSubsystem sftp internal-sftp\n' > /etc/ssh/sshd_config.d/demo.conf
EXPOSE 22
CMD ["/usr/sbin/sshd","-D","-e"]
