#!/bin/bash
# QRいっき読み — unit + JVM UI verification runner (docs/VERIFICATION.md).
#
#   ./run-tests.sh          run everything; screenshots are VERIFIED
#                           against the goldens in app/src/test/screenshots/
#   ./run-tests.sh record   run everything; screenshots are RE-RECORDED
#                           (review the images, then keep them as goldens)
#
# Why not `gradle test`? The Gradle Test executor's worker daemon talks
# over loopback TCP, which this VM intercepts (see AGENTS.md "Android
# build in this VM"). So the tests are compiled by Gradle and then run
# by driving JUnitCore directly on the classpath AGP assembled
# (:app:writeTestRuntimeClasspath). On a normal machine
# `gradle :app:testDebugUnitTest` works too — pass the same
# roborazzi.test.record / roborazzi.test.verify system property.
#
# In this VM the compile step resolves dependencies --offline against
# the local mirror's disk cache as file repositories (QR_FILE_MIRROR=1,
# see settings.gradle.kts): the HTTP mirror serialises badly when
# several builds share it. New test dependencies must be pre-fetched
# into that cache with curl first (docs/VERIFICATION.md).
set -e
cd "$(dirname "$0")"
MODE="${1:-verify}"
case "$MODE" in
  verify) ROBORAZZI_PROP="-Droborazzi.test.verify=true" ;;
  record) ROBORAZZI_PROP="-Droborazzi.test.record=true" ;;
  *) echo "usage: $0 [verify|record]" >&2; exit 2 ;;
esac

export QR_FILE_MIRROR=1
export JAVA_HOME=~/workspace/tools/jdk21
# Project-local Gradle home: the shared one's journal lock collides
# with other projects' builds on this VM.
export GRADLE_USER_HOME=~/workspace/qr-matome/.local/gradle-home
export GRADLE_OPTS="-Xmx768m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"
# Build serialization rule (AGENTS.md, 2026-11-23): at most 2 builds
# at a time on this VM, via the global flock wrapper (2 slots).
# NOTE (v0.4.2): :app:bundleDebugClassesToRuntimeJar must be in this
# list explicitly. The unit-test runtime classpath points at that jar,
# but nothing else in this invocation rebuilds it — without the task,
# source changes silently test against a stale jar (this bit v0.4.2's
# first run: NoClassDefFoundError for a brand-new class).
~/workspace/tools/with-build-lock.sh \
  ~/workspace/tools/gradle-8.9/bin/gradle --offline \
  :app:assembleDebug :app:compileDebugUnitTestKotlin \
  :app:bundleDebugClassesToRuntimeJar :app:writeTestRuntimeClasspath \
  -Dorg.gradle.daemon=false -Dkotlin.daemon.enabled=false --max-workers=1

# Robolectric needs its android-all runtime jar. In this VM Java cannot
# download it (egress proxy), so seed Robolectric's offline dependency
# directory from the mirror's disk cache when it is there. On a normal
# machine the jar is absent here and Robolectric fetches it from Maven
# Central by itself.
EXTRA_PROPS=()
ROBO_DIR="$PWD/app/build/robolectric-deps"
ANDROID_ALL_JAR=~/workspace/tools/mirror-cache/central/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7/android-all-instrumented-15-robolectric-12650502-i7.jar
if [ -f "$ANDROID_ALL_JAR" ]; then
  mkdir -p "$ROBO_DIR"
  cp -n "$ANDROID_ALL_JAR" "$ROBO_DIR/" 2>/dev/null || true
  EXTRA_PROPS+=(-Drobolectric.offline=true "-Drobolectric.dependency.dir=$ROBO_DIR")
fi

TEST_CLASSES=$(cd app/build/tmp/kotlin-classes/debugUnitTest &&
  find . -name "*Test.class" ! -name "*\$*" |
  sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)

echo "Running: $TEST_CLASSES"
# Working directory = app/: the screenshot tests address their goldens
# as src/test/screenshots/<name>.png relative to it.
cd app
"$JAVA_HOME/bin/java" -Xmx1536m -Dfile.encoding=UTF-8 \
  -Djava.net.preferIPv4Stack=true -Duser.timezone=Asia/Tokyo \
  "$ROBORAZZI_PROP" "${EXTRA_PROPS[@]}" \
  -cp "$(cat build/test-runtime-classpath.txt)" \
  org.junit.runner.JUnitCore $TEST_CLASSES
