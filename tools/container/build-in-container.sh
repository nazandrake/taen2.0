#!/usr/bin/env bash
# Runs inside the build image: builds the committed HEAD of /src (mounted read-only) in a fresh clone and copies the unsigned
# APK to /out. Usage (from the repo): make container-build
set -euo pipefail
rm -rf /work && git clone --quiet --no-hardlinks /src /work
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > /work/local.properties
cd /work
./gradlew --console=plain --no-build-cache :app:assembleRelease
cp app/build/outputs/apk/release/*.apk /out/
sha256sum /out/*.apk
