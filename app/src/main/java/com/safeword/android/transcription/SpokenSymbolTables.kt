package com.safeword.android.transcription

/**
 * Canonical spoken-name → inserted-text mapping for every punctuation and
 * symbol shortcut recognised by the voice command pipeline.
 *
 * Consumed by [VoiceCommandDetector] to populate its COMMAND_MAP. Keeping
 * the table here lets the list grow without touching detector logic.
 *
 * Convention:
 *  - Period and "dot" carry a trailing space (". ") so the cursor lands ready
 *    for the next word after sentence-end insertion.
 *  - All other marks (comma, !, ?, :, ; …) do NOT carry a trailing space;
 *    the target editor handles spacing after insertion.
 *  - "dash" → em dash (" — "); "hyphen" and "minus" → "-".
 *  - All keys are already lowercase; [VoiceCommandDetector] lowercases input
 *    before lookup.
 */
internal object SpokenPunctuationTable {

    /** Spoken phrase → text to insert verbatim. */
    val entries: Map<String, String> = buildMap {

        // ── Sentence-ending & pause marks ─────────────────────────────────────
        put("period",                ". ")
        put("full stop",             ". ")
        put("dot",                   ". ")

        put("comma",                 ",")
        put("add a comma",           ",")
        put("exclamation point",     "!")
        put("exclamation mark",      "!")
        put("question mark",         "?")

        put("colon",                 ":")
        put("semicolon",             ";")
        put("ellipsis",              "…")
        put("dot dot dot",           "…")

        // ── Paired delimiters ─────────────────────────────────────────────────
        put("open parenthesis",      "(")
        put("close parenthesis",     ")")
        put("open paren",            "(")
        put("close paren",           ")")

        put("open bracket",          "[")
        put("close bracket",         "]")

        put("open brace",            "{")
        put("close brace",           "}")

        put("less than",             "<")
        put("greater than",          ">")

        // ── Quotes ────────────────────────────────────────────────────────────
        put("quote",                 "\"")
        put("open quote",            "\"")
        put("close quote",           "\"")

        put("apostrophe",            "'")
        put("single quote",          "'")

        // ── Dashes, spacing, and connectors ───────────────────────────────────
        put("hyphen",                "-")
        put("dash",                  " — ")
        put("minus",                 "-")
        put("em dash",               " — ")

        put("underscore",            "_")

        put("space",                 " ")
        put("tab",                   "\t")

        // ── Mathematical / programming symbols ────────────────────────────────
        put("plus",                  "+")
        put("equals",                "=")
        put("tilde",                 "~")
        put("asterisk",              "*")
        put("slash",                 "/")
        put("forward slash",         "/")
        put("backslash",             "\\")
        put("pipe",                  "|")
        put("caret",                 "^")
        put("percent",               "%")

        // ── Common shorthand symbols ──────────────────────────────────────────
        put("ampersand",             "&")
        put("at sign",               "@")
        put("hashtag",               "#")
        put("hash",                  "#")
        put("dollar sign",           "$")
        put("dollar",                "$")
        put("euro",                  "€")
        put("euro sign",             "€")
        put("pound sterling",        "£")

        // ── Extended symbols ──────────────────────────────────────────────────
        put("bullet point",          "\u2022")  // •
        put("en dash",               "\u2013")  // –
        put("degree sign",           "\u00B0")  // °
        put("copyright sign",        "\u00A9")  // ©
        put("trademark",             "\u2122")  // ™
        put("registered trademark",  "\u00AE")  // ®
        put("registered sign",       "\u00AE")  // ®
        put("cent sign",             "\u00A2")  // ¢
        put("yen sign",              "\u00A5")  // ¥
        put("right arrow",           "\u2192")  // →
        put("left arrow",            "\u2190")  // ←
        put("equals sign",           "=")

        // ── Aliases (long-form variants used in natural speech) ───────────────
        put("pound sign",            "#")
        put("plus sign",             "+")
        put("minus sign",            "-")
        put("equal sign",            "=")
        put("percent sign",          "%")
        put("open single quote",     "'")
        put("close single quote",    "'")

        // ── Backtick / grave accent ───────────────────────────────────────────
        put("backtick",              "`")
        put("grave",                 "`")
        put("back quote",            "`")

        // ── Typography / named symbols ────────────────────────────────────────
        put("octothorpe",            "#")
        put("pilcrow",               "\u00B6")  // ¶
        put("section sign",          "\u00A7")  // §
        put("pounds sterling",       "\u00A3")  // £ (plural alias)

        // ── Math symbols ──────────────────────────────────────────────────────
        put("multiplication sign",   "\u00D7")  // ×
        put("division sign",         "\u00F7")  // ÷
        put("plus or minus",         "\u00B1")  // ±
        put("not equal to",          "\u2260")  // ≠
    }
}

