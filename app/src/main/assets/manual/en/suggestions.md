# Suggestions and autocorrect

This page explains how Taen makes suggestions, what it learns from you, how autocorrect works and how to take a correction back. The settings themselves are listed in [Text correction](page:text-correction).

## Where suggestions come from

While you type a word, the strip above the keys offers words. They come from:

- the **main dictionary** of the language you type in. English and Bulgarian dictionaries are part of the app. Other languages need a dictionary file ([Dictionaries](page:dictionaries) and [Downloads](page:downloads)).
- **add-on dictionaries** that you added.
- your **personal dictionary** and **learned words**.
- **emoji**: names and keywords of emoji, in English and Bulgarian. Type a colon and a word to search only for emoji (**Inline emoji search**).
- the **previous word**: after you finish a word, the strip can show the words that usually come next (**Next-word suggestions**). An optional model on the phone can improve the order ([Downloads](page:downloads)).
- the **clipboard**, if you turn on **Suggest clipboard content**.

Nothing leaves the phone: there is no network.

## What Taen learns

With **Personalized suggestions** on (the default), Taen learns the words you type and finish with a space or a punctuation mark, and uses them in later suggestions. Learned words:

- are kept in memory and saved as one **encrypted** file for each language. They are part of the encrypted backup and are deleted by the self-destruct ([Self-destruct](page:self-destruct)).
- decay: a word you stop using drops out of the suggestions in time.
- are **not learned** in password fields, in fields that ask for no personalized learning, in apps on your incognito list, while incognito mode is on, and while the device or screen is locked. A word that is your trigger word for self-destruct is never learned either.
- are not used as suggestions in an incognito field either.

**Add words to personal dictionary** is a separate opt-in. It lets words that you have typed often move into the personal dictionary, where they stay.

See [Privacy explained](page:privacy) for what is stored and where.

## Autocorrect

With **Auto-correction** on, the space bar or a punctuation mark replaces a mistyped word by the dictionary word that is most likely meant, when the field allows it. How sure Taen has to be is **Auto-correction confidence**. **More auto-correction** also corrects in fields that did not ask for it. Shortcuts of your personal dictionary can be expanded by autocorrect (**Auto-correct shortcuts**).

Potentially offensive words are not suggested (**Block offensive words**).

## Take a correction back

If autocorrect changed a word and you did not want that, press **backspace right after it**. The word you typed comes back, and Taen:

- learns the word you typed (counted as two uses, so that the correction does not come back at once), and
- unlearns the correction.

The usual privacy rules decide first: in incognito, in a password field, for the trigger word or while locked, nothing is learned. Taking a correction back never moves a word into your personal dictionary on its own. You can turn this behavior off with **Backspace reverts autocorrect**.

## The toolbar and the suggestions

The toolbar and the suggestion strip share one row in the default mode. While the toolbar is open it **replaces** the strip, so you see no suggestions. **Auto hide toolbar** (on by default) closes the toolbar by itself when suggestions become available. The other toolbar modes keep only the toolbar, only the strip, or neither ([Toolbar](page:toolbar)).

The strip is not shown at all in password fields, and some apps ask keyboards not to show suggestions. **Always show suggestions** overrides that request, which can cause problems in some apps.

## Spelling

Red underlines in a text come from Android's spell checker. If you set Taen as the default spell checker in Android, **Show spellchecker suggestions** offers corrections when you select an underlined word. In Taen's spell checker, names and abbreviations that no word list contains are not underlined, unless the dictionary has a close word that it suggests instead.
