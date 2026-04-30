package com.safeword.android.transcription

import org.junit.Test
import kotlin.test.assertEquals

class ContentNormalizerTest {

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `empty string returns empty`() {
        assertEquals("", ContentNormalizer.normalize(""))
    }

    @Test
    fun `whitespace-only returns empty`() {
        assertEquals("", ContentNormalizer.normalize("   "))
    }

    // -------------------------------------------------------------------------
    // Invisible character stripping
    // -------------------------------------------------------------------------

    @Test
    fun `strips ZWSP and BOM characters`() {
        val input = "hello\u200Bworld\uFEFF"
        val result = ContentNormalizer.normalize(input)
        assertEquals("helloworld", result)
    }

    // -------------------------------------------------------------------------
    // Spoken emoji conversion — requires "emoji" suffix
    // -------------------------------------------------------------------------

    @Test
    fun `spoken emoji with suffix is converted`() {
        assertEquals("\uD83D\uDE0A", ContentNormalizer.normalize("smiley face emoji"))
    }

    @Test
    fun `spoken emoji without suffix is NOT converted`() {
        val result = ContentNormalizer.normalize("smiley face")
        assertEquals("smiley face", result)
    }

    @Test
    fun `spoken emoji case insensitive`() {
        assertEquals("\uD83D\uDC4D", ContentNormalizer.normalize("Thumbs Up Emoji"))
    }

    @Test
    fun `coffee without emoji suffix stays as text`() {
        val result = ContentNormalizer.normalize("I like coffee")
        assertEquals("I like coffee", result)
    }

    @Test
    fun `coffee emoji suffix converts to emoji`() {
        val result = ContentNormalizer.normalize("coffee emoji")
        assertEquals("\u2615", result)
    }

    @Test
    fun `cat without emoji suffix stays as text`() {
        val result = ContentNormalizer.normalize("my cat is cute")
        assertEquals("my cat is cute", result)
    }

    @Test
    fun `hundred without emoji suffix stays as text`() {
        // "hundred" is also a multiplier word — it should not become 💯 without suffix
        val result = ContentNormalizer.normalize("one hundred")
        assertEquals("100", result) // ITN converts it to a number instead
    }

    @Test
    fun `emoji in middle of sentence`() {
        val result = ContentNormalizer.normalize("that is fire emoji right there")
        assertEquals("that is \uD83D\uDD25 right there", result)
    }

    @Test
    fun `multiple emojis in same sentence`() {
        val result = ContentNormalizer.normalize("thumbs up emoji and heart emoji")
        assertEquals("\uD83D\uDC4D and \u2764\uFE0F", result)
    }

    // -------------------------------------------------------------------------
    // Spoken punctuation
    // -------------------------------------------------------------------------

    @Test
    fun `spoken period is converted`() {
        val result = ContentNormalizer.normalize("hello period")
        assertEquals("hello.", result)
    }

    @Test
    fun `spoken comma is converted`() {
        val result = ContentNormalizer.normalize("hello comma world")
        assertEquals("hello, world", result)
    }

    @Test
    fun `spoken question mark is converted`() {
        val result = ContentNormalizer.normalize("are you there question mark")
        assertEquals("are you there?", result)
    }

    @Test
    fun `spoken exclamation is converted`() {
        val result = ContentNormalizer.normalize("wow exclamation point")
        assertEquals("wow!", result)
    }

    @Test
    fun `spoken new line is converted`() {
        val result = ContentNormalizer.normalize("hello new line world")
        assertEquals("hello\nworld", result)
    }

    @Test
    fun `spoken new paragraph is converted`() {
        val result = ContentNormalizer.normalize("end new paragraph begin")
        assertEquals("end\n\nbegin", result)
    }

    @Test
    fun `spoken open and close brace`() {
        val result = ContentNormalizer.normalize("open brace key close brace")
        assertEquals("{key}", result)
    }

