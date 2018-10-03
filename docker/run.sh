#!/bin/bash
set -e
exec java ${JAVA_OPTS} -Dlogback.configurationFile=logback.xml -jar "$@"
