#!/usr/bin/env bash
# Host build of the native dictionary core plus the in-memory serialization test (app/src/main/jni/host_test).
# The core compiles without Android (HOST_TOOL); jni.h comes from the JDK. Build output: ~/.cache/taen-native-test.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
jni="$root/app/src/main/jni"
out="${TAEN_NATIVE_TEST_DIR:-$HOME/.cache/taen-native-test}"
jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
mkdir -p "$out"
export TAEN_NATIVE_TEST_DIR="$out" # the test writes its hand-made dictionaries here

# TAEN_NATIVE_SANITIZE=thread|address builds the same test with that sanitizer (own binary name) and runs it: thread checks the
# shared transient word list of Dictionary::getWordsBulk and the worker threads of the one-pass walk, address the bounds
sanitize="${TAEN_NATIVE_SANITIZE:-}"
san_flags=()
name=memory_dict_test
if [ -n "$sanitize" ]; then
  case "$sanitize" in
    thread|address) ;;
    *) echo "TAEN_NATIVE_SANITIZE must be thread or address" >&2; exit 2 ;;
  esac
  san_flags=("-fsanitize=$sanitize" -fno-omit-frame-pointer)
  name="memory_dict_test_$sanitize"
fi

# the core file list comes from NativeFileList.mk (the same list the Android build compiles)
mk="$out/list.mk"
printf 'include %s/NativeFileList.mk\nprint:\n\t@echo $(LATIN_IME_CORE_SRC_FILES)\n' "$jni" > "$mk"
files=()
for f in $(make -s -f "$mk" print); do
  files+=("$jni/src/$f")
done

g++ -std=c++17 -O1 -g -pthread -DHOST_TOOL -Wno-narrowing "${san_flags[@]}" -I"$jni/src" -I"$jdk/include" -I"$jdk/include/linux" \
  "${files[@]}" "$jni/host_test/memory_dict_test.cpp" -o "$out/$name"
# the two bundled main dictionaries (format 202) take the one-pass word walk of the glide typing word list; the number is the word
# count with a probability, a change of a dictionary file changes it on purpose
dicts="$root/app/src/main/assets/dicts"
"$out/$name" \
  "$dicts/main_en-GB.dict" "$(stat -c %s "$dicts/main_en-GB.dict")" 386504 \
  "$dicts/main_bg.dict" "$(stat -c %s "$dicts/main_bg.dict")" 898049
