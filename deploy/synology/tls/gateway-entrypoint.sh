#!/bin/sh
set -u
umask 077
certs=${TEACHING_CERTS_DIR:-/etc/teaching/certs}
runtime=/run/teaching-tls
mkdir -p "$runtime/generations"
last=''
child=''
sleep_pid=''
stop() {
  [ -z "$sleep_pid" ] || kill "$sleep_pid" 2>/dev/null || :
  [ -z "$child" ] || kill -QUIT "$child" 2>/dev/null || :
  [ -z "$child" ] || wait "$child" 2>/dev/null || :
  exit 0
}
trap stop TERM INT

prepare_candidate() {
  source="$certs"
  if [ -L "$certs/current" ]; then source=$(readlink -f "$certs/current") || return 1; fi
  [ -s "$source/tls.crt" ] && [ -s "$source/tls.key" ] || return 1
  stage="$runtime/stage"
  mkdir -p "$stage"
  cp "$source/tls.crt" "$stage/tls.crt" || return 1
  cp "$source/tls.key" "$stage/tls.key" || return 1
  sh /opt/teaching/validate-certificate.sh "$stage/tls.crt" "$stage/tls.key" "$DOMAIN" 0 || return 1
  candidate=$(sha256sum "$stage/tls.crt" "$stage/tls.key" | sha256sum | cut -d ' ' -f 1)
  [ "$candidate" != "$last" ] || return 2
  mkdir -p "$runtime/generations/$candidate"
  cp "$stage/tls.crt" "$runtime/generations/$candidate/tls.crt"
  cp "$stage/tls.key" "$runtime/generations/$candidate/tls.key"
  chmod 644 "$runtime/generations/$candidate/tls.crt"
  chmod 600 "$runtime/generations/$candidate/tls.key"
}

activate() {
  ln -s "generations/$candidate" "$runtime/.current.$$" || return 1
  mv -Tf "$runtime/.current.$$" "$runtime/current"
}

while :; do
  if prepare_candidate && activate; then last="$candidate"; break; fi
  printf '%s\n' 'Waiting for a valid domain certificate; see acme-config/acme.env and certificate-manager logs.'
  sleep "${TEACHING_TLS_POLL_SECONDS:-20}" & sleep_pid=$!; wait "$sleep_pid" || :
done

/docker-entrypoint.sh nginx -g 'daemon off;' &
child=$!
while kill -0 "$child" 2>/dev/null; do
  sleep "${TEACHING_TLS_POLL_SECONDS:-20}" & sleep_pid=$!; wait "$sleep_pid" || :
  if prepare_candidate; then
    previous="$last"
    if activate && nginx -t -q && nginx -s reload; then
      last="$candidate"
      printf '%s\n' 'Validated certificate loaded without stopping active video connections.'
    else
      candidate="$previous"
      activate || :
      printf '%s\n' 'Certificate reload failed; previous certificate retained.'
    fi
  fi
done
wait "$child"
exit $?