    @Test
    fun `spoken single quotes`() {
        val result = ContentNormalizer.normalize("open single quote hello close single quote")
        assertEquals("'hello'", result)
    }

    @Test
    fun `spoken apostrophe`() {
        val result = ContentNormalizer.normalize("don apostrophe t")
        assertEquals("don't", result)
    }

    @Test
    fun `spoken underscore and tilde`() {
        val result = ContentNormalizer.normalize("my underscore var")
        assertEquals("my_var", result)
    }

    @Test
    fun `spoken pipe and caret`() {
        val result = ContentNormalizer.normalize("a pipe b caret c")
        assertEquals("a | b ^ c", result)
    }

    @Test
    fun `spoken backslash`() {
        val result = ContentNormalizer.normalize("path backslash file")
        assertEquals("path\\file", result)
    }

    @Test
    fun `spoken math symbols`() {
        val result = ContentNormalizer.normalize("a plus b equal sign c")
        assertEquals("a + b = c", result)
    }

    @Test
    fun `spoken dollar sign and percent sign`() {
        val result = ContentNormalizer.normalize("dollar sign 50 and 20 percent sign")
        assertEquals("\$50 and 20%", result)
    }

    @Test
    fun `spoken em dash is converted`() {
        val result = ContentNormalizer.normalize("this em dash that")
        assertEquals("this — that", result)
    }

    @Test
    fun `spoken pound sign is converted`() {
        val result = ContentNormalizer.normalize("pound sign trending")
        assertEquals("#trending", result)
    }

    @Test
    fun `spoken greater than and less than are converted`() {
        val result = ContentNormalizer.normalize("x greater than y and y less than z")
        assertEquals("x > y and y < z", result)
    }

    @Test
    fun `spoken dot dot dot is converted to ellipsis`() {
        val result = ContentNormalizer.normalize("wait dot dot dot maybe")
        assertEquals("wait… maybe", result)
    }

    // -------------------------------------------------------------------------
    // Filler word removal
    // -------------------------------------------------------------------------

    @Test
    fun `filler uh is removed`() {
        val result = ContentNormalizer.normalize("I uh want that")
        assertEquals("I want that", result)
    }

    @Test
    fun `filler um is removed`() {
        val result = ContentNormalizer.normalize("um hello there")
        assertEquals("hello there", result)
    }

    @Test
    fun `multiple fillers removed`() {
        val result = ContentNormalizer.normalize("so uh like um yeah hmm")
        assertEquals("so like yeah", result)
    }

    @Test
    fun `filler erm is removed`() {
        val result = ContentNormalizer.normalize("I erm need help")
        assertEquals("I need help", result)
    }

    @Test
    fun `filler basically is removed`() {
        val result = ContentNormalizer.normalize("it is basically done")
        assertEquals("it is done", result)
    }

    @Test
    fun `filler literally is removed`() {
        val result = ContentNormalizer.normalize("I literally just arrived")
        assertEquals("I just arrived", result)
    }

    @Test
    fun `multi-word filler you know is removed`() {
        val result = ContentNormalizer.normalize("I was you know going home")
        assertEquals("I was going home", result)
    }

    @Test
    fun `custom filler set can remove like`() {
        val result = ContentNormalizer.normalize(
            text = "I was like going home",
            fillerWords = setOf("like"),
        )
        assertEquals("I was going home", result)
    }

    // -------------------------------------------------------------------------
    // Stutter collapse
    // -------------------------------------------------------------------------

    @Test
    fun `repeated word collapses to single`() {
        val result = ContentNormalizer.normalize("the the answer is")
        assertEquals("the answer is", result)
    }

    @Test
    fun `triple repeat collapses`() {
        val result = ContentNormalizer.normalize("I I I think so")
        assertEquals("I think so", result)
    }

    // -------------------------------------------------------------------------
    // Self-repair resolution
    // -------------------------------------------------------------------------

    @Test
    fun `well actually triggers self-repair`() {
        val result = ContentNormalizer.resolveSelfRepairs("meet at five well actually six o'clock")
        // Reparandum "at five" removed (word-count heuristic matches repair length).
        assertEquals("meet six o'clock", result)
    }

