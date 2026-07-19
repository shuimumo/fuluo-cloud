#!/usr/bin/env bash
set -euo pipefail

wait_for_port() {
  local host="$1"
  local port="$2"
  until (echo >"/dev/tcp/${host}/${port}") >/dev/null 2>&1; do
    echo "waiting for ${host}:${port}"
    sleep 2
  done
}

wait_for_port rabbitmq 5672
wait_for_port mysql8 3306

# app.sh applies the container environment to Canal's properties and starts Java.
/home/admin/app.sh &
app_pid=$!
trap 'kill "$app_pid" 2>/dev/null || true' EXIT TERM INT

started=0
deadline=$((SECONDS + 90))
while kill -0 "$app_pid" 2>/dev/null; do
  if ps -ef | grep '[C]analLauncher' >/dev/null; then
    started=1
  elif [ "$started" -eq 1 ]; then
    echo "Canal Java process exited; restarting container"
    exit 1
  elif [ "$SECONDS" -ge "$deadline" ]; then
    echo "Canal Java process did not start within 90 seconds"
    exit 1
  fi
  sleep 2
done

wait "$app_pid"
