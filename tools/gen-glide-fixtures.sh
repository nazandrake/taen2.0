#!/usr/bin/env bash
# Writes the word lists the glide quality gate runs on: every word of the bundled main dictionaries
# (en-GB and bg), "word<TAB>probability" per line, most probable first, gzipped, from the host build of the
# native core (app/src/main/jni/host_test/dump_words.cpp). Output: app/src/test/resources/glide/<lang>.tsv.gz.
# Usage: tools/gen-glide-fixtures.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
jni="$root/app/src/main/jni"
out="${TAEN_NATIVE_TEST_DIR:-$HOME/.cache/taen-native-test}"
jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
dest="$root/app/src/test/resources/glide"
mkdir -p "$out" "$dest"

mk="$out/list.mk"
printf 'include %s/NativeFileList.mk\nprint:\n\t@echo $(LATIN_IME_CORE_SRC_FILES)\n' "$jni" > "$mk"
files=()
for f in $(make -s -f "$mk" print); do
  files+=("$jni/src/$f")
done
g++ -std=c++17 -O1 -DHOST_TOOL -Wno-narrowing -I"$jni/src" -I"$jdk/include" -I"$jdk/include/linux" \
  "${files[@]}" "$jni/host_test/dump_words.cpp" -o "$out/dump_words"

for pair in en:en-GB bg:bg; do
  lang="${pair%%:*}"
  dict="$root/app/src/main/assets/dicts/main_${pair##*:}.dict"
  "$out/dump_words" "$dict" "$(stat -c %s "$dict")" | gzip -9n > "$dest/$lang.tsv.gz"
  echo "$lang: $(zcat "$dest/$lang.tsv.gz" | wc -l) words"
done