    @Test
    fun `bare actually does NOT trigger self-repair`() {
        // STT guide: "I actually think this is good" must NOT fire.
        val result = ContentNormalizer.resolveSelfRepairs("I actually think this is good")
        assertEquals("I actually think this is good", result)
    }

    @Test
    fun `bare wait does NOT trigger self-repair`() {
        val result = ContentNormalizer.resolveSelfRepairs("wait until tomorrow")
        assertEquals("wait until tomorrow", result)
    }

    @Test
    fun `bare sorry does NOT trigger self-repair`() {
        val result = ContentNormalizer.resolveSelfRepairs("I'm sorry about that")
        assertEquals("I'm sorry about that", result)
    }

    @Test
    fun `scratch that triggers self-repair`() {
        val result = ContentNormalizer.resolveSelfRepairs("go left scratch that go right")
        assertEquals("go right", result)
    }

    @Test
    fun `i mean triggers self-repair`() {
        val result = ContentNormalizer.resolveSelfRepairs("the red i mean the blue one")
        assertEquals("the blue one", result)
    }

    @Test
    fun `no editing term in text leaves text intact`() {
        val result = ContentNormalizer.resolveSelfRepairs("this is a normal sentence")
        assertEquals("this is a normal sentence", result)
    }

    // -------------------------------------------------------------------------
    // Number normalization (ITN)
    // -------------------------------------------------------------------------

    @Test
    fun `single digit word converts`() {
        assertEquals("I have 5 apples", ContentNormalizer.normalizeNumbers("I have five apples"))
    }

    @Test
    fun `teens convert`() {
        assertEquals("she is 13", ContentNormalizer.normalizeNumbers("she is thirteen"))
    }

    @Test
    fun `tens convert`() {
        assertEquals("he is 42", ContentNormalizer.normalizeNumbers("he is forty two"))
    }

    @Test
    fun `hundred converts`() {
        assertEquals("300 people", ContentNormalizer.normalizeNumbers("three hundred people"))
    }

    @Test
    fun `thousand converts`() {
        assertEquals("sold 5000 units", ContentNormalizer.normalizeNumbers("sold five thousand units"))
    }

    @Test
    fun `ordinal converts`() {
        assertEquals("the 1st place", ContentNormalizer.normalizeNumbers("the first place"))
    }

    @Test
    fun `percent converts`() {
        assertEquals("85%", ContentNormalizer.normalizeNumbers("85 percent"))
    }

    @Test
    fun `dollar currency rewrites`() {
        assertEquals("$50", ContentNormalizer.normalizeNumbers("50 dollars"))
    }

    @Test
    fun `euro currency rewrites`() {
        assertEquals("€30", ContentNormalizer.normalizeNumbers("30 euros"))
    }

    @Test
    fun `pound currency rewrites`() {
        assertEquals("£10", ContentNormalizer.normalizeNumbers("10 pounds"))
    }

    // -------------------------------------------------------------------------
    // Time expression ITN
    // -------------------------------------------------------------------------

    @Test
    fun `spoken time with meridiem`() {
        assertEquals("meet at 3:30 PM", ContentNormalizer.normalizeNumbers("meet at three thirty PM"))
    }

    @Test
    fun `spoken time oh minutes`() {
        assertEquals("at 7:05 AM", ContentNormalizer.normalizeNumbers("at seven oh five AM"))
    }

    @Test
    fun `spoken time o'clock`() {
        assertEquals("at 6:00 PM", ContentNormalizer.normalizeNumbers("at six o'clock PM"))
    }

    @Test
    fun `spoken time without meridiem`() {
        assertEquals("at 10:15", ContentNormalizer.normalizeNumbers("at ten fifteen"))
    }

    @Test
    fun `spoken time case insensitive meridiem`() {
        assertEquals("at 12:45 PM", ContentNormalizer.normalizeNumbers("at twelve forty five pm"))
    }

