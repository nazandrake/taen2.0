# Clipboard

Taen keeps a short history of what you copy, so that you can paste it again. The settings are on the Preferences screen ([Preferences](page:preferences)); the clipboard key is on the toolbar. This page explains how the history behaves.

## Open and use the history

- Tap the **clipboard key** on the toolbar. The history opens in place of the letters, with its own toolbar (clear, cursor keys, undo, cut, copy, paste, select word). If you set an app lock, it asks for the PIN first ([Privacy settings](page:privacy-settings)).
- Tap an entry to paste it. This inserts text only and does not touch the system clipboard.
- **Pin** an entry with the pin button on it, or with a long press. A pinned entry stays until you unpin it or clear the history. Pinned entries can be shown on top (**Show pinned items on top**).
- **Clear clipboard** removes every entry, pinned ones too.
- If you turn the history off, the clipboard key pastes the current clipboard content, if any.

## What is kept

- Text only. No pictures or files.
- For one hour by default. You can choose from 5 minutes to 7 days, or Forever (**History retention time**).
- At most 50 unpinned entries by default (**Maximum number of history items**).
- Not stored at all: a copy that an app marks as sensitive, a copy made in a password field or in a field that asks for no personalized learning, a copy in an app on your incognito list or while incognito mode is on, and a copy made while the device or the screen is locked.
- A clip that was already on the clipboard before the keyboard started is not stored.

When the screen locks, the history is dropped from memory until you unlock. Every self-destruct trigger deletes it ([Self-destruct](page:self-destruct)).

## Password fields

In a password field the strip has no paste or clipboard keys by default. **Clipboard keys in password fields** (under Privacy; off by default) shows the paste, select all and clear all keys and your pinned keys there too. A copy made in a password field is marked sensitive: Android hides its preview, and Taen never stores, suggests or logs it.

## Pasting into special fields

A **number field** takes the first number of the pasted text, read with its separators: `1,234.50`, `1.234,50` and `1 234,50` all work. A number that can be read two ways, such as `1,234`, is not pasted. A **phone field** takes its digits, `+`, space, `-`, `(` and `)`. A **date field** takes its own characters. The paste key, a history entry and a clipboard suggestion all follow the same rule.

## Clipboard suggestion

With **Suggest clipboard content** (off by default) a recently copied text is offered on the strip. A clip marked sensitive, from Taen or from another app, is never offered, not even as stars.

## Paste button after copying

With **Suggest Paste after copying** (on by default) a **Paste** button appears first on the strip when you copy text, next to the word suggestions. It shows no text, and Taen reads the clipboard only when you tap it, with the same paste as the paste key (so a number field takes what it accepts). It goes away when you use it, after a minute, when you move to another field, and when the screen locks. It also appears in password fields and for a clip marked sensitive, because it shows nothing; it never appears while the device is locked. It adds nothing to the history.

## Vim mode

In Vim mode the `"+` register is the system clipboard: `"+y` copies and `"+p` pastes through the same code as the copy and paste keys ([Registers, macros, search and the command line](page:vim-power)).
