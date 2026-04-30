<style>
@media print {
  @page { margin: 18mm 15mm 20mm; size: A4; }
  .page-break { page-break-before: always; }
  h2 { page-break-after: avoid; }
  h3 { page-break-after: avoid; }
  table { page-break-inside: avoid; }
  pre { page-break-inside: avoid; }
  blockquote { page-break-inside: avoid; }
  .no-print { display: none; }
}
</style>

<div style="background: linear-gradient(135deg, #080c22 0%, #111740 40%, #0d1233 100%); border-radius: 14px; padding: 44px 32px 30px; text-align: center; margin-bottom: 40px; border: 1px solid rgba(0, 71, 171, 0.25); box-shadow: 0 4px 24px rgba(0, 0, 0, 0.3);">
  <img src="jurgistan.png" alt="Safe Word Logo" style="height: 56px; opacity: 0.92;" />
  <div style="text-align: right; padding-right: 20px; margin-top: 2px;">
    <span style="color: #0047AB; font-size: 10.5px; font-style: italic; letter-spacing: 0.8px;">Made in Jurgistan</span>
  </div>
  <h1 style="color: #ffffff; font-size: 30px; margin: 22px 0 6px; letter-spacing: -0.3px; font-weight: 700;">Safe Word Android</h1>
  <p style="color: #8892b0; font-size: 17px; margin: 0 0 14px; font-weight: 300;">Complete Voice Commands Reference</p>
  <p style="color: #4a5580; font-size: 12px; margin: 0; letter-spacing: 1px; text-transform: uppercase;">Version 1.2 &nbsp;·&nbsp; April 2026 &nbsp;·&nbsp; On-Device Voice-to-Text Overlay</p>
</div>

---

<div style="background: #f8f9fc; border: 1px solid #e2e6f0; border-radius: 10px; padding: 24px 28px 20px; margin: 16px 0 32px;">

<h2 style="margin-top: 0; color: #1a1f4e;">📋 Table of Contents</h2>