    // -------------------------------------------------------------------------
    // Full pipeline integration
    // -------------------------------------------------------------------------

    @Test
    fun `full pipeline with fillers and spoken punctuation`() {
        val input = "so um I uh want to say hello comma world period"
        val result = ContentNormalizer.normalize(input)
        assertEquals("so I want to say hello, world.", result)
    }

    @Test
    fun `full pipeline with emoji and spoken punctuation`() {
        val input = "that's great thumbs up emoji exclamation point"
        val result = ContentNormalizer.normalize(input)
        assertEquals("that's great \uD83D\uDC4D!", result)
    }

    @Test
    fun `full pipeline preserves normal text`() {
        val input = "the quick brown fox jumps over the lazy dog"
        val result = ContentNormalizer.normalize(input)
        assertEquals("the quick brown fox jumps over the lazy dog", result)
    }

    @Test
    fun `normalization is idempotent`() {
        val input = "um hello comma world period"
        val once = ContentNormalizer.normalize(input)
        val twice = ContentNormalizer.normalize(once)
        assertEquals(once, twice)
    }

    // -------------------------------------------------------------------------
    // New spoken emoji entries (extended)
    // -------------------------------------------------------------------------

    @Test
    fun `sleeping face emoji converts`() {
        assertEquals("\uD83D\uDE34", ContentNormalizer.normalize("sleeping face emoji"))
    }

    @Test
    fun `angel face emoji converts`() {
        assertEquals("\uD83D\uDE07", ContentNormalizer.normalize("angel face emoji"))
    }

    @Test
    fun `devil face emoji converts`() {
        assertEquals("\uD83D\uDE08", ContentNormalizer.normalize("devil face emoji"))
    }

    @Test
    fun `ghost emoji converts`() {
        assertEquals("\uD83D\uDC7B", ContentNormalizer.normalize("ghost emoji"))
    }

    @Test
    fun `alien emoji converts`() {
        assertEquals("\uD83D\uDC7D", ContentNormalizer.normalize("alien emoji"))
    }

    @Test
    fun `robot emoji converts`() {
        assertEquals("\uD83E\uDD16", ContentNormalizer.normalize("robot emoji"))
    }

    @Test
    fun `handshake emoji converts`() {
        assertEquals("\uD83E\uDD1D", ContentNormalizer.normalize("handshake emoji"))
    }

    @Test
    fun `eyes emoji converts`() {
        assertEquals("\uD83D\uDC40", ContentNormalizer.normalize("eyes emoji"))
    }

    @Test
    fun `brain emoji converts`() {
        assertEquals("\uD83E\uDDE0", ContentNormalizer.normalize("brain emoji"))
    }

    @Test
    fun `rose emoji converts`() {
        assertEquals("\uD83C\uDF39", ContentNormalizer.normalize("rose emoji"))
    }

    @Test
    fun `four leaf clover emoji converts`() {
        assertEquals("\uD83C\uDF40", ContentNormalizer.normalize("four leaf clover emoji"))
    }

    @Test
    fun `gem emoji converts`() {
        assertEquals("\uD83D\uDC8E", ContentNormalizer.normalize("gem emoji"))
    }

    @Test
    fun `lion emoji converts`() {
        assertEquals("\uD83E\uDD81", ContentNormalizer.normalize("lion emoji"))
    }

    @Test
    fun `shark emoji converts`() {
        assertEquals("\uD83E\uDD88", ContentNormalizer.normalize("shark emoji"))
    }

    @Test
    fun `hamburger emoji converts`() {
        assertEquals("\uD83C\uDF54", ContentNormalizer.normalize("hamburger emoji"))
    }

    @Test
    fun `boom emoji converts`() {
        assertEquals("\uD83D\uDCA5", ContentNormalizer.normalize("boom emoji"))
    }

    @Test
    fun `ghost without emoji suffix stays as text`() {
        assertEquals("ghost", ContentNormalizer.normalize("ghost"))
    }

