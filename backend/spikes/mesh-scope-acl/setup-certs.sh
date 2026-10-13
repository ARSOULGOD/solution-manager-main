#!/bin/bash
#
# §11.3 spike — self-signed TLS material for the nginx reverse proxy.
# Copied from backend/spikes/mesh-authz/setup-certs.sh verbatim except the CN/O strings and
# the fact that this spike's nginx listens on 9444, not 9443 (nginx.conf, not this script,
# carries that difference).
#
# MUST run BEFORE `docker compose up` — the nginx service bind-mounts ./certs.

set -euo pipefail

cd "$(dirname "$0")"

CERT_DIR="./certs"
CA_VALIDITY_DAYS=3650    # 10 years
SRV_VALIDITY_DAYS=730    # 2 years

echo "[1/3] Creating certificates directory..."
mkdir -p "$CERT_DIR"

echo "[2/3] Generating Certificate Authority (CA)..."
openssl genrsa -out "$CERT_DIR/ca.key" 4096 2>/dev/null
openssl req -new -x509 -days $CA_VALIDITY_DAYS \
  -key "$CERT_DIR/ca.key" \
  -out "$CERT_DIR/ca.crt" \
  -subj "/CN=SM Spike ACL CA/O=Solution Manager mesh-scope-acl Spike" 2>/dev/null

echo "[3/3] Generating nginx server certificate (CN=localhost)..."
openssl genrsa -out "$CERT_DIR/server.key" 2048 2>/dev/null
openssl req -new -key "$CERT_DIR/server.key" \
  -out "$CERT_DIR/server.csr" \
  -subj "/CN=localhost/O=Solution Manager mesh-scope-acl Spike" 2>/dev/null
openssl x509 -req -days $SRV_VALIDITY_DAYS \
  -in "$CERT_DIR/server.csr" \
  -CA "$CERT_DIR/ca.crt" \
  -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial \
  -extfile <(printf "subjectAltName=DNS:localhost,DNS:host.docker.internal,IP:127.0.0.1") \
  -out "$CERT_DIR/server.crt" 2>/dev/null
rm -f "$CERT_DIR/server.csr"

echo ""
echo "Certificate generation complete. Files in $CERT_DIR:"
echo "  - ca.key / ca.crt          spike CA (10 years)"
echo "  - server.key / server.crt  nginx TLS server cert for wss://localhost:9444 (2 years)"
