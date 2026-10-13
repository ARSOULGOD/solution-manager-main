# syntax=docker/dockerfile:1

# Both bases are pinned by index digest (resolved 2026-10-10); a tag alone moves under us. The licence text of the Go standard
# library in THIRD-PARTY-LICENCES.md was taken from this builder.
FROM --platform=$BUILDPLATFORM golang:1.26.4@sha256:f96cc555eb8db430159a3aa6797cd5bae561945b7b0fe7d0e284c63a3b291609 AS builder

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

ARG TARGETOS
ARG TARGETARCH

RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
    go build -trimpath -ldflags="-s -w" -o /out/sm-agent ./cmd/sm-agent

FROM gcr.io/distroless/static-debian13:nonroot@sha256:e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3

COPY --from=builder /out/sm-agent /usr/local/bin/sm-agent

# Third-party notices (Apache-2.0 s4(d) NOTICE texts; licence texts) travel with the binary. No --chmod: it would also mode the created
# directory 0644 (untraversable for the nonroot user); the files are 0644 in git and the directory is created 0755.
COPY NOTICE THIRD-PARTY-LICENCES.md /usr/share/doc/sm-agent/

USER nonroot

ENTRYPOINT ["/usr/local/bin/sm-agent"]
