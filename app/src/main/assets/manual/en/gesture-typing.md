# Gesture typing

With gesture typing you type a word by sliding your finger over its letters without lifting it, and lift at the end of the word. A space is added by default. Taen's glide engine is open source and runs inside the app: it needs no network, no model file and no download, and nothing records your swipes. See [Privacy explained](page:privacy).

How it works: the engine compares the path of your finger with the words of the dictionary of the language and with your learned words and personal dictionary, and shows the best words on the strip. English glide typing gets about 80 percent of the words right on a synthetic test set. If a word comes out wrong, tap a suggestion, or type it. Turn the feature off if you do not use it.

The spacing around a gesture word is in [Text correction](page:text-correction): **Autospace before gesture typing a word** and **Autospace after gesture typing a word**.

## Settings

### {#gesture_input}
Turns gesture typing on. Slide through the letters of a word. Default: on.

### {#gesture_preview_trail}
Draws the line that follows your finger while you slide. Shown only when gesture typing is on. Default: on.

### {#gesture_floating_preview_text}
Shows the word that Taen suggests above your finger while you slide, so that you see the result before you lift. Shown only when gesture typing is on. Default: on.

### {#gesture_floating_preview_dynamic}
Lets the preview word move with your finger instead of staying in one place. Shown only when the floating preview is on. Default: on, unless Android has animations turned off. Turn it off if the moving word distracts you.

### {#gesture_fast_typing_cooldown}
When you type quickly, a slide from one key to the next could be taken for a gesture. After you tap a key, gesture typing waits this long before a new slide counts as a gesture. From 0 (always start instantly) to 500 milliseconds. Shown only when gesture typing is on. Default: 500 ms. Lower it if your gestures do not start; raise it if fast typing is taken for gestures.

### {#gesture_trail_fadeout_duration}
How long the trail and the preview stay visible after you lift your finger, from 0.2 to 2 seconds. Shown only when the trail or the preview is on. Default: 0.9 seconds.
