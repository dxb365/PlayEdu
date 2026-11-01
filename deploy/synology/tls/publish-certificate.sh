#!/bin/sh
set -eu
umask 077
certs=${TEACHING_CERTS_DIR:-/certs}
domain=${DOMAIN:?DOMAIN is required}
sh /opt/teaching/validate-certificate.sh "$certs/.incoming/tls.crt" "$certs/.incoming/tls.key" "$domain" 86400
fingerprint=$(sha256sum "$certs/.incoming/tls.crt" "$certs/.incoming/tls.key" | sha256sum | cut -d ' ' -f 1)
mkdir -p "$certs/managed/$fingerprint"
cp "$certs/.incoming/tls.crt" "$certs/managed/$fingerprint/tls.crt"
cp "$certs/.incoming/tls.key" "$certs/managed/$fingerprint/tls.key"
chmod 644 "$certs/managed/$fingerprint/tls.crt"
chmod 600 "$certs/managed/$fingerprint/tls.key"
ln -s "managed/$fingerprint" "$certs/.current.$$"
mv -Tf "$certs/.current.$$" "$certs/current"
printf '%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ') Certificate ready for $domain" > "$certs/acme-status.txt"
