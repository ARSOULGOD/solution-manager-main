# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM golang:1.26.4 AS builder

WORKDIR /src

# No third-party requires yet; go.sum may be absent — do not COPY a missing go.sum.
COPY go.mod ./
RUN go mod download

COPY . .

ARG TARGETOS
ARG TARGETARCH

RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
    go build -trimpath -ldflags="-s -w" -o /out/sm-agent ./cmd/sm-agent

FROM gcr.io/distroless/static-debian13:nonroot

COPY --from=builder /out/sm-agent /usr/local/bin/sm-agent

USER nonroot

ENTRYPOINT ["/usr/local/bin/sm-agent"]
