# Downloads

Settings > Downloads lists optional files for the keyboard: dictionaries for more languages and the next-word models. The keyboard has no network access, so it does not download anything itself. This page explains the steps and the one setting on the screen. The voice model has its own screen: [Voice typing](page:voice).

## How a download works

1. Find the file in the list and tap **Download**. Taen opens the fixed address in your **browser**. The address is pinned to one exact version of the file and points to codeberg.org or huggingface.co. Use Wi-Fi: some files are large.
2. When the browser has saved the file, come back and tap **Install downloaded**. Pick the file from your Downloads folder, under any name. You can pick several files at once.
3. Taen checks the file against the size and SHA-256 that are built into the app. Only a file that matches is installed. A wrong or damaged file is not installed, and nothing else changes. For a dictionary Taen also checks that it is a valid dictionary of the language.
4. The row shows **Installed and verified**.

The screen also shows how much space the downloads use and how much is free. **Remove** deletes one file. **Remove all downloads** deletes every file that you installed from this screen; the dictionaries inside the app are used again. A dictionary that you added by hand under Dictionaries is never replaced or removed from here: remove it there first if you want the downloaded one.

You can cancel an install. It stops by itself if the file provider does not deliver a byte for a minute.

## Dictionaries

Each row is the dictionary of one language, with its licence and its size. A downloaded dictionary is used instead of the one in the app until you remove it. See [Dictionaries](page:dictionaries).

## Next-word models

A next-word model improves the order of the words that are suggested after a word. There is one for English and one for Bulgarian. They run on the phone, read only the text before the cursor, keep nothing on disk and work only where learning is allowed (never in password, incognito or no-learning fields, and never while the phone is locked). They show only words that your dictionaries have, and a word that only the model suggested is never learned when you pick it.

A model is used only when it is **ready**: installed and verified, clearly better than the dictionary on the reference text, and it has passed the **speed test** on this phone (tap **Run speed test**). The row says in plain words if a model is not used: not installed, too slow on this phone, not enough memory, or the keyboard was stopped while loading it. A phone that fails the test can remove the model to free the space. The first words are suggested by the dictionary at once; the model reorders them only if it is ready in time.

### {#nextword_model}
Lets the next-word model reorder the predictions, when one is installed and ready. Default: on. It does nothing until a model is installed and has passed the test. Turn it off if you want the plain dictionary order.

## Where the files are kept

Downloaded dictionaries and models are public files kept inside the app's private storage. They are not part of the encrypted backup (models) and are removed by a self-destruct that includes imported dictionaries. See [Privacy explained](page:privacy).
