# Dictionaries

A dictionary is the list of words that Taen uses for suggestions, autocorrect and gesture typing. Settings > Dictionaries holds the dictionaries that you add yourself. Your personal words and snippets are under Text correction > Personal dictionary ([Text correction](page:text-correction)). Settings > Downloads has ready-made dictionaries that you can install ([Downloads](page:downloads)).

## Built-in dictionaries

English and Bulgarian dictionaries are part of the app. They come from word lists of the Leipzig Corpora Collection (CC BY 4.0), made into dictionaries by the HeliBoard dictionaries project. A language without a dictionary can still be typed; Taen then suggests only words that you have typed before.

## Add-on dictionaries

Settings > Dictionaries lists your languages. For each one:

- the dictionary that comes with the app, if there is one (shown as the internal dictionary),
- the dictionaries that you added, which you can replace or delete.

To add one, choose **Add dictionary from file** and pick a `.dict` file from your phone. Taen checks that the file is a valid dictionary and that its script fits the keyboard. A file that is not accepted is not installed. A dictionary that you add for a language is used before the one in the app.

Where to get a file: the dictionary project that the link in the dialog points to, or the ready-made rows on the Downloads screen, which check the file against a fixed size and SHA-256 before installing. Dictionaries that you added are part of the encrypted backup, and a self-destruct that includes imported dictionaries removes them.

## Personal dictionary and snippets

The personal dictionary is a list of words that you add. Each entry has:

- the **word** (or a short text),
- an optional **shortcut**: type the shortcut and the word is offered, or expanded by autocorrect if **Auto-correct shortcuts** is on,
- a **weight**: how strongly the word is preferred,
- the **language** it belongs to, or all languages.

Use it for names, places, words of your work, and snippets: short texts such as an address or a greeting that you insert with a tap. A word that is already in the list of that language cannot be added twice. The personal dictionary is stored encrypted. It is part of the encrypted backup and is deleted by the self-destruct (the scope "Learned words").

Opening the list from **Snippets and personal dictionary** on the first settings screen is the same list.

## Language-specific and learned words

The words that Taen learns from your typing are separate from the personal dictionary: see [Suggestions and autocorrect](page:suggestions). **Add words to personal dictionary** in Text correction lets often-typed words move into the personal dictionary.
