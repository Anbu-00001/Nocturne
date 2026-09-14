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

    @Test
    fun `durations read like the spec example`() {
        assertEquals("3h 12m", Tone.duration(192 * 60_000L))
        assertEquals("1h 0m", Tone.duration(60 * 60_000L))
        assertEquals("4m", Tone.duration(4 * 60_000L + 59_000))
        assertEquals("38s", Tone.duration(38_000))
        assertEquals("under 1s", Tone.duration(400))
    }

    @Test
    fun `every session kind has a label`() {
        for (kind in SessionKind.entries) assertTrue(Tone.kindLabel(kind).isNotBlank())
    }
}
