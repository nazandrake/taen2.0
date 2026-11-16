#!/usr/bin/env bash
# The next-word measurement on the build machine (make nextword-eval, BUILDING.md section 10). Everything it fetches is pinned by SHA-256:
#   the held-out Leipzig text of tools/dict-eval.sh (Wikipedia 10K sentences, English 2016 and Bulgarian 2021, CC BY 4.0),
#   the Bulgarian dictionary and the two models of tools/downloads.lock.
# Then it writes the 1000 contexts per language (tools/nextword/contexts.py, the same contexts as tools/dict-eval.sh) and runs the Kotlin ranking of the
# app on the host (:nextword-engine:nextWordEval). English is measured against the dictionary in the APK, Bulgarian against the downloadable one (the
# Bulgarian dictionary in the APK has no word pairs). Output: one EVAL line per model. Work files: ${TAEN_NEXTWORD_DIR:-~/.cache/taen-nextword}.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="${TAEN_NEXTWORD_DIR:-$HOME/.cache/taen-nextword}"
mkdir -p "$work/models" "$work/text"

fetch() { # url size sha256 file
    local url="$1" size="$2" sha="$3" file="$4"
    if [ -f "$file" ] && [ "$(wc -c < "$file")" -eq "$size" ] && [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$sha" ]; then return 0; fi
    curl --fail --location --retry 3 --continue-at - --output "$file" "$url"
    if [ "$(wc -c < "$file")" -ne "$size" ] || [ "$(sha256sum "$file" | cut -d' ' -f1)" != "$sha" ]; then
        echo "FAILED: $file does not match its pinned size and SHA-256; removed" >&2
        rm -f "$file"
        exit 1
    fi
}

fetch https://downloads.wortschatz-leipzig.de/corpora/eng_wikipedia_2016_10K.tar.gz 2516875 d0c803b7b10d7b42e2da0a3990ded945143594be89f135266da0e8998ffe4edc "$work/text/en.tar.gz"
fetch https://downloads.wortschatz-leipzig.de/corpora/bul_wikipedia_2021_10K.tar.gz 2159757 f2d7e3466030a5ab107029e74b79fd4dd5ce2c37bad820e8ce689f38d88818f3 "$work/text/bg.tar.gz"
tar -xzf "$work/text/en.tar.gz" -C "$work/text" eng_wikipedia_2016_10K/eng_wikipedia_2016_10K-sentences.txt
tar -xzf "$work/text/bg.tar.gz" -C "$work/text" bul_wikipedia_2021_10K/bul_wikipedia_2021_10K-sentences.txt

while IFS="$(printf '\t')" read -r id kind _locale size sha url _licence _source; do
    case "$id" in model-en|model-bg) fetch "$url" "$size" "$sha" "$work/models/$id.onnx" ;; dict-bg) fetch "$url" "$size" "$sha" "$work/models/main_bg.dict" ;; esac
done < <(grep -v '^#' "$root/tools/downloads.lock")

# the word list and word pairs of each dictionary: tools/dict-eval.sh leaves the dump of its last file as dump.tsv in TAEN_NATIVE_TEST_DIR
TAEN_NATIVE_TEST_DIR="$work/en" "$root/tools/dict-eval.sh" "$work/text/eng_wikipedia_2016_10K/eng_wikipedia_2016_10K-sentences.txt" qwerty "$root/app/src/main/assets/dicts/main_en-US.dict"
TAEN_NATIVE_TEST_DIR="$work/bg" "$root/tools/dict-eval.sh" "$work/text/bul_wikipedia_2021_10K/bul_wikipedia_2021_10K-sentences.txt" bg "$work/models/main_bg.dict"
python3 -I "$root/tools/nextword/contexts.py" "$work/en/dump.tsv" "$work/text/eng_wikipedia_2016_10K/eng_wikipedia_2016_10K-sentences.txt" qwerty "$work/eval/en"
python3 -I "$root/tools/nextword/contexts.py" "$work/bg/dump.tsv" "$work/text/bul_wikipedia_2021_10K/bul_wikipedia_2021_10K-sentences.txt" bg "$work/eval/bg"

TAEN_NEXTWORD_MODEL_DIR="$work/models" TAEN_NEXTWORD_EVAL_DIR="$work/eval" "$root/gradlew" --console=plain -p "$root" :nextword-engine:nextWordEval
