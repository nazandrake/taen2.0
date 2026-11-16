// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * The voice model the app accepts: the four files of the sherpa-onnx int8 export of NVIDIA parakeet-tdt-0.6b-v3 (CC-BY-4.0, ONNX files
 * made by k2-fsa/sherpa-onnx from the NeMo model), at the pinned Hugging Face revision of csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.
 * The app has no network: the browser downloads the files from the pinned URLs (Settings > Voice typing > Download model files, or `make fetch-voice-model`
 * on the PC; tools/voice-model.lock lists URL, size and SHA-256, VoicePolicyTest keeps this list equal to the lock file) and the user installs them. A file is accepted by its content only.
 * The SHA-256 values are the Hugging Face LFS object ids of the files, and equal the hashes of the downloaded files.
 */
object VoiceModelSpec {
    /** [url] is the pinned download address that "Download model files" opens in the browser (the app itself never connects to it). */
    class Entry(val name: String, val size: Long, val sha256: String, val url: String = "")

    val entries: List<Entry> = listOf(
        Entry("encoder.int8.onnx", 652_282_294L, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479",
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/encoder.int8.onnx?download=true"),
        Entry("decoder.int8.onnx", 11_845_274L, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5",
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/decoder.int8.onnx?download=true"),
        Entry("joiner.int8.onnx", 6_355_277L, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98",
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/joiner.int8.onnx?download=true"),
        Entry("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/tokens.txt?download=true"),
    )

    /**
     * The one archive of the same model: the official k2-fsa/sherpa-onnx release asset, equal in content to the four files above (each file inside was measured
     * equal in size and SHA-256, tools/voice/check_archive.py). One download, one install: [folder] is the only folder the four files are read from.
     * Pinned by size and SHA-256 like the files; tools/voice-model-archive.lock lists it (VoicePolicyTest keeps them equal).
     */
    class Archive(val name: String, val size: Long, val sha256: String, val url: String, val folder: String)

    val archive = Archive(
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2", 487_211_443L, "1495e91b59d7fc53ddca91f3431758ec99f8fa253fdb0e1e851be0f72b14b0f9",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
    )

    val totalBytes: Long = entries.sumOf { it.size }
    val largestBytes: Long = entries.maxOf { it.size }

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"

    fun bySha256(sha256: String): Entry? = entries.firstOrNull { it.sha256 == sha256 }
}