/**
 * Canonical spoken-name → emoji character mapping consumed by [VoiceCommandDetector].
 *
 * Convention:
 *  - Primary keys use the "[name] emoji" form (mirrors Apple/Google dictation).
 *    A trailing space is NOT added — the speaker controls surrounding text.
 *  - Unambiguous shorthand aliases (e.g. "lol", "omg", "lmao") are also registered
 *    so they fire on a standalone utterance without the "emoji" suffix.
 *  - All keys are lowercase; [VoiceCommandDetector] lowercases input before lookup.
 *  - Emoji that appear in multiple logical groups are listed only once to avoid
 *    silent overwrite (buildMap does not deduplicate → compile-time NOT enforced,
 *    so duplicates would silently keep the last entry).
 */
internal object SpokenEmojiTable {

    val entries: Map<String, String> = buildMap {

        // ── Smileys ──────────────────────────────────────────────────────────
        put("smiley emoji",              "😊")
        put("smiley face emoji",          "😊")  // full phrase alias
        put("smile emoji",               "😊")
        put("happy emoji",               "😊")
        put("grin emoji",                "😁")
        put("laugh emoji",               "😂")
        put("crying laughing emoji",     "😂")
        put("tears of joy emoji",        "😂")
        put("rofl emoji",                "🤣")
        put("wink emoji",                "😉")
        put("heart eyes emoji",          "😍")
        put("kiss emoji",                "😘")
        put("blush emoji",               "😊")
        put("tongue out emoji",          "😜")
        put("cool emoji",               "😎")
        put("sunglasses emoji",          "😎")
        put("thinking emoji",            "🤔")
        put("eye roll emoji",            "🙄")
        put("sad emoji",                 "😢")
        put("crying emoji",              "😭")
        put("angry emoji",               "😠")
        put("shocked emoji",             "😱")
        put("scream emoji",              "😱")
        put("skull emoji",               "💀")
        put("clown emoji",               "🤡")
        put("nerd emoji",                "🤓")
        put("poop emoji",                "💩")
        put("sleeping face emoji",       "😴")
        put("angel face emoji",          "😇")
        put("devil face emoji",          "😈")
        put("alien emoji",               "👽")

        // ── Hands / gestures ─────────────────────────────────────────────────
        put("thumbs up emoji",           "👍")
        put("thumbs emoji",              "👍")  // alias for "thumbs up emoji"
        put("thumbs down emoji",         "👎")
        put("clap emoji",                "👏")
        put("wave emoji",                "👋")
        put("high five emoji",           "🙌")
        put("pray emoji",                "🙏")
        put("handshake emoji",           "🤝")
        put("fist bump emoji",           "🤜")
        put("middle finger emoji",       "🖕")
        put("ok emoji",                  "👌")
        put("peace emoji",               "✌️")
        put("muscle emoji",              "💪")
        put("point up emoji",            "☝️")

        // ── Hearts ───────────────────────────────────────────────────────────
        put("heart emoji",               "❤️")
        put("red heart emoji",           "❤️")
        put("broken heart emoji",        "💔")
        put("fire emoji",                "🔥")
        put("sparkle emoji",             "✨")

        // ── Celebrations ─────────────────────────────────────────────────────
        put("party emoji",               "🎉")
        put("confetti emoji",            "🎊")
        put("trophy emoji",              "🏆")
        put("star emoji",                "⭐")
        put("hundred emoji",             "💯")

        // ── Common reactions ─────────────────────────────────────────────────
        put("check emoji",               "✅")
        put("checkmark emoji",           "✅")
        put("x emoji",                   "❌")
        put("warning emoji",             "⚠️")
        put("question mark emoji",       "❓")
        put("exclamation emoji",         "❗")
        put("eyes emoji",                "👀")
        put("shrug emoji",               "🤷")
        put("facepalm emoji",            "🤦")

        // ── Everyday objects ─────────────────────────────────────────────────
        put("sun emoji",                 "☀️")
        put("rainbow emoji",             "🌈")
        put("coffee emoji",              "☕")
        put("pizza emoji",               "🍕")
        put("hamburger emoji",           "🍔")
        put("cake emoji",                "🎂")
        put("cake slice emoji",          "🍰")
        put("beer emoji",                "🍺")
        put("dog emoji",                 "🐶")
        put("cat emoji",                 "🐱")
        put("mouse emoji",               "🐭")
        put("lion emoji",                "🦁")
        put("shark emoji",               "🦈")
        put("robot emoji",               "🤖")
        put("rocket emoji",              "🚀")
        put("money emoji",               "💰")
        put("ghost emoji",               "👻")
        put("brain emoji",               "🧠")
        put("gem emoji",                 "💎")
        put("boom emoji",                "💥")
        put("rose emoji",                "🌹")
        put("four leaf clover emoji",    "🍀")
        put("computer mouse emoji",      "🖱️")

        // ── Internet shorthand ───────────────────────────────────────────────
        put("lol",                       "😂")
        put("lmao",                      "🤣")
        put("omg",                       "😱")
        put("smh",                       "🤦")
        put("gg",                        "🏆")
        put("rip",                       "🪦")
        put("fyi",                       "💡")
        put("ngl",                       "😬")
    }
}
