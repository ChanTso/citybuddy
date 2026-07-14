#!/bin/sh
set -eu

if [ "${1:-}" = "serve" ]; then
  exec sleep infinity
fi

exec java -jar /opt/citybuddy/rocketmq-probe.jar "$@"
