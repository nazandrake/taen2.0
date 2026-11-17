# Thin wrappers around ./gradlew and tools/*.sh for Linux and macOS. On Windows run gradlew.bat directly (BUILDING.md).
# Everything runs locally; there is no cloud CI. `make help` lists the targets.

GRADLE   ?= ./gradlew --console=plain
VERSION  := $(shell sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
UNSIGNED := app/build/outputs/apk/release/Taen_$(VERSION)-release.apk
ENGINE   := $(shell command -v podman 2>/dev/null || command -v docker 2>/dev/null)
IMAGE    ?= taen-build:local
GRADLE_CACHE ?= $(HOME)/.cache/taen-gradle

.PHONY: help build debug test test-build-scripts check fetch-voice-model voice-smoke nextword-eval leak-audit leak-audit-device native-test native-test-tsan native-test-asan offline-build verify-repro keygen sign hooks container container-build container-verify-repro clean

help:
	@echo "build         unsigned release APK -> dist/taen-$(VERSION)-unsigned.apk (runs privacyCheck first)"
	@echo "debug         debug APK"
	@echo "test          unit tests (buildSrc and app)"
	@echo "test-build-scripts  tests of tools/build-linux.sh and tools/build-windows.ps1 (detection, options, dry runs; no network)"
	@echo "check         privacyCheck + unit tests, what the pre-push hook runs without the reproducibility step"
	@echo "fetch-voice-model  download the voice model files (about 670 MB) with pinned URLs and SHA-256 -> dist/voice-model; tools/fetch-voice-model.sh --archive fetches the one archive instead (BUILDING.md section 8)"
	@echo "voice-smoke   the Kotlin voice engine on the host with the real model and the FLEURS samples (needs fetch-voice-model and tools/voice/prepare_samples.py)"
	@echo "nextword-eval the next-word models on the held-out text on the host (fetches the pinned models, about 750 MB) -> EVAL lines"
	@echo "leak-audit   static leak checks of the release APK + planted-failure proofs -> build/leak-audit/leak-audit-report.md"
	@echo "leak-audit-device  scripted session on the phone (adb, audit build and release build) -> same report; about 25 minutes"
	@echo "native-test   host build of the native dictionary core + serialization test (g++, no Android needed)"
	@echo "native-test-tsan / native-test-asan  the same test built with ThreadSanitizer / AddressSanitizer"
	@echo "offline-build the release build with --offline (needs one earlier online build)"
	@echo "verify-repro  two clean builds of HEAD in separate directories; the unsigned APK hashes must match"
	@echo "keygen        create a signing key outside the repo (~/.config/taen/release.p12)"
	@echo "sign          sign dist/taen-$(VERSION)-unsigned.apk -> dist/taen-$(VERSION)-signed.apk"
	@echo "hooks         enable githooks/ (public check on commit, build gate and reproducibility on push)"
	@echo "container     build the pinned build image (podman or docker)"
	@echo "container-build  release build of HEAD inside the image -> dist/"
	@echo "container-verify-repro  verify-repro inside the image"

build:
	$(GRADLE) :app:assembleRelease
	mkdir -p dist
	cp $(UNSIGNED) dist/taen-$(VERSION)-unsigned.apk
	cd dist && sha256sum taen-$(VERSION)-unsigned.apk | tee SHA256SUMS

debug:
	$(GRADLE) :app:assembleDebug

test:
	$(GRADLE) :buildSrc:test
	$(GRADLE) :voice-engine:test :nextword-engine:test
	$(GRADLE) :app:testRunTestsUnitTest

test-build-scripts:
	tools/test-build-scripts.sh

check:
	$(GRADLE) :buildSrc:test
	$(GRADLE) :voice-engine:test :nextword-engine:test
	$(GRADLE) privacyCheck :app:testRunTestsUnitTest

# Voice model (BUILDING.md section 8): the app has no network, so the builder fetches the files here; the user imports them on the phone.
fetch-voice-model:
	tools/fetch-voice-model.sh

# The Kotlin engine against the real model and 20 EN + 20 BG FLEURS utterances; prints the SMOKE lines and fails above EN 10 % / BG 20 % WER.
# Defaults: dist/voice-model and ~/.cache/taen-voice-samples (tools/voice/prepare_samples.py writes en/ and bg/ there).
voice-smoke:
	TAEN_VOICE_MODEL_DIR=$${TAEN_VOICE_MODEL_DIR:-$(CURDIR)/dist/voice-model} TAEN_VOICE_SAMPLES_DIR=$${TAEN_VOICE_SAMPLES_DIR:-$(HOME)/.cache/taen-voice-samples} $(GRADLE) :voice-engine:voiceSmoke

# Next-word models (BUILDING.md section 10): fetches the two pinned models and the held-out text, writes the contexts, then runs the Kotlin ranking on the
# host (:nextword-engine:nextWordEval) and prints one EVAL line per model. Files go to ~/.cache/taen-nextword.
nextword-eval:
	tools/nextword-eval.sh

# Leak audit (BUILDING.md section 9): needs the release APK (make build). The device part needs the phone on adb and, for the release
# session, a signed APK (make sign, or a throw-away test key); it builds the audit APK of the checked-out commit first.
# Both leak-audit targets then stop this project's idle Gradle daemons (about 4.7 GB each); the dependency listing runs with --no-daemon.
leak-audit:
	-./gradlew --stop
	python3 tools/leak_audit static
	python3 tools/leak_audit selftest

leak-audit-device:
	$(GRADLE) :app:assembleAudit
	-./gradlew --stop
	python3 tools/leak_audit device

native-test:
	tools/native-test.sh

native-test-tsan:
	TAEN_NATIVE_SANITIZE=thread tools/native-test.sh

native-test-asan:
	TAEN_NATIVE_SANITIZE=address tools/native-test.sh

offline-build:
	$(GRADLE) --offline :app:assembleRelease

verify-repro: # stops the project's idle Gradle daemons first (tools/verify-repro.sh), then builds twice with --no-daemon
	tools/verify-repro.sh

keygen:
	tools/keygen.sh

sign:
	tools/sign.sh dist/taen-$(VERSION)-unsigned.apk dist/taen-$(VERSION)-signed.apk
	cd dist && sha256sum taen-$(VERSION)-signed.apk | tee -a SHA256SUMS

hooks:
	scripts/setup-hooks

container:
	@test -n "$(ENGINE)" || { echo "install podman or docker"; exit 2; }
	$(ENGINE) build -t $(IMAGE) -f tools/container/Containerfile tools/container

container-build:
	@test -n "$(ENGINE)" || { echo "install podman or docker"; exit 2; }
	mkdir -p $(GRADLE_CACHE) dist
	$(ENGINE) run --rm -v "$(CURDIR)":/src:ro -v "$(CURDIR)/dist":/out -v "$(GRADLE_CACHE)":/gradle \
	  -e GRADLE_USER_HOME=/gradle $(IMAGE) taen-build

container-verify-repro:
	@test -n "$(ENGINE)" || { echo "install podman or docker"; exit 2; }
	mkdir -p $(GRADLE_CACHE)
	$(ENGINE) run --rm -v "$(CURDIR)":/src:ro -v "$(GRADLE_CACHE)":/gradle -e GRADLE_USER_HOME=/gradle \
	  -e GRADLE_OPTS_EXTRA="" $(IMAGE) /src/tools/verify-repro.sh

clean:
	$(GRADLE) clean
	rm -rf dist
