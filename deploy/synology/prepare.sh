#!/bin/sh
# Teaching customization: generate secrets locally; never print or upload them to GitHub.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -e .env ]; then printf '%s\n' '.env already exists; left unchanged.'; exit 0; fi
umask 077
printf 'DOMAIN=www.5927.fun\nWEB_HTTPS_PORT=8443\nSTORAGE_HTTPS_PORT=9443\nADMIN_BIND_IP=127.0.0.1\nADMIN_PORT=9900\n' > .env
printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> .env
printf 'JWT_SECRET=%s\n' "$(openssl rand -hex 48)" >> .env
printf 'STORAGE_ACCESS_KEY=%s\n' "$(openssl rand -hex 16)" >> .env
printf 'STORAGE_SECRET_KEY=%s\n' "$(openssl rand -hex 32)" >> .env
mkdir -p certs mysql storage
printf '%s\n' 'Generated .env. Set ADMIN_BIND_IP to the NAS LAN IP, then add the domain TLS certificate to certs/ before starting.'
