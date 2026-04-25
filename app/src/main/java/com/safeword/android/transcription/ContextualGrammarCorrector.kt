package com.safeword.android.transcription

import java.util.Locale

/**
 * Pure-function contextual grammar and homophone corrections (Layers 2–3).
 *
 * Contains no injected dependencies — all regexes are pre-compiled at object initialisation.
 * This makes every correction unit-testable without Robolectric or an Android context.
 *
 * Called by [ConfusionSetCorrector.apply] after [ContentNormalizer.preProcess].
 */
internal object ContextualGrammarCorrector {

    // ════════════════════════════════════════════════════════════════════
    //  Layer 2 — Moonshine repetitive-token cleanup
    //
    //  Moonshine produces repeated tokens on short audio (<1s). The SDK caps
    //  generation at 6.5 tokens/s, but short segments can still surface
    //  whole-sentence duplicates. This layer runs before ContentNormalizer,
    //  so it targets raw Moonshine output. ContentNormalizer Step 9 catches
    //  word-level triple-repeats later in the pipeline.
    // ════════════════════════════════════════════════════════════════════

    /** Whole-sentence duplication: "Hello. Hello." or "Okay. Okay. Okay."
     *
     * Moonshine produces three repetition modes on short audio:
     *  1. Exact whole-sentence repeats: "Hello. Hello." (captured below).
     *  2. Near-identical repeats with minor punctuation variation (captured by
     *     normalLevenshtein below).
     *  3. Word-level triples ("the the the") — caught by ContentNormalizer TRIPLE_REPEAT.
     */
    private val sentenceRepeatPattern: Regex =
        Regex("""(?i)^([^.!?]+[.!?])\s*(?:\1\s*)+$""")

    // ════════════════════════════════════════════════════════════════════
    //  Layer 3 — Contextual word-level corrections
    //
    //  Pre-compiled regexes targeting specific homophone/grammar errors.
    //  Only patterns with near-zero false-positive rates are included.
    // ════════════════════════════════════════════════════════════════════

