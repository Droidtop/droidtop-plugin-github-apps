#!/usr/bin/env bash
# Signs and packages an already-built plugin bundle (build.sh output:
# manifest.json + classes.jar -- either built locally or downloaded from
# CI's unsigned artifact) into droidtop.github-apps.droidplugin.tar.xz.
#
# The only script that touches the plugin origin private key. CI runs it with
# the PLUGIN_SIGNING_KEY repo secret (written to a 600 temp file for the job and
# deleted afterwards); locally, run it on droidtop-dev with the key under
# /root/coordination/keys/droidtop-plugins/. The key is never committed.
# PLUGIN_SIGNING_KEY is the PATH of the PEM. If PLUGIN_SIGNING_CERT names a
# file (this plugin key's certificate from droidtop's plugin master key), it is
# packaged as origin.cert next to manifest.sig.

set -euo pipefail
JWS_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$(dirname "$0")"

: "${PLUGIN_SIGNING_KEY:?set to the droidtop plugin origin EC private key PEM}"
: "${BUNDLE_DIR:=build}"

test -f "$BUNDLE_DIR/manifest.json" || { echo "missing $BUNDLE_DIR/manifest.json -- run build.sh first" >&2; exit 1; }
test -f "$BUNDLE_DIR/classes.jar" || { echo "missing $BUNDLE_DIR/classes.jar -- run build.sh first" >&2; exit 1; }

openssl dgst -sha256 -sign "$PLUGIN_SIGNING_KEY" "$BUNDLE_DIR/manifest.json" | base64 -w0 > "$BUNDLE_DIR/manifest.sig"

# Certificate-format signature (droidtop docs/SPEC.md 12a): a detached JWS over the exact manifest.json bytes, the
# leaf certificate issued by Droidtop Core in x5c. manifest.sig stays for older droidtop builds.
JWS_FILE=
if [ -n "${PLUGIN_SIGNING_CERT:-}" ]; then
  python3 -c 'import cryptography' 2>/dev/null || python3 -m pip install --quiet --user --break-system-packages 'cryptography>=42'
  python3 "$JWS_DIR/sign_jws.py" droidtop-plugin+jws "$BUNDLE_DIR/manifest.json" "$PLUGIN_SIGNING_KEY" "$PLUGIN_SIGNING_CERT" > "$BUNDLE_DIR/manifest.jws"
  JWS_FILE=manifest.jws
fi

# Optional certificate of this plugin key (signed by droidtop's plugin master).
rm -f "$BUNDLE_DIR/origin.cert"
CERT_FILE=
if [ -n "${PLUGIN_SIGNING_CERT:-}" ]; then
  test -f "$PLUGIN_SIGNING_CERT" || { echo "PLUGIN_SIGNING_CERT is not a file" >&2; exit 1; }
  cp "$PLUGIN_SIGNING_CERT" "$BUNDLE_DIR/origin.cert"
  CERT_FILE=origin.cert
fi

tar -C "$BUNDLE_DIR" --sort=name -cf - manifest.json manifest.sig $JWS_FILE $CERT_FILE classes.jar | xz -9e > droidtop.github-apps.droidplugin.tar.xz

echo "Signed droidtop.github-apps.droidplugin.tar.xz"
sha256sum droidtop.github-apps.droidplugin.tar.xz