    @Test
    fun `robot without emoji suffix stays as text`() {
        assertEquals("robot", ContentNormalizer.normalize("robot"))
    }

    // -------------------------------------------------------------------------
    // Hallucination removal
    // -------------------------------------------------------------------------

    @Test
    fun `hallucination thank you for watching is removed`() {
        assertEquals("", ContentNormalizer.normalize("Thank you for watching"))
    }

    @Test
    fun `hallucination thanks for watching is removed`() {
        assertEquals("", ContentNormalizer.normalize("Thanks for watching"))
    }

    @Test
    fun `hallucination please subscribe is removed`() {
        assertEquals("", ContentNormalizer.normalize("please subscribe"))
    }

    @Test
    fun `hallucination subscribe to is removed`() {
        assertEquals("", ContentNormalizer.normalize("subscribe to"))
    }

    @Test
    fun `hallucination like and subscribe is removed`() {
        assertEquals("", ContentNormalizer.normalize("Like and Subscribe"))
    }

    @Test
    fun `hallucination subtitles by is removed`() {
        assertEquals("", ContentNormalizer.normalize("subtitles by"))
    }

    @Test
    fun `hallucination translated by is removed`() {
        assertEquals("", ContentNormalizer.normalize("translated by"))
    }

    @Test
    fun `hallucination translation by is removed`() {
        assertEquals("", ContentNormalizer.normalize("translation by"))
    }

    @Test
    fun `hallucination transcribed by is removed`() {
        assertEquals("", ContentNormalizer.normalize("Transcribed by"))
    }

    @Test
    fun `hallucination all rights reserved is removed`() {
        assertEquals("", ContentNormalizer.normalize("All Rights Reserved"))
    }

    @Test
    fun `hallucination music playing is removed`() {
        assertEquals("", ContentNormalizer.normalize("music playing"))
    }

    @Test
    fun `hallucination applause is removed`() {
        assertEquals("", ContentNormalizer.normalize("applause"))
    }

    @Test
    fun `hallucination copyright alone is removed`() {
        assertEquals("", ContentNormalizer.normalize("copyright"))
    }

    @Test
    fun `copyright sign spoken punctuation is preserved`() {
        assertEquals("\u00A9", ContentNormalizer.normalize("copyright sign"))
    }

    @Test
    fun `hallucination is case insensitive`() {
        assertEquals("", ContentNormalizer.normalize("THANK YOU FOR WATCHING"))
    }

    @Test
    fun `hallucination mid-sentence keeps surrounding text`() {
        val result = ContentNormalizer.normalize("hello thank you for watching world")
        assertEquals("hello world", result)
    }

    @Test
    fun `multiple hallucinations in one string`() {
        val result = ContentNormalizer.normalize("applause thank you for watching please subscribe")
        assertEquals("", result)
    }

    @Test
    fun `hallucination partial match is not removed`() {
        val result = ContentNormalizer.normalize("I am thankful for watching movies")
        assertEquals("I am thankful for watching movies", result)
    }

    @Test
    fun `hallucination URL www dot pattern is removed`() {
        assertEquals("", ContentNormalizer.normalize("www.youtube.com"))
    }

    @Test
    fun `hallucination HTTPS URL is removed`() {
        assertEquals("", ContentNormalizer.normalize("https://example.com/path"))
    }

    @Test
    fun `hallucination HTTP URL is removed`() {
        assertEquals("", ContentNormalizer.normalize("http://example.com"))
    }

    @Test
    fun `URL hallucination mid-sentence keeps surrounding text`() {
        val result = ContentNormalizer.normalize("visit www.youtube.com for more")
        assertEquals("visit for more", result)
    }

    @Test
    fun `hallucination mixed with real content preserves real content`() {
        val result = ContentNormalizer.normalize("I went to the store thank you for watching")
        assertEquals("I went to the store", result)
    }

