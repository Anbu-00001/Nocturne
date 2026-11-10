package io.github.anbu00001.nocturne.tone

import io.github.anbu00001.nocturne.core.glance.SessionKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mechanical half of spec §1.2 correction 1: no exclamation marks, no emoji, no verdicts on the person. */
class ToneAuditTest {

    private val literals: List<Pair<String, String>> by lazy {
        val stringLiteral = Regex(""""(?:[^"\\\n]|\\.)*"""")
        File("src/main/kotlin").walk().filter { it.extension == "kt" }.flatMap { file ->
            stringLiteral.findAll(file.readText()).map { file.name to it.value }
        }.toList()
    }

    private fun offenders(predicate: (String) -> Boolean) =
        literals.filter { (_, text) -> predicate(text) }.joinToString("\n") { (file, text) -> "$file: $text" }

    @Test
    fun `the audit actually sees the strings`() {
        assertTrue(literals.size > 50, "only ${literals.size} string literals found")
    }

    @Test
    fun `no exclamation marks`() {
        val found = offenders { '!' in it }
        assertTrue(found.isEmpty(), found)
    }

    @Test
    fun `no emoji`() {
        fun isEmoji(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D
        val found = offenders { text -> text.codePoints().anyMatch(::isEmoji) }
        assertTrue(found.isEmpty(), found)
    }

    @Test
    fun `no judgemental vocabulary`() {
        val banned = listOf(
            "waste", "bad", "terrible", "awful", "addict", "shame", "guilt", "lazy", "you failed",
            "unhealthy", "ruin", "you should", "you need to", "too much", "again",
        )
        val found = offenders { text -> banned.any { Regex("""\b${Regex.escape(it)}""", RegexOption.IGNORE_CASE).containsMatchIn(text) } }
        assertTrue(found.isEmpty(), found)
    }

    /**
     * The evidence review's "Do not say" list (10 Nov 2026), as far as phrases can carry it: no hormone is ever the user's,
     * none is said to rise or fall, some are never named, and nothing is said to cause or diagnose. Hormone names that are
     * allowed (dopamine, cortisol, melatonin) may only appear in a sentence that carries its source in brackets or is
     * about the model, which the second half checks for the two the review covers.
     */
    @Test
    fun `no hormone readings, causes or diagnoses`() {
        val banned = listOf(
            "your dopamine", "your cortisol", "your melatonin", "your stress", "your reward", "your brain", "your hpa",
            "adrenaline", "noradrenaline", "epinephrine", "serotonin", "oxytocin", "endorphin",
            "dopamine hit", "dopamine loop", "dopamine detox", "detox", "awakening response", "stress hormone",
            "spik", "crash", "caused", "causes", "because of your", "addict", "ruminat", "depress", "anxi", "adhd", "arousal",
            "lowers your", "raises your", "raised your", "reduced your", "boost",
        )
        val found = offenders { text -> banned.any { Regex("""\b${Regex.escape(it)}""", RegexOption.IGNORE_CASE).containsMatchIn(text) } }
        assertTrue(found.isEmpty(), found)
        val unsourced = offenders { text ->
            Regex("""\b(dopamine|cortisol)""", RegexOption.IGNORE_CASE).containsMatchIn(text) && !Regex("""\(\w[^)]*(19|20)\d\d""").containsMatchIn(text)
        }
        assertTrue(unsourced.isEmpty(), unsourced)
    }

    @Test
    fun `durations read like the spec example`() {
        assertEquals("3h 12m", Tone.duration(192 * 60_000L))
        assertEquals("1h 0m", Tone.duration(60 * 60_000L))
        assertEquals("4m", Tone.duration(4 * 60_000L + 59_000))
        assertEquals("38s", Tone.duration(38_000))
        assertEquals("under 1s", Tone.duration(400))
    }

    @Test
    fun `the week's runs line names only what happened`() {
        assertEquals(null, Tone.Week.runs(short = 0, long = 0, sleepless = 0))
        assertEquals("One night under 5 h, 3 nights of 9 h or more.", Tone.Week.runs(short = 1, long = 3, sleepless = 0))
        assertEquals("2 nights without sleep.", Tone.Week.runs(short = 0, long = 0, sleepless = 2))
    }

    @Test
    fun `every session kind has a label`() {
        for (kind in SessionKind.entries) assertTrue(Tone.kindLabel(kind).isNotBlank())
    }
}