    // ── "would/could/should/might/must of" → "have" ─────────────────
    // "of" is never correct after modals; always a mishearing of "'ve".
    private val modalOfPattern = Regex(
        """\b(would|could|should|might|must)\s+of\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "its" → "it's" before function words / verbs ────────────────
    // Possessive "its" precedes a noun directly; these followers are
    // never the start of a noun phrase after "its".
    private val itsFollowers = listOf(
        "a", "an", "the", "not", "just", "like", "been",
        "so", "too", "very", "really", "about", "always", "never", "only",
        "actually", "basically", "probably", "definitely", "clearly",
        "obviously", "apparently", "because",
        "going", "getting", "coming", "raining", "snowing",
        "here", "there", "over", "gonna", "gotta",
    ).joinToString("|")

    private val itsPattern = Regex(
        """\bits\s+($itsFollowers)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "your" → "you're" before verbs / adverbs ────────────────────
    // Only followers that never serve as adjective-before-noun in
    // "your ___ [noun]" constructs, keeping the false-positive rate ≈ 0.
    private val yourFollowers = listOf(
        "welcome",
        "not", "so", "very", "really", "too", "quite",
        "being", "doing", "going", "getting", "making", "having",
        "telling", "saying", "looking", "talking", "coming", "running",
        "working", "trying", "leaving", "staying", "sitting", "standing",
        "walking", "waiting", "kidding", "joking", "lying",
        "gonna", "gotta",
    ).joinToString("|")

    private val yourMultiWordPattern = Regex(
        """\byour\s+(the\s+best|the\s+worst|the\s+one|a\s+genius|an\s+idiot)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val yourSinglePattern = Regex(
        """\byour\s+($yourFollowers)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "whose" → "who's" before articles / demonstratives ──────────
    private val whoseFollowers = listOf(
        "the", "a", "an", "this", "that", "there", "here",
        "going", "coming", "calling", "responsible", "next",
        "not", "been", "got",
    ).joinToString("|")

    private val whosePattern = Regex(
        """\bwhose\s+($whoseFollowers)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── Comparative + "then" → "than" ───────────────────────────────
    private val comparatives = listOf(
        "better", "worse", "more", "less", "greater", "higher", "lower",
        "bigger", "smaller", "faster", "slower", "older", "younger",
        "longer", "shorter", "wider", "taller", "stronger", "weaker",
        "easier", "harder", "simpler", "cheaper", "richer", "poorer",
        "closer", "further", "earlier", "later", "rather", "other",
        "smarter", "dumber", "quieter", "louder", "brighter", "darker",
        "thinner", "thicker", "deeper", "lighter", "heavier",
    ).joinToString("|")

    private val comparativeThenPattern = Regex(
        """\b($comparatives)\s+then\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "apart of" → "a part of" ───────────────────────────────────
    private val apartOfPattern = Regex(
        """\bapart\s+of\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "chop/drop centre/center" → "job centre/center" ────────────
    // Moonshine confuses /dʒɒb/ with /tʃɒp/ and /drɒp/ on short
    // utterances. "chop centre" and "drop centre" are extremely rare
    // real phrases compared to "job centre" in dictation contexts.
    private val jobCentrePattern = Regex(
        """\b(?:chop|drop)\s+(centre|center)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── Extended modal "of" → "have" ─────────────────────────────────────
    private val extendedModalOfPattern = Regex(
        """\b(going to|ought to|supposed to|used to|have to)\s+of\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "loose" in verb contexts → "lose" ────────────────────────────────
    private val looseLosePattern = Regex(
        """\b(don't|can't|will|won't|didn't|shouldn't|couldn't|wouldn't|not)\s+loose\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "walk/drive/run passed" → "past" ─────────────────────────────────
    private val passedPastPattern = Regex(
        """\b(walk|drive|run|go|went|come|came|fly|flew|march|drove)\s+passed\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "was/were/has lead by" → "led by" ────────────────────────────────
    private val ledLeadPattern = Regex(
        """\b(was|were|has|have|had|being)\s+lead\s+by\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "brake up/down/free/away" → "break" ──────────────────────────────
    private val brakeBreakPattern = Regex(
        """\bbrake\s+(up|down|free|away|off|in|out|through|into|apart)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── Number collocations → digit form ─────────────────────────────────
    private val twentyFourSevenPattern = Regex("""\btwenty[- ]four[- ]seven\b""", RegexOption.IGNORE_CASE)
    private val nineOneOnePattern = Regex("""\bnine[- ]one[- ]one\b""", RegexOption.IGNORE_CASE)
    private val doubleOhSevenPattern = Regex("""\bdouble[- ]oh[- ]seven\b""", RegexOption.IGNORE_CASE)

    // ── Category B — common homophone pairs ──────────────────────────────
    // "free reign" → "free rein" (horsemanship idiom is always "free rein")
    private val freeReignPattern = Regex("""\bfree\s+reign\b""", RegexOption.IGNORE_CASE)

    // "nerves of steal" → "nerves of steel"
    private val nervesOfStealPattern = Regex("""\bnerves\s+of\s+steal\b""", RegexOption.IGNORE_CASE)

    // "dire straights" → "dire straits"
    private val direStraitPattern = Regex("""\bdire\s+straights\b""", RegexOption.IGNORE_CASE)

    // "a peace of" → "a piece of" ("peace" is never used partitively)
    private val peaceOfPattern = Regex("""\ba\s+peace\s+of\b""", RegexOption.IGNORE_CASE)

    // "body and sole" → "body and soul"
    private val bodyAndSolePattern = Regex("""\bbody\s+and\s+sole\b""", RegexOption.IGNORE_CASE)

    // "belly naval" → "belly navel"
    private val bellyNavalPattern = Regex("""\bbelly\s+naval\b""", RegexOption.IGNORE_CASE)

    // "in vane" → "in vain" (weather-vane is never "in vane")
    private val inVanePattern = Regex("""\bin\s+vane\b""", RegexOption.IGNORE_CASE)

    // "nice/good/glad to meat you" → "…to meet you" (verb context is unambiguous)
    private val meatMeetPattern = Regex(
        """\b(nice|good|happy|glad|pleased|great|lovely)\s+to\s+meat\b""",
        RegexOption.IGNORE_CASE,
    )

    // "makes no/any/some/much/little cents" → "…sense" (cents only follows numbers)
    private val makesNoCentsPattern = Regex(
        """\b(makes?|making|made)\s+(no|any|some|much|little|perfect)\s+cents\b""",
        RegexOption.IGNORE_CASE,
    )

    // "plane to see/understand/tell/spot" → "plain to …"
    private val plainToSeePattern = Regex(
        """\bplane\s+to\s+(see|understand|observe|tell|spot|read|notice)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "the hole [team|world|day|story|…]" → "the whole …"
    private val wholeMisspelledPattern = Regex(
        """\bthe\s+hole\s+(team|world|day|week|month|year|story|picture|truth|point|idea|thing|concept|situation|place|class|school|country|city|company|family|group|time)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "took/follow/miss/your/my queue" → "…cue" (line-queue is always "queue", not "cue")
    private val queueCuePattern = Regex(
        """\b(took|take|follow(?:ed)?|miss(?:ed)?|that(?:'s)?|your|my|her|his|our|their|on)\s+queue\b""",
        RegexOption.IGNORE_CASE,
    )

    // "lightening struck/bolt/rod/storm/speed/flash" → "lightning …"
    private val lighteningLightningPattern = Regex(
        """\blightening\s+(struck|strikes?|bolts?|rods?|storms?|speed|flashes?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "can/could here you/them/…" → "…hear …" (verb context prevents false positives)
    private val hearHerePattern = Regex(
        """\b(can|could|can't|couldn't)\s+here\s+(you|them|him|her|it|us|me|anything|nothing|the|a|this|that)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "less [countable nouns]" → "fewer …" (uncountable nouns intentionally excluded)
    private val lessFewerPattern = Regex(
        """\bless\s+(items?|people|persons?|words?|cars?|books?|files?|entries|rows?|columns?|emails?|messages?|calls?|meetings?|events?|tasks?|bugs?|errors?|steps?|points?|pages?|photos?|images?|songs?|videos?|articles?|documents?|reports?|requests?|cases?|products?|options?|choices?|candidates?|players?|students?|employees?|customers?|users?|accounts?|features?|changes?|updates?|commits?|tests?|instances?|servers?|devices?|records?|tickets?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "the/a/my principle [issue|reason|cause|…]" → "…principal …"
    private val principalPrinciplePattern = Regex(
        """\b(the|a|their|its|our|my|your|his|her|one)\s+principle\s+(issue|reason|cause|benefit|advantage|concern|source|focus|objective|challenge|obstacle|purpose|driver|factor|barrier)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "farther discussion/notice/investigation/…" → "further …" (farther = physical distance only)
    private val fartherFurtherPattern = Regex(
        """\bfarther\s+(discussion|notice|investigation|thoughts?|consideration|details?|explanations?|analysis|research|reading|action|delay|proof|evidence|comments?|instructions?|questions?|study|examination|review|assistance|help|guidance|clarification|information)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "weather/wether you like/want/decide …" → "whether …" (conditional conjunction)
    private val weatherWhetherPattern = Regex(
        """\b(?:weather|wether)\s+(you|we|they|he|she|I|it)\s+(like|want|agree|prefer|think|believe|know|care|feel|decide|choose|plan|mean|need|have)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "went/came/walked threw the/a …" → "…through the/a …"
    private val threwThroughPattern = Regex(
        """\b(went|come|came|gone|go|walk(?:ed)?|run|ran|fell?|push(?:ed)?|reach(?:ed)?|climb(?:ed)?|slip(?:ped)?|pass(?:ed)?|look(?:ed)?)\s+threw\s+(the|a|an|this|that|those|these)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "flare for" → "flair for" (talent/aptitude sense; distress-flare never uses "for")
    private val flareFlair = Regex("""\bflare\s+for\b""", RegexOption.IGNORE_CASE)

    // "stationary [shop|store|supplies|pad|pens|…]" → "stationery …"
    private val stationaryStationeryPattern = Regex(
        """\bstationary\s+(shop|store|aisle|section|supplies|items|pad|notebooks?|pens?|pencils?|paper)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "effect" (noun) vs "affect" (verb) ──────────────────────────────────
    // "affect" mis-transcribed as "effect" in verb position: "it will effect/effects us"
    // Only fires when sandwiched by a subject-pronoun or modal — near-zero FP rate.
    private val effectAffectPattern = Regex(
        """\b(will|would|could|should|may|might|can|does|did|shall|must|won't|doesn't|didn't|hasn't|to|not)\s+effect(s?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "accept" vs "except" ─────────────────────────────────────────────────
    // "except" rarely follows "please/will/can/should/I/we"; in those slots the
    // word is almost always "accept" (to receive / to agree).
    private val acceptExceptPattern = Regex(
        """\b(please|I|we|you|they|she|he|can|will|would|should|must|need to|have to|want to|going to)\s+except\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "then" → "than" in "more/better/less… then X" (non-comparative context) ──
    // Catches the "more then enough" / "less then ideal" family not covered by
    // the comparative-leading pattern above (where the comparative precedes "then").
    private val moreThenThanPattern = Regex(
        """\b(more|less|rather|no more|no less|nothing more|nothing less|better off|worse off)\s+then\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "aloud" → "allowed" in permission contexts ────────────────────────────
    // "aloud" (audibly) before a modal or negative is always a mistranscription.
    private val aloudAllowedPattern = Regex(
        """\b(?:not|isn't|aren't|wasn't|weren't|you're)\s+aloud\s+to\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "coarse" → "course" ──────────────────────────────────────────────────
    // "of coarse" is never correct English — always a mishearing of "of course".
    private val ofCoarsePattern = Regex("""\bof\s+coarse\b""", RegexOption.IGNORE_CASE)

    // ── "manor" → "manner" in behavioural contexts ────────────────────────────
    // "in a/the [adjective] manor" where the adjective is behavioural, not locational
    private val manorMannerPattern = Regex(
        """\bin\s+(?:a|the|this|that|such|what|an|every|no)\s+(professional|appropriate|polite|rude|abrupt|respectful|dignified|orderly|disorderly|timely|responsible|irresponsible|strange|odd|unusual|formal|informal|casual|efficient|effective|consistent|proper)\s+manor\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "discreet" → "discrete" (separate/distinct) ──────────────────────────
    // "discrete" precedes measurement/math nouns; "discreet" precedes behaviour adj.
    private val discreteDiscreetPattern = Regex(
        """\bdiscreet\s+(units?|values?|steps?|events?|components?|variables?|data\s+points?|categories|elements?|sets?|items?|groups?|intervals?|segments?|packets?|signals?|measurements?|samples?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "phase" → "faze" (to disturb) ────────────────────────────────────────
    // "didn't/doesn't/won't phase me/him/her/us/them" → "faze"
    private val phasesFazePattern = Regex(
        """\b(didn't|doesn't|won't|can't|couldn't|wouldn't|shouldn't|doesn't\s+even)\s+phase\s+(me|him|her|us|you|them|anyone|anybody)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "reign" → "rein" in expressions of control ───────────────────────────
    // (This catches the broader "give free reign" / "take the reigns" family,
    // not just "free reign" which is already handled above.)
    private val reignReinPattern = Regex(
        """\b(take\s+the|hold\s+the|keep\s+a\s+tight|tighten\s+the|loosen\s+the|give\s+free)\s+reigns?\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "wander" → "wonder" in cognitive contexts ────────────────────────────
    // "I wander if/why/how/what" → "I wonder …" (musing, not wandering)
    private val wanderWonderPattern = Regex(
        """\b(I|we|you|they|he|she|do\s+you|does\s+anyone|did\s+you)\s+wander\s+(if|why|how|what|whether|when|where|who)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "mute" → "moot" (point) ──────────────────────────────────────────────
    private val muteMootPattern = Regex("""\bmute\s+point\b""", RegexOption.IGNORE_CASE)

    // ── "hoard" → "horde" (large group) ─────────────────────────────────────
    private val hoardHordePattern = Regex(
        """\ba?\s*hoard\s+of\s+(people|users|fans|zombies|enemies|soldiers|attackers|players|tourists|customers|bugs)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "boarder" → "border" (boundary) when clearly geographic ─────────────
    private val boarderBorderPattern = Regex(
        """\b(national|state|country|international|southern|northern|eastern|western|US|UK|EU|county)\s+boarder\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── "diffuse" → "defuse" (disarm/calm) ───────────────────────────────────
    private val diffuseDefusePattern = Regex(
        """\b(?:diffuse|to\s+diffuse)\s+(the\s+(?:tension|situation|conflict|bomb|crisis|argument|confrontation|threat))\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── UK English spelling normalisations ─────────────────────────────────
    // Each entry is (pattern, americanSpelling, britishSpelling). The letter-case
    // of the first character is preserved when applying replacements.
    // Applied to all major Commonwealth locales, not only en-GB (C4).
    private val ukSpellingTable: List<Triple<Regex, String, String>> = listOf(
        Triple(Regex("""\brealize\b""", RegexOption.IGNORE_CASE), "realize", "realise"),
        Triple(Regex("""\borganize\b""", RegexOption.IGNORE_CASE), "organize", "organise"),
        Triple(Regex("""\bcenter\b""", RegexOption.IGNORE_CASE), "center", "centre"),
        Triple(Regex("""\bcolor\b""", RegexOption.IGNORE_CASE), "color", "colour"),
        Triple(Regex("""\bgray\b""", RegexOption.IGNORE_CASE), "gray", "grey"),
    )

    /** ISO 3166-1 alpha-2 country codes that use British English spelling conventions. */
    private val britishSpellingCountries: Set<String> = setOf(
        "GB", "AU", "NZ", "IE", "ZA", "IN", "HK", "SG",
    )

    // ── "there/their" → "they're" before verb-ing / adverb ──────────
    // Applied via lambda to check the preceding word and skip correction
    // in locational/existential constructs ("over there going …").

    private val thereVerbingSet: Set<String> = setOf(
        "going", "doing", "coming", "making", "taking", "having",
        "getting", "looking", "trying", "playing", "saying", "thinking",
        "working", "moving", "leaving", "staying", "living", "running",
        "walking", "talking", "eating", "sitting", "standing", "waiting",
        "watching", "reading", "writing", "sleeping", "driving", "flying",
        "singing", "dancing", "fighting", "winning", "losing", "paying",
        "buying", "selling", "calling", "asking", "telling", "showing",
        "helping", "meeting", "starting", "stopping", "building",
        "opening", "closing", "turning", "pulling", "pushing",
        "holding", "carrying", "bringing", "throwing", "catching",
        "falling", "growing", "changing", "learning", "teaching",
        "feeling", "planning", "preparing", "kidding", "lying",
    )

    private val thereAdverbSet: Set<String> = setOf(
        "not", "all", "both", "just", "only", "also", "still",
        "already", "always", "never", "really", "very", "so",
        "too", "quite", "pretty", "actually", "probably",
        "definitely", "certainly", "basically", "honestly",
        "literally", "simply", "finally", "obviously",
        "gonna", "gotta",
    )

    private val thereAllFollowers =
        (thereVerbingSet + thereAdverbSet).joinToString("|")

    private val thereTheyRePattern = Regex(
        """\b(?:there|their)\s+($thereAllFollowers)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Words before "there/their" that indicate location/existential usage. */
    private val locationPreceders: Set<String> = setOf(
        "over", "in", "from", "around", "near", "out", "up", "down",
        "right", "back", "go", "went", "get", "got", "been", "was",
        "is", "are", "were", "be", "put", "left", "moved", "sat",
        "stood", "stay", "stayed", "live", "lived",
    )

    // ── O2: Layer 3 pre-scan trigger words ──────────────────────────────
    // If none of these words appear (case-insensitive) in the lowercased
    // input, no Layer 3 regex can match — skip the entire block.
    // Built from the distinctive trigger tokens of every Layer 3 pattern.
    private val layer3TriggerWords: Set<String> = setOf(
        // Modal "of" → "have"
        "of",
        // its/your/whose patterns
        "its", "your", "whose",
        // Comparative "then" → "than"
        "then",
        // apart of
        "apart",
        // chop/drop centre/center
        "chop", "drop",
        // Extended modal of
        "going", "ought", "supposed", "used",
        // loose → lose
        "loose",
        // passed → past
        "passed",
        // lead → led
        "lead",
        // brake → break
        "brake",
        // Number collocations
        "twenty", "nine", "double",
        // there/their → they're
        "there", "their",
        // Category B homophones
        "reign", "steal", "straights", "peace", "sole", "naval",
        "vane", "meat", "cents", "plane", "hole", "queue",
        "lightening", "here", "less", "principle", "farther",
        "weather", "wether", "threw", "flare", "stationary",
        // UK spelling
        "realize", "organize", "center", "color", "gray",
        // New homophone patterns
        "effect", "except", "aloud", "coarse", "manor", "pore",
        "phase", "reigns", "wander", "mute", "hoard", "boarder",
        "diffuse", "discreet", "more",
        // Missing triggers added:
        // hear/here pattern requires "can/could" as auxiliaries
        "can", "could",
        // makesNoCentsPattern trigger
        "makes", "making", "made",
    )

    // ════════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Apply Layers 2–3 corrections to [text].
     *
     * @param text The pre-cleaned text from [ContentNormalizer.preProcess].
     * @param locale The current locale, used for UK spelling normalisation.
     * @param isIncremental True for mid-stream partial results; Layer 3 is
     *   skipped unless the utterance already has ≥ 4 words.
     * @return Corrected text (same reference if no changes were needed).
     */
    fun correct(text: String, locale: Locale, isIncremental: Boolean): String {
        var result = text

        // Layer 2: collapse Moonshine repetitive-token artifacts.
        sentenceRepeatPattern.find(result)?.let { match ->
            result = match.groupValues[1]
        }

        // Layer 3: contextual word-level corrections.
        // Skip on incremental calls with fewer than 4 words to avoid mid-sentence false positives.
        val wordCount = result.split(WHITESPACE_SPLIT_REGEX).count { it.isNotBlank() }
        if (!isIncremental || wordCount >= 4) {
            // O2: Pre-scan fast-reject — if none of the trigger words appear,
            // skip the entire 40+ regex block (common path for clean text).
            val lowerWords = result.lowercase().split(WHITESPACE_SPLIT_REGEX).toSet()
            val hasAnyTrigger = lowerWords.any { it in layer3TriggerWords }
            if (hasAnyTrigger) {
                result = modalOfPattern.replace(result, "$1 have")
                result = itsPattern.replace(result, "it's $1")
                result = yourMultiWordPattern.replace(result, "you're $1")
                result = yourSinglePattern.replace(result, "you're $1")
                result = whosePattern.replace(result, "who's $1")
                result = comparativeThenPattern.replace(result, "$1 than")
                result = apartOfPattern.replace(result, "a part of")
                result = jobCentrePattern.replace(result) { "job ${it.groupValues[1]}" }
                result = applyThereCorrections(result)
                result = extendedModalOfPattern.replace(result, "$1 have")
                result = looseLosePattern.replace(result) { "${it.groupValues[1]} lose" }
                result = passedPastPattern.replace(result) { "${it.groupValues[1]} past" }
                result = ledLeadPattern.replace(result) { "${it.groupValues[1]} led by" }
                result = brakeBreakPattern.replace(result) { "break ${it.groupValues[1]}" }
                result = twentyFourSevenPattern.replace(result, "24/7")
                result = nineOneOnePattern.replace(result, "911")
                result = doubleOhSevenPattern.replace(result, "007")
                // Category B homophone corrections
                result = freeReignPattern.replace(result, "free rein")
                result = nervesOfStealPattern.replace(result, "nerves of steel")
                result = direStraitPattern.replace(result, "dire straits")
                result = peaceOfPattern.replace(result, "a piece of")
                result = bodyAndSolePattern.replace(result, "body and soul")
                result = bellyNavalPattern.replace(result, "belly navel")
                result = inVanePattern.replace(result, "in vain")
                result = meatMeetPattern.replace(result, "$1 to meet")
                result = makesNoCentsPattern.replace(result, "$1 $2 sense")
                result = plainToSeePattern.replace(result, "plain to $1")
                result = wholeMisspelledPattern.replace(result, "the whole $1")
                result = queueCuePattern.replace(result, "$1 cue")
                result = lighteningLightningPattern.replace(result, "lightning $1")
                result = hearHerePattern.replace(result, "$1 hear $2")
                result = lessFewerPattern.replace(result, "fewer $1")
                result = principalPrinciplePattern.replace(result, "$1 principal $2")
                result = fartherFurtherPattern.replace(result, "further $1")
                result = weatherWhetherPattern.replace(result, "whether $1 $2")
                result = threwThroughPattern.replace(result, "$1 through $2")
                result = flareFlair.replace(result, "flair for")
                result = stationaryStationeryPattern.replace(result, "stationery $1")
                // Category C — new high-value homophone corrections
                result = effectAffectPattern.replace(result) { m ->
                    "${m.groupValues[1]} affect${m.groupValues[2]}"
                }
                result = acceptExceptPattern.replace(result, "$1 accept")
                result = moreThenThanPattern.replace(result, "$1 than")
                result = aloudAllowedPattern.replace(result) { m -> m.value.replace("aloud", "allowed", ignoreCase = true) }
                result = ofCoarsePattern.replace(result, "of course")
                result = manorMannerPattern.replace(result) { m ->
                    m.value.replace("manor", "manner", ignoreCase = true)
                }
                result = phasesFazePattern.replace(result) { m ->
                    "${m.groupValues[1]} faze ${m.groupValues[2]}"
                }
                result = reignReinPattern.replace(result) { m ->
                    m.value.replace(Regex("reigns?", RegexOption.IGNORE_CASE), "reins")
                }
                result = wanderWonderPattern.replace(result) { m ->
                    "${m.groupValues[1]} wonder ${m.groupValues[2]}"
                }
                result = muteMootPattern.replace(result, "moot point")
                result = hoardHordePattern.replace(result) { m ->
                    m.value.replace("hoard", "horde", ignoreCase = true)
                }
                result = boarderBorderPattern.replace(result) { m ->
                    m.value.replace("boarder", "border", ignoreCase = true)
                }
                result = diffuseDefusePattern.replace(result) { m ->
                    m.value.replaceFirst("diffuse", "defuse", ignoreCase = true)
                }
                result = discreteDiscreetPattern.replace(result) { m ->
                    m.value.replace("discreet", "discrete", ignoreCase = true)
                }
                // UK locale spellings (C4: expanded to all Commonwealth locales)
                if (locale.language == "en" && locale.country in britishSpellingCountries) {
                    for ((pattern, _, british) in ukSpellingTable) {
                        result = pattern.replace(result) { m ->
                            if (m.value[0].isUpperCase()) british.replaceFirstChar { it.uppercaseChar() } else british
                        }
                    }
                }
            } // end hasAnyTrigger
        }

        return result
    }

    // ════════════════════════════════════════════════════════════════════
    //  Internal helper
    // ════════════════════════════════════════════════════════════════════

    /** Replace "there/their" → "they're" only when not preceded by a location word. */
    private fun applyThereCorrections(text: String): String =
        thereTheyRePattern.replace(text) { match ->
            val preceding = text.substring(0, match.range.first).trimEnd()
            val prevWord = preceding.split(WHITESPACE_SPLIT_REGEX).lastOrNull()
                ?.lowercase()
                ?.trimEnd(',', '.', '!', '?', ';', ':')
                ?: ""
            if (prevWord in locationPreceders) {
                match.value
            } else {
                "they're ${match.groupValues[1]}"
            }
        }
}
