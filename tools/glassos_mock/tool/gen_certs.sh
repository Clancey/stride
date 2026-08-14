#!/usr/bin/env bash
# Generate throwaway mTLS material for the mock GlassOS server.
#
# These certs are TEST FIXTURES ONLY. They are not the real GlassOS certificates
# and grant no access to any real console. They are written into ./certs, which
# is gitignored (the repo root .gitignore excludes *.pem, *.key and certs/), and
# must never be committed. See docs/PLAN.md section 2.2.
#
# Produces:
#   certs/ca.pem        throwaway CA certificate
#   certs/ca.key        CA private key
#   certs/server.pem    server certificate (CN/SAN = localhost)
#   certs/server.key    server private key
#   certs/client.pem    client certificate (for the probe / smoke test)
#   certs/client.key    client private key
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERTS="$DIR/certs"
mkdir -p "$CERTS"
cd "$CERTS"

DAYS=800

# --- CA ---
# BoringSSL (Dart/Flutter's TLS stack) strictly requires the CA to carry a
# keyUsage extension with keyCertSign, so we set it explicitly. A plain
# `openssl req -x509` omits it and the handshake then fails verification.
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem \
  -days "$DAYS" -subj "/CN=Stride Mock GlassOS CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

# openssl needs a SAN for the server so TLS hostname checks against localhost.
# BoringSSL (used by Dart/Flutter) also wants the leaf to carry the right
# extendedKeyUsage, so we set serverAuth here and clientAuth on the client.
cat > server.ext <<'EOF'
subjectAltName = DNS:localhost, IP:127.0.0.1
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
EOF

cat > client.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
EOF

# --- server cert ---
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/CN=localhost" 2>/dev/null
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -out server.pem -days "$DAYS" -extfile server.ext 2>/dev/null

# --- client cert (unencrypted PKCS#8 key, matching what tHUD extracts) ---
openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr \
  -subj "/CN=com.ifit.dev_app" 2>/dev/null
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -out client.pem -days "$DAYS" -extfile client.ext 2>/dev/null

rm -f server.csr client.csr server.ext client.ext ca.srl

echo "Wrote throwaway certs to $CERTS :"
ls -1 "$CERTS"
echo "Reminder: certs/ is gitignored. Do not commit any of these."
