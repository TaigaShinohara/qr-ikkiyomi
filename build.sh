#!/bin/bash
# QRいっき読み build helper for this VM (see AGENTS.md "Android build in this VM").
# Same recipe as Scriptlet's build.sh: local mirror + IPv4 + in-process Gradle 8.9.
set -e
cd "$(dirname "$0")"
export QR_LOCAL_MIRROR=1
export JAVA_HOME=~/workspace/tools/jdk21
export GRADLE_USER_HOME=~/workspace/tools/gradle-home
export GRADLE_OPTS="-Xmx768m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"
if ! curl -s -o /dev/null --max-time 3 http://127.0.0.1:18080/central/; then
  (cd ~/workspace/tools && nohup python3 mirror.py > /tmp/mirror.log 2>&1 &)
  sleep 1
fi
# Build serialization rule (AGENTS.md, 2026-11-23): at most 2 builds
# at a time on this VM — go through the global flock wrapper.
exec ~/workspace/tools/with-build-lock.sh \
  ~/workspace/tools/gradle-8.9/bin/gradle "$@" \
  -Dorg.gradle.daemon=false -Dkotlin.daemon.enabled=false --max-workers=1