    @Test
    fun `triple repeat hallucination is collapsed`() {
        val result = ContentNormalizer.normalize("the the the")
        assertEquals("the", result)
    }

    @Test
    fun `subscribe to mid-sentence is removed`() {
        val result = ContentNormalizer.normalize("hey subscribe to the channel")
        assertEquals("hey the channel", result)
    }

    @Test
    fun `new emoji mid-sentence`() {
        val result = ContentNormalizer.normalize("congrats handshake emoji well done")
        assertEquals("congrats \uD83E\uDD1D well done", result)
    }

    @Test
    fun `multiple new emojis in same sentence`() {
        val result = ContentNormalizer.normalize("ghost emoji and alien emoji")
        assertEquals("\uD83D\uDC7B and \uD83D\uDC7D", result)
    }

    // -------------------------------------------------------------------------
    // Emoji fast-path optimization — no "emoji" keyword skips regex
    // -------------------------------------------------------------------------

    @Test
    fun `text without emoji keyword is unchanged by emoji conversion`() {
        val input = "the ghost of the robot alien brain was sleeping"
        assertEquals(input, ContentNormalizer.normalize(input))
    }

    // -------------------------------------------------------------------------
    // New spoken punctuation entries
    // -------------------------------------------------------------------------

    @Test
    fun `spoken equals sign with trailing s`() {
        val result = ContentNormalizer.normalize("x equals sign y")
        assertEquals("x = y", result)
    }

    @Test
    fun `spoken bullet point`() {
        val result = ContentNormalizer.normalize("bullet point fix the bug")
        assertEquals("\u2022 fix the bug", result)
    }

    @Test
    fun `spoken en dash`() {
        val result = ContentNormalizer.normalize("pages 10 en dash 20")
        assertEquals("pages 10 \u2013 20", result)
    }

    @Test
    fun `spoken registered trademark`() {
        val result = ContentNormalizer.normalize("safe word registered trademark")
        assertEquals("safe word\u00AE", result)
    }

    @Test
    fun `spoken registered sign`() {
        val result = ContentNormalizer.normalize("acme registered sign")
        assertEquals("acme\u00AE", result)
    }

    @Test
    fun `spoken euro sign before number`() {
        val result = ContentNormalizer.normalize("it costs euro sign 50")
        assertEquals("it costs \u20AC50", result)
    }

    @Test
    fun `spoken cent sign`() {
        val result = ContentNormalizer.normalize("that's 99 cent sign")
        assertEquals("that's 99\u00A2", result)
    }

    @Test
    fun `spoken yen sign before number`() {
        val result = ContentNormalizer.normalize("price yen sign 1000")
        assertEquals("price \u00A51000", result)
    }

    @Test
    fun `spoken right arrow`() {
        val result = ContentNormalizer.normalize("a right arrow b")
        assertEquals("a \u2192 b", result)
    }

    @Test
    fun `spoken left arrow`() {
        val result = ContentNormalizer.normalize("b left arrow a")
        assertEquals("b \u2190 a", result)
    }

    @Test
    fun `spoken copyright and trademark together`() {
        val result = ContentNormalizer.normalize("acme copyright sign 2024 trademark")
        assertEquals("acme\u00A9 2024\u2122", result)
    }

    @Test
    fun `spoken degree sign unchanged by new edits`() {
        val result = ContentNormalizer.normalize("it's 72 degree sign outside")
        assertEquals("it's 72\u00B0 outside", result)
    }

    // -------------------------------------------------------------------------
    // Mixed new emoji + new punctuation
    // -------------------------------------------------------------------------

    @Test
    fun `new emoji and new punctuation in same sentence`() {
        val result = ContentNormalizer.normalize("great job handshake emoji exclamation point")
        assertEquals("great job \uD83E\uDD1D!", result)
    }

    @Test
    fun `arrow with emoji`() {
        val result = ContentNormalizer.normalize("go right arrow rocket emoji")
        assertEquals("go \u2192 \uD83D\uDE80", result)
    }
}
