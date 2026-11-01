#!/bin/sh
set -u
umask 077
state=${TEACHING_ACME_HOME:-/acme.sh}
certs=${TEACHING_CERTS_DIR:-/certs}
config=${TEACHING_ACME_CONFIG:-/run/acme-config/acme.env}
client=${TEACHING_ACME_CLIENT:-/acmebin/acme.sh}
server=${TEACHING_ACME_SERVER:-https://acme-v02.api.letsencrypt.org/directory}
mkdir -p "$state" "$certs/.incoming"
chmod 700 "$state" "$certs/.incoming"
trap 'exit 0' TERM INT
cr=$(printf '\r')

load_config() {
  unset ACME_EMAIL Ali_Key Ali_Secret
  ACME_DNS_WAIT=180
  [ -f "$config" ] || return 1
  while IFS='=' read -r name value || [ -n "$name" ]; do
    value=${value%"$cr"}
    case "$name" in
      ACME_EMAIL|Ali_Key|Ali_Secret|ACME_DNS_WAIT) export "$name=$value" ;;
      ''|\#*) ;;
      *) return 1 ;;
    esac
  done < "$config"
  [ -n "${ACME_EMAIL:-}" ] && [ -n "${Ali_Key:-}" ] && [ -n "${Ali_Secret:-}" ] || return 1
  case "$ACME_EMAIL" in *@*.*) ;; *) return 1 ;; esac
  case "$ACME_DNS_WAIT" in ''|*[!0-9]*) return 1 ;; esac
  [ "$ACME_DNS_WAIT" -le 1800 ] || return 1
}

cycle() {
  [ -n "${DOMAIN:-}" ] || return 1
  case "$DOMAIN" in *[!a-zA-Z0-9.-]*|.*|*..*) return 1 ;; esac
  if [ ! -e "$state/teaching-$DOMAIN.ready" ]; then
    "$client" --home /acmebin --config-home "$state" --server "$server" --issue --dns dns_ali --dnssleep "$ACME_DNS_WAIT" -d "$DOMAIN" --keylength 2048 --accountemail "$ACME_EMAIL"
    result=$?
  else
    "$client" --home /acmebin --config-home "$state" --server "$server" --renew -d "$DOMAIN" --accountemail "$ACME_EMAIL"
    result=$?
  fi
  [ "$result" -eq 0 ] || [ "$result" -eq 2 ] || return "$result"
  "$client" --home /acmebin --config-home "$state" --server "$server" --install-cert -d "$DOMAIN" --key-file "$certs/.incoming/tls.key" --fullchain-file "$certs/.incoming/tls.crt" --reloadcmd 'sh /opt/teaching/publish-certificate.sh' || return 1
  touch "$state/teaching-$DOMAIN.ready"
}

while :; do
  if ! load_config; then
    printf '%s\n' 'Certificate setup pending: fill acme-config/acme.env on your NAS.'
    printf '%s\n' 'Waiting for local certificate setup.' > "$certs/acme-status.txt"
    delay=60
  elif (cycle) > "$state/last-run.log" 2>&1; then
    printf '%s\n' 'Certificate check completed. The gateway loads validated changes automatically.'
    delay=21600
  else
    printf '%s\n' 'Certificate check failed; existing certificate retained. Details are in private acme-state/last-run.log.'
    printf '%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ') Certificate check failed; retry scheduled." > "$certs/acme-status.txt"
    delay=3600
  fi
  [ "${TEACHING_ACME_ONCE:-0}" = 1 ] && exit 0
  sleep "$delay" &
  wait $! || :
done
