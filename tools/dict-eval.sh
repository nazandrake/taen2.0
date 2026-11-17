#!/usr/bin/env bash
# Dictionary evaluation: builds the host dump tool (app/src/main/jni/host_test/dump_dict.cpp, the make native-test
# toolchain) and measures, for each "lang name file.dict" row, the five numbers of tools/dict_eval/dict_eval.py on one held-out text.
# Usage: tools/dict-eval.sh <sentences.txt> <lang-for-keyboard: qwerty|bg> <file.dict> [<file.dict> ...]
# Output: one block per file on stdout (save it with the result of each run).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
jni="$root/app/src/main/jni"
out="${TAEN_NATIVE_TEST_DIR:-$HOME/.cache/taen-native-test}"
jdk="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
mkdir -p "$out"
if [ "$#" -lt 3 ]; then echo "usage: $0 <sentences.txt> <qwerty|bg> <file.dict>..." >&2; exit 2; fi
text="$1"; layout="$2"; shift 2

mk="$out/list.mk"
printf 'include %s/NativeFileList.mk\nprint:\n\t@echo $(LATIN_IME_CORE_SRC_FILES)\n' "$jni" > "$mk"
files=()
for f in $(make -s -f "$mk" print); do
  files+=("$jni/src/$f")
done
g++ -std=c++17 -O1 -DHOST_TOOL -Wno-narrowing -I"$jni/src" -I"$jdk/include" -I"$jdk/include/linux" \
  "${files[@]}" "$jni/host_test/dump_dict.cpp" -o "$out/dump_dict"

for dict in "$@"; do
  "$out/dump_dict" "$dict" "$(stat -c %s "$dict")" > "$out/dump.tsv"
  python3 -I "$root/tools/dict_eval/dict_eval.py" "$dict" "$out/dump.tsv" "$text" "$layout"
done