1. [Getting Started](#getting-started)
2. [Session Control](#session-control)
3. [Voice Commands](#voice-commands) — Deletion · Undo & Redo · Selection · Word Recognition · Clipboard · Replace & Switch · Navigation · Formatting
4. [Custom Voice Commands](#custom-voice-commands)
5. [Semantic Understanding](#semantic-understanding)
6. [Spelling Mode & Letters](#spelling-mode--letters)
7. [Spoken Emoji](#spoken-emoji)
8. [Spoken Punctuation & Spacing](#spoken-punctuation-spacing)
9. [Number Words & Ordinals](#number-words--ordinals)
10. [Fractions](#fractions)
11. [Tips & Tricks](#tips--tricks)
12. [Accessibility](#accessibility)

</div>

---

## Getting Started

### Voice Command Format

All voice commands follow this general pattern:

<div style="background: #f0f2f8; border-left: 4px solid #0047AB; border-radius: 0 8px 8px 0; padding: 14px 18px; margin: 12px 0; font-family: monospace; font-size: 14px; color: #1a1f4e;">
[Wake Word] &nbsp; [Polite Prefix] &nbsp; <strong>[Command]</strong> &nbsp; [Polite Suffix]
</div>

| Component | Required | Options |
|-----------|----------|---------|
| **Wake Word** | Optional | `Safe Word` or `OK Safe Word` |
| **Polite Prefix** | Optional | `please`, `can you`, `could you`, `would you`, `will you` |
| **Command** | **Required** | The action you want to perform |
| **Polite Suffix** | Optional | `please`, `thanks`, `thank you` |

### Examples

| Spoken | Interpreted As |
|--------|----------------|
| `delete that` | Delete selected text |
| `okay safe word delete that please` | Delete selected text |
| `can you select all thank you` | Select all text |
| `please undo` | Undo last action |

<div class="page-break"></div>

---

## Session Control

Control the recording session and app state.

| Command | Alternatives | Action |
|---------|---|---|
| **stop listening** | stop recording, stop, stop dictation, done | Stop voice recording session |
| **send** | send that, send message, send it | Send current message (if supported by app) |
| **submit** | submit that, confirm | Submit form or dialog |
| **clear** | clear all, clear everything, erase all, erase everything | Clear all text from field |
| **go back** | navigate back | Navigate to previous screen |
| **open settings** | dictation settings, safe word settings | Open app settings |
| **dismiss** | dismiss keyboard, hide keyboard, close keyboard | Hide onscreen keyboard |

<div class="page-break"></div>

---

## Voice Commands

### Deletion

Delete text from the current position, selection, or by specifying a target word.

| Command | Alternatives | Action |
|---------|---|---|
| **delete that** | delete this, erase that, remove that | Delete currently selected text |
| **delete last word** | erase last word | Delete the word before cursor |
| **delete last sentence** | delete sentence, erase last sentence | Delete the previous sentence |
| **backspace** | — | Delete one character before cursor |
| **delete last paragraph** | erase last paragraph | Delete the previous paragraph |
| **delete [word]** | erase [word], remove [word] | Find and delete a specific word from the text |
| **delete the word [word]** | erase the word [word] | Find and delete a specific word (explicit form) |

### Undo & Redo

Undo mistakes and redo changes.

| Command | Alternatives | Action |
|---------|---|---|
| **undo** | undo that, scratch that, take that back | Undo the last action |
| **redo** | redo that | Redo the last undone action |

### Selection

Select text by position, scope, or by specifying a target word.

| Command | Alternatives | Action |
|---------|---|---|
| **select all** | highlight all | Select all text in the field |
| **select last word** | — | Select the word before cursor |
| **select last sentence** | — | Select the previous sentence |
| **select last paragraph** | — | Select the previous paragraph |
| **select word** | select this word, select current word | Select the word at cursor |
| **select sentence** | select this sentence, select current sentence | Select the current sentence |
| **select line** | select this line, select current line | Select the current line |
| **select to beginning** | select to start | Select from cursor to document start |
| **select to end** | — | Select from cursor to document end |
| **select [word]** | highlight [word] | Find and select a specific word in the text |
| **select the word [word]** | highlight the word [word] | Find and select a specific word (explicit form) |

### Word Recognition

Target specific words in the text field by name. The system finds the first occurrence of the spoken word and performs the action on it.

| Command | Examples | Action |
|---------|----------|--------|
| **select [word]** | "select hello", "select world" | Find and highlight the word |
| **highlight [word]** | "highlight error", "highlight the word test" | Find and highlight the word |
| **copy [word]** | "copy email", "copy the word address" | Find, select, and copy the word |
| **delete [word]** | "delete typo", "delete the word oops" | Find and remove the word |
| **erase [word]** | "erase mistake" | Find and remove the word |
| **remove [word]** | "remove extra" | Find and remove the word |

<div style="background: #f0f7ff; border-left: 4px solid #0047AB; border-radius: 0 8px 8px 0; padding: 10px 16px; margin: 12px 0; font-size: 14px;">
<strong>📌 Note:</strong> Word targets are limited to 1–3 words. Commands like "select all", "delete that", "copy this" retain their original behavior and are not treated as word targets.
</div>

### Clipboard

Copy, cut, and paste text.

| Command | Alternatives | Action |
|---------|---|---|
| **copy** | copy that, copy this | Copy selected text to clipboard |
| **copy [word]** | copy the word [word] | Find, select, and copy a specific word |
| **cut** | cut that, cut this | Cut selected text to clipboard |
| **paste** | paste that, paste here | Paste clipboard content at cursor |

### Replace & Switch

Replace or switch words and phrases in the text. All variations work equivalently.

| Command | Example | Action |
|---------|---------|--------|
| **replace [X] with [Y]** | "replace hello with goodbye" | Find X and replace with Y |
| **change [X] to [Y]** | "change Monday to Tuesday" | Find X and replace with Y |
| **swap [X] with [Y]** | "swap red with blue" | Find X and replace with Y |
| **switch [X] with [Y]** | "switch old with new" | Find X and replace with Y |
| **switch [X] to [Y]** | "switch morning to evening" | Find X and replace with Y |
| **find [X] and replace with [Y]** | "find bug and replace with fix" | Find X and replace with Y |

<div style="background: #f0fff4; border-left: 4px solid #38a169; border-radius: 0 8px 8px 0; padding: 10px 16px; margin: 12px 0; font-size: 14px;">
<strong>💡 Tip:</strong> These commands are case-insensitive. Say <code>"change hello to HELLO"</code> to change casing.
</div>

### Navigation

Move cursor and navigate the document.

| Command | Alternatives | Action |
|---------|---|---|
| **new line** | newline, next line, go to next line, enter, press enter, hit enter | Insert line break (Enter) |
| **new paragraph** | next paragraph | Insert paragraph break (double Enter) |
| **move to end** | go to end, jump to end | Move cursor to end of document |
| **move to beginning** | go to beginning, go to start, jump to start, move to start | Move cursor to start of document |
| **go to end of line** | move to end of line, end of line | Move cursor to end of current line |
| **go to start of line** | move to start of line, beginning of line | Move cursor to start of current line |
| **scroll up** | page up | Scroll up in document |
| **scroll down** | page down | Scroll down in document |

### Formatting

Apply text formatting and styling.

| Command | Alternatives | Action |
|---------|---|---|
| **capitalize that** | — | Capitalize first letter of selection |
| **uppercase that** | make that uppercase, make that upper case | Convert selection to UPPERCASE |
| **lowercase that** | make that lowercase, make that lower case | Convert selection to lowercase |
| **bold** | bold that, make that bold | Apply bold formatting |
| **italic** | italic that, italicize that, make that italic | Apply italic formatting |
| **underline** | underline that, make that underline, make that underlined | Apply underline formatting |

<div class="page-break"></div>

---

## Custom Voice Commands

### Creating Custom Commands

Safe Word allows you to create custom voice commands tailored to your needs.

**To create a custom command:**

1. Open **Safe Word settings** (via `"open settings"` or app menu)
2. Navigate to **Custom Commands** section
3. Tap **Add Command**
4. Enter trigger phrase(s) and select action
5. Save

### Custom Command Actions

Custom commands support the same actions as built-in commands:

- **Text insertion** — Insert custom text (name, address, frequently used phrase)
- **Clipboard** — Copy, cut, paste
- **Navigation** — Move cursor, select text
- **Formatting** — Apply bold, italic, underline
- **Session control** — Send, submit, clear, dismiss

### Example Custom Commands

| Trigger | Action | Use Case |
|---------|--------|----------|
| "my email" | Insert your email address | Quick contact entry |
| "my phone" | Insert your phone number | Contact information |
| "my address" | Insert your address | Forms, shipping |
| "thanks" | Insert custom thank you message | Email signatures |
| "signature" | Insert name/company signature | Professional emails |
| "website" | Insert website URL | Sharing links |

<div class="page-break"></div>

---

## Semantic Understanding

Safe Word uses a multi-layer command detection system with semantic understanding. Commands don't need to be spoken verbatim — the system understands natural language variations.

### How It Works

Command detection uses three layers, in priority order:

<div style="background: #f0f7ff; border: 1px solid #cce0ff; border-radius: 8px; padding: 16px 20px; margin: 12px 0;">

1. **🎯 Exact Match** — Direct hash lookup for canonical phrases (fastest, zero ambiguity)
2. **🧠 Semantic Intent Recognition** — Keyword-weighted scoring that understands paraphrased commands
3. **🔍 Fuzzy Match** — Levenshtein distance correction for minor ASR transcription errors

</div>

### Natural Language Examples

The semantic recognizer handles many natural-language variations:

| Spoken (Natural) | Understood As | Action |
|-------------------|---------------|--------|
| "remove everything" | ClearAll | Clears all text |
| "erase the last word" | DeleteLastWord | Deletes previous word |
| "take that back" | Undo | Undoes last action |
| "get rid of the selection" | DeleteSelection | Deletes selected text |
| "highlight everything" | SelectAll | Selects all text |
| "go to the bottom" | MoveCursorToEnd | Moves cursor to end |
| "jump to the beginning" | MoveCursorToStart | Moves cursor to start |
| "that's enough" | StopListening | Stops recording |
| "I'm finished" | StopListening | Stops recording |
| "make it bold" | BoldSelection | Bolds selection |
| "put that in italics" | ItalicSelection | Italicizes selection |

### Confidence Levels

<div style="background: #fffbf0; border-left: 4px solid #f0a030; border-radius: 0 8px 8px 0; padding: 14px 18px; margin: 12px 0;">

| Threshold | Commands | Confidence |
|-----------|----------|------------|
| **Standard** | Most commands | ≥ 45% |
| **Destructive** | delete, undo, clear | ≥ 65% |
| **Below threshold** | — | Treated as dictation text |

</div>

### Context-Aware Gating

Commands are gated based on the type of input field:

| Field Type | Behavior |
|------------|----------|
| **Password** | All commands suppressed — text passed through as-is |
| **Search bar** | Formatting and word-targeting commands suppressed |
| **Messaging** | All commands available including Send |
| **Text** | All commands except Search available |

<div class="page-break"></div>

---

## Spelling Mode & Letters

Spell out words letter by letter, or insert individual letters. Supports the NATO phonetic alphabet.

### Spell Mode

Say `"spell"` followed by individual letters (separated by spaces) to spell a word:

| Command | Result | Notes |
|---------|--------|-------|
| **spell a b c** | abc | Space-separated single letters |
| **spell h e l l o** | hello | Spells out "hello" |
| **spell alpha bravo charlie** | abc | NATO phonetic alphabet |
| **spell a bravo c** | abc | Mixed letters and NATO |
| **type out h e l l o** | hello | Alternative to "spell" |

### NATO Phonetic Alphabet

```
alpha / alfa   → a     hotel     → h     oscar    → o     victor   → v
bravo          → b     india     → i     papa     → p     whiskey  → w
charlie        → c     juliet    → j     quebec   → q     x-ray    → x
delta          → d     kilo      → k     romeo    → r     yankee   → y
echo           → e     lima      → l     sierra   → s     zulu     → z
foxtrot        → f     mike      → m     tango    → t
golf           → g     november  → n     uniform  → u
```

### Single Letter Insertion

| Command | Alternatives | Result |
|---------|---|--------|
| **letter a** | type letter a, the letter a | Inserts "a" |
| **letter b** | type letter b, the letter b | Inserts "b" |
| ... | ... | Any letter A–Z |

### Single Letter Recognition

Say any single letter name (A through Z) while in spell mode. The system recognizes:
- **Direct letters**: A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z
- **NATO alphabet**: Alpha, Bravo, Charlie, Delta, Echo, Foxtrot, Golf, Hotel, India, Juliet, Kilo, Lima, Mike, November, Oscar, Papa, Quebec, Romeo, Sierra, Tango, Uniform, Victor, Whiskey, X-ray, Yankee, Zulu

<div class="page-break"></div>

---

## Spoken Emoji

### Complete Emoji Vocabulary

Speak the emoji name followed by the word **"emoji"** to insert emoji characters.

<div style="background: #fef9f0; border: 1px solid #f0d8a0; border-radius: 8px; padding: 14px 18px; margin: 12px 0;">

**Format**: `[emoji name] emoji`

**Example**: Say `"thumbs up emoji"` to insert 👍

</div>

#### Smileys & Emotion

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| happy face emoji | 😄 | sad face emoji | ☹️ |
| smiley face emoji | 😊 | crying face emoji | 😢 |
| laughing face emoji | 😂 | winking face emoji | 😉 |
| heart eyes emoji | 😍 | thinking face emoji | 🤔 |
| rolling eyes emoji | 🙄 | mind blown emoji | 🤯 |
| face palm emoji | 🤦 | shrug emoji | 🤷 |
| clown face emoji | 🤡 | skull emoji | 💀 |
| angry face emoji | 😠 | surprised face emoji | 😮 |
| sunglasses face emoji | 😎 | nerd face emoji | 🤓 |
| kissing face emoji | 😘 | angel face emoji | 😇 |
| devil face emoji | 😈 | sleeping face emoji | 😴 |
| drooling face emoji | 🤤 | zany face emoji | 🤪 |
| pleading face emoji | 🥺 | hugging face emoji | 🤗 |

#### Gestures

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| thumbs up emoji | 👍 | thumbs down emoji | 👎 |
| clapping hands emoji | 👏 | raised hands emoji | 🙌 |
| crossed fingers emoji | 🤞 | muscle emoji | 💪 |
| wave emoji | 👋 | pointing up emoji | ☝️ |
| ok hand emoji | 👌 | peace sign emoji | ✌️ |
| fist bump emoji | 🤜 | high five emoji | 🙏 |
| middle finger emoji | 🖕 | handshake emoji | 🤝 |
| writing hand emoji | ✍️ | nail polish emoji | 💅 |
| pray emoji | 🙏 |

#### Hearts & Love

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| red heart emoji | ❤️ | heart emoji | ❤️ |
| broken heart emoji | 💔 | fire heart emoji | ❤️‍🔥 |
| sparkling heart emoji | 💖 | blue heart emoji | 💙 |
| green heart emoji | 💚 | purple heart emoji | 💜 |

#### Common Objects & Symbols

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| fire emoji | 🔥 | star emoji | ⭐ |
| sparkles emoji | ✨ | lightning emoji | ⚡ |
| sun emoji | ☀️ | moon emoji | 🌙 |
| rainbow emoji | 🌈 | cloud emoji | ☁️ |
| snowflake emoji | ❄️ | umbrella emoji | ☂️ |
| rocket emoji | 🚀 | airplane emoji | ✈️ |
| check mark emoji | ✅ | cross mark emoji | ❌ |
| warning sign emoji | ⚠️ | light bulb emoji | 💡 |
| money bag emoji | 💰 | crown emoji | 👑 |
| trophy emoji | 🏆 | gift emoji | 🎁 |
| balloon emoji | 🎈 | party popper emoji | 🎉 |
| confetti emoji | 🎊 | bell emoji | 🔔 |
| megaphone emoji | 📣 |

#### Face Features

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| eyes emoji | 👀 | brain emoji | 🧠 |
| ghost emoji | 👻 | alien emoji | 👽 |
| robot emoji | 🤖 |

#### Food & Drink

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| pizza emoji | 🍕 | coffee emoji | ☕ |
| beer emoji | 🍺 | wine emoji | 🍗 |
| taco emoji | 🌮 | cake emoji | 🎂 |
| ice cream emoji | 🍨 | cookie emoji | 🍪 |
| avocado emoji | 🥑 | hot dog emoji | 🌭 |
| hamburger emoji | 🍔 | popcorn emoji | 🍿 |
| banana emoji | 🍌 |

#### Animals

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| dog emoji | 🐶 | cat emoji | 🐱 |
| unicorn emoji | 🦄 | butterfly emoji | 🦋 |
| snake emoji | 🐍 | panda emoji | 🐼 |
| monkey emoji | 🐵 | penguin emoji | 🐧 |
| frog emoji | 🐸 | lion emoji | 🦁 |
| bear emoji | 🐻 | fox emoji | 🦊 |
| shark emoji | 🦈 |

#### Nature

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| rose emoji | 🌹 | cherry blossom emoji | 🌸 |
| four leaf clover emoji | 🍀 | seedling emoji | 🌱 |
| globe emoji | 🌍 |

#### Reactions & Actions

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| poop emoji | 💩 | hundred emoji | 💯 |
| eye roll emoji | 🙄 | boom emoji | 💥 |
| sweat drops emoji | 💦 | dizzy emoji | 💫 |

#### Objects (Extended)

| Spoken | Emoji | Spoken | Emoji |
|--------|-------|--------|-------|
| gem emoji | 💎 | musical note emoji | 🎵 |
| camera emoji | 📷 | lock emoji | 🔒 |
| magnifying glass emoji | 🔍 |

<div class="page-break"></div>

---

## Spoken Punctuation & Spacing

### Quick Reference

| Command | Alternatives | Action |
|---------|---|---|
| **space** | add a space, type a space | Insert single space |
| **tab** | — | Insert tab character |
| **period** | full stop, dot | Insert `. ` (period + space) |
| **comma** | add a comma | Insert `,` |
| **question mark** | — | Insert `?` |
| **exclamation point** | exclamation mark | Insert `!` |
| **colon** | — | Insert `:` |
| **semicolon** | — | Insert `;` |
| **hyphen** | — | Insert `-` |
| **dash** | — | Insert ` — ` (em dash with spaces) |
| **ellipsis** | dot dot dot | Insert `…` |
| **open bracket** | open square bracket | Insert `[` |
| **close bracket** | close square bracket | Insert `]` |
| **open parenthesis** | open paren | Insert `(` |
| **close parenthesis** | close paren | Insert `)` |
| **open curly** | open brace | Insert `{` |
| **close curly** | close brace | Insert `}` |
| **open quote** | — | Insert `"` |
| **close quote** | — | Insert `"` |
| **at sign** | at symbol | Insert `@` |
| **hashtag** | hash, number sign | Insert `#` |
| **ampersand** | and sign | Insert `&` |
| **asterisk** | star symbol | Insert `*` |

### Comprehensive Punctuation Map

Insert punctuation marks by speaking their names. All commands are case-insensitive.

#### Whitespace & Structure
```
new paragraph    →  \n\n    (double line break)
new line         →  \n     (line break)
```

#### Sentence-Ending Punctuation
```
period           →  .
full stop        →  .
question mark    →  ?
exclamation point→  !
exclamation mark →  !
ellipsis         →  …
dot dot dot      →  …
```

#### Mid-Sentence Punctuation
```
comma            →  ,
colon            →  :
semicolon        →  ;
hyphen           →  -
dash             →     —     (em dash with spaces)
em dash          →     —     (em dash with spaces)
```

#### Paired Delimiters
```
open parenthesis    →  (
close parenthesis   →  )
open paren          →  (
close paren         →  )

open bracket        →  [
close bracket       →  ]
open square bracket →  [
close square bracket→  ]

open brace          →  {
close brace         →  }
open curly          →  {
close curly         →  }

open quote          →  "
close quote         →  "
open single quote   →  '
close single quote  →  '
```

#### Slashes
```
forward slash       →  /
slash               →  /
backslash           →  \
```

#### Common Symbols
```
at sign             →  @
ampersand           →  &
hash                →  #
hashtag             →  #
pound sign          →  #

asterisk            →  *
apostrophe          →  '
underscore          →  _
tilde               →  ~
pipe                →  |
caret               →  ^

degree sign         →  °
copyright sign      →  ©
trademark           →  ™
registered sign     →  ®
registered trademark→  ®
```

#### Mathematical & Currency
```
plus sign           →  +
plus                →  +
minus sign          →  -
equal sign          →  =
equals              →  =
equals sign         →  =

greater than        →  >
less than           →  <
percent sign        →  %

dollar sign         →  $
euro sign           →  €
cent sign           →  ¢
yen sign            →  ¥
```

#### Typographic Symbols
```
bullet point        →  •
en dash             →  –
```

#### Arrows
```
right arrow         →  →
left arrow          →  ←
```

<div class="page-break"></div>

---

## Number Words & Ordinals

### Spoken Numbers

Say numbers in words and they'll be converted to digits.

#### Basic Numbers (0–19)

```
zero    →  0         ten      →  10
one     →  1         eleven   →  11
two     →  2         twelve   →  12
three   →  3         thirteen →  13
four    →  4         fourteen →  14
five    →  5         fifteen  →  15
six     →  6         sixteen  →  16
seven   →  7         seventeen→  17
eight   →  8         eighteen →  18
nine    →  9         nineteen →  19
```

#### Tens (20–90)

```
twenty   →  20       sixty   →  60
thirty   →  30       seventy →  70
forty    →  40       eighty  →  80
fifty    →  50       ninety  →  90
```

#### Large Numbers

```
hundred     →  100
thousand    →  1,000
million     →  1,000,000
billion     →  1,000,000,000
trillion    →  1,000,000,000,000
```

#### Compound Numbers

Say multiple number words together to form larger numbers:

| Spoken | Result |
|--------|--------|
| "one hundred twenty three" | 123 |
| "five thousand four hundred fifty six" | 5,456 |
| "two million" | 2,000,000 |
| "a hundred" | 100 |

#### Ordinal Numbers

Ordinal numbers convert to numbered positions (1st, 2nd, 3rd, etc.):

```
first        →  1st          eleventh  →  11th        sixtieth →  60th
second       →  2nd          twelfth   →  12th        seventieth→ 70th
third        →  3rd          thirteenth→  13th        eightieth→ 80th
fourth       →  4th          fourteenth→  14th        ninetieth→ 90th
fifth        →  5th          fifteenth →  15th        hundredth→ 100th
sixth        →  6th          sixteenth →  16th        thousandth→ 1000th
seventh      →  7th          seventeenth→ 17th
eighth       →  8th          eighteenth→  18th
ninth        →  9th          nineteenth→  19th
tenth        →  10th         twentieth →  20th        thirtieth→  30th
                             fortieth  →  40th        fiftieth →  50th
```

#### Currency

Currency amounts are recognized and formatted:

```
five dollars        →  $5
ten dollars         →  $10
one dollar fifty    →  $1.50
five euros          →  €5
ten pounds          →  £10
one yen             →  ¥1
twenty five cents   →  $0.25
```

#### Percentages

```
fifty percent       →  50%
one hundred percent →  100%
two point five percent→  2.5%
```

<div class="page-break"></div>

---

## Fractions

### Spoken Fractions

Fractions are converted to Unicode fraction symbols where available:

```
half              →  ½
one half          →  ½
one third         →  ⅓
two thirds        →  ⅔
one quarter       →  ¼
three quarters    →  ¾
one fourth        →  ¼
three fourths     →  ¾
one eighth        →  ⅛
three eighths     →  ⅜
five eighths      →  ⅝
seven eighths     →  ⅞
```

<div class="page-break"></div>

---

## Tips & Tricks

### Efficiency

- **Chain commands**: Combine multiple commands with natural speech
  - Example: `"select all delete"` instead of two separate commands

- **Use alternatives**: All listed alternatives work the same way
  - `"delete that"` = `"erase that"` = `"remove that"`

- **Polite wrappers are optional**: Commands work with or without "please" and "thank you"

### Voice Recognition

- **Clear pronunciation**: Speak clearly for better recognition
- **Pause between words**: Brief pauses help separate commands from text
- **Natural pacing**: Don't rush; speak at normal conversation speed
- **Context matters**: System recognizes context (field type, position)

### Common Mistakes

<div style="background: #fff5f5; border: 1px solid #fecaca; border-radius: 8px; padding: 14px 18px; margin: 12px 0;">

| | Mistake | Fix |
|---|---------|-----|
| ❌ | Saying `"period emoji"` | Use `"period"` for punctuation or `"period emoji"` for emoji |
| ❌ | Mixing number formats | Say `"one hundred"` not `"a hundred one"` |
| ❌ | Forgetting emoji suffix | Say `"fire emoji"` not just `"fire"` |
| ✅ | **Be consistent** | Pick one phrasing style and stick with it |

</div>

---

## Accessibility

Safe Word is designed with accessibility in mind:

<div style="background: #f0f7ff; border: 1px solid #cce0ff; border-radius: 8px; padding: 16px 20px; margin: 12px 0;">

| | Feature | Description |
|---|---------|-------------|
| 👋 | **Voice-only input** | Fully hands-free operation |
| 👁️ | **No visual requirements** | Works without looking at screen |
| ⚙️ | **Customizable commands** | Create commands for your workflow |
| 📱 | **On-device processing** | All voice data processed locally—no transmission |
| 🔒 | **Privacy** | No recordings sent to external servers |

</div>

---

<div style="text-align: center; padding: 32px 16px 8px; color: #5a6690; font-size: 12px;">
  <p style="margin: 0 0 4px;"><strong style="color: #8892b0;">Safe Word Android</strong> — <em>Voice to text, the right way.</em></p>
  <p style="margin: 0 0 12px; font-size: 11px;">All commands are case-insensitive and work with or without polite wrappers.<br/>This guide reflects the current voice command vocabulary and emoji library.</p>
  <img src="jurgistan.png" alt="Made in Jurgistan" style="height: 28px; opacity: 0.5;" />
</div>
