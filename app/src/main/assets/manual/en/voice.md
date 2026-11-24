# Voice typing

Voice typing turns speech into text on your phone. The model that recognises speech runs on the device: the sound stays in memory, is never saved, never sent and never logged, and the recognition needs no network. The language is detected from your speech; Bulgarian and English are supported, among others. This page matches Settings > Voice typing.

## Use it

1. Install the voice model once (below).
2. Tap the **voice key** on the strip (it is pinned there by default), or hold it. Tap to start and stop, hold to talk and release to stop.
3. Allow the microphone when Android asks the first time. The microphone is open only while you record.
4. The recognised text is typed into the field.

The voice key is not available in password fields and while the device is locked. If nothing records, see [Troubleshooting](page:troubleshooting).

## Settings

### {#voice_typing}
Shows the voice key. When it is off, the key is hidden and nothing can record. Default: on. Turn it off if you never dictate.

## The voice model

The model is too large for the app (about 670 MB), so Taen does not download it. The keyboard has no network access.

- **Download the model** opens the fixed address of one archive in your browser. Use Wi-Fi.
- **Install downloaded model** lets you pick what you downloaded: the archive, or any of the four files, now or in several rounds. Each file is checked against its fixed SHA-256 before it is installed. A wrong file is not installed.
- **Delete the downloaded copies after installing** (on by default in the screen) removes the copies that you picked, once they are installed.
- The list **Model files** shows which of the four files are installed and has a **Download this file** button for each missing one. The model is ready as soon as all are in place. A line shows how many are installed.
- An install shows its progress and can be cancelled. It stops by itself if the file provider stalls for a minute.
- **Delete the model** removes the installed files.

The model is NVIDIA Parakeet TDT 0.6B v3 (CC BY 4.0), run with ONNX Runtime (MIT). No microphone permission is asked for on this screen; Android asks when you first record.

See [Privacy explained](page:privacy) for what is kept in memory and for how long, and [Downloads](page:downloads) for the other optional files.
