#!/bin/sh
set -eu
cert=$1
key=$2
domain=$3
minimum=${4:-0}
trust=${TLS_TRUST_BUNDLE:-/etc/ssl/cert.pem}
[ -s "$cert" ] && [ -s "$key" ]
openssl x509 -in "$cert" -noout -checkhost "$domain" >/dev/null 2>&1
openssl x509 -in "$cert" -noout -checkend "$minimum" >/dev/null 2>&1
openssl verify -purpose sslserver -CAfile "$trust" -untrusted "$cert" "$cert" >/dev/null 2>&1
cert_public=$(openssl x509 -in "$cert" -pubkey -noout | openssl pkey -pubin -outform DER 2>/dev/null | sha256sum | cut -d ' ' -f 1)
key_public=$(openssl pkey -in "$key" -pubout -outform DER 2>/dev/null | sha256sum | cut -d ' ' -f 1)
[ -n "$cert_public" ] && [ "$cert_public" = "$key_public" ]
