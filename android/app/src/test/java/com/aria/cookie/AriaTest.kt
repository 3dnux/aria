package com.aria.cookie

import com.aria.cookie.aria.Candidate
import com.aria.cookie.aria.ConceptGraph
import com.aria.cookie.aria.Gate
import com.aria.cookie.aria.LocalAnswerer
import com.aria.cookie.aria.Pattern
import com.aria.cookie.aria.Patterns
import com.aria.cookie.aria.Router
import com.aria.cookie.core.Backup
import com.aria.cookie.core.CalendarEvent
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.Engine
import com.aria.cookie.core.TimelineEvent
import com.aria.cookie.core.patternNudges
import com.aria.cookie.core.relevant
import com.aria.cookie.core.remember
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Paridad con los módulos Go de ARIA sobre los mismos datos (testdata/aria). */
class AriaTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun fixture(name: String) = File("../../testdata/aria/$name")

    @Serializable
    data class RouterCase(val q: String, val tier: String, val intent: String = "", val effort: String = "", val web: Boolean? = null)

    @Test
    fun routerMatchesGoOnSharedCases() {
        val cases = json.decodeFromString<List<RouterCase>>(fixture("router_cases.json").readText())
        for (c in cases) {
            val r = Router.route(c.q)
            assertEquals(c.q, c.tier, r.tier.name.lowercase())
            if (c.intent.isNotEmpty()) assertEquals(c.q, c.intent, r.intent)
            if (c.effort.isNotEmpty()) assertEquals(c.q, c.effort, r.effort.name.lowercase())
            c.web?.let { assertEquals(c.q, it, r.webSearches > 0) }
        }
    }

    @Serializable
    data class GoPattern(val cause: String, val effect: String, val window_hours: Int, val count: Int, val support: Int, val lift: Double)

    @Test
    fun patternsMatchGoExactly() {
        val events = json.decodeFromString<List<TimelineEvent>>(fixture("timeline.json").readText())
        val want = json.decodeFromString<List<GoPattern>>(fixture("patterns_expected.json").readText())
        val got = Patterns.mine(events)
        assertEquals(want.size, got.size)
        want.zip(got).forEach { (w, g) ->
            assertEquals(w.cause, g.cause)
            assertEquals(w.effect, g.effect)
            assertEquals(w.window_hours, g.windowHours)
            assertEquals(w.support, g.support)
            assertEquals(w.count, g.count)
            assertEquals(w.lift, g.lift, 1e-9)
        }
        assertTrue(got.any { it.cause == "sueño:corto" && it.effect == "estrés:alto" })
        assertTrue(got.none { it.cause == "app:instagram" })
    }

    @Test
    fun backupMadeByGoOpensInTheApp() = runBlocking {
        val plain = Backup.decrypt(fixture("copia.cookie").readBytes(), "contraseña-aria")
        val e = Engine(File(Files.createTempDirectory("c").toFile(), "c.json"))
        e.importJson(plain)
        assertEquals("Prueba", e.current().profile.name)
        assertEquals(170, e.current().timeline.size)
    }

    @Test
    fun gateExitsLearnsAndUsesConsensus() {
        val g = Gate()
        assertFalse(g.decide(listOf(Candidate("rutina", "gimnasio", "gimnasio", 0.6))).exit)
        val d = g.decide(listOf(Candidate("rutina", "Vas al gimnasio", "gimnasio", 0.6), Candidate("contexto", "gimnasio", "gimnasio", 0.5)))
        assertTrue(d.exit && d.reason.startsWith("consenso"))
        g.observe(0.9, correct = false)
        assertTrue(g.threshold >= 0.92)
        repeat(100) { g.observe(0.8, correct = true) }
        assertEquals(g.min, g.threshold, 1e-9)
    }

    @Test
    fun conceptGraphAssociatesLikeGo() {
        val g = ConceptGraph()
        g.addDocument(listOf("maraton", "abril", "entren"))
        g.addDocument(listOf("entren", "rodill", "dolor"))
        g.addDocument(listOf("maraton", "dorm", "temprano"))
        g.addDocument(listOf("sushi", "laura", "viern"))
        val names = g.spread(listOf("maraton"), 2, 0.5, 0.05).map { it.concept }
        assertTrue(names.containsAll(listOf("entren", "dorm", "rodill")))
        assertFalse("sushi" in names)
    }

    @Test
    fun associativeRecallFindsRelatedMemories() {
        val s = CookieState()
        s.memories.remember("meta", "Estoy entrenando para un maratón en abril", 0)
        s.memories.remember("hecho", "Cuando entreno mucho me duele la rodilla", 0)
        s.memories.remember("gusto", "Me encanta el sushi de los viernes con Laura", 0)
        // "rodilla" no aparece en la pregunta: llega por asociación maratón → entrenar → rodilla.
        val top = s.memories.relevant("¿cómo voy con el maratón?", 0, 2).map { it.text }
        assertTrue(top.any { "rodilla" in it })
        assertFalse(top.any { "sushi" in it })
    }

    @Test
    fun localAnswersAndPatternNudges() {
        val now = 1772323200000L + 10 * 3_600_000L
        val s = CookieState()
        s.upcoming += CalendarEvent(1, "Dentista", now + 3_600_000L, now + 7_200_000L)
        val agenda = LocalAnswerer.candidates("agenda", s, now)
        assertTrue(agenda.single().answer.contains("Dentista"))
        assertTrue(s.gate.decide(agenda).exit)
        assertTrue(LocalAnswerer.candidates("sueno", s, now).isEmpty()) // sin datos no inventa

        s.patterns += Pattern("sueño:corto", "estrés:alto", 24, 8, 7, 0.875, 0.1, 8.0, 15.0, 1.0)
        s.timeline += TimelineEvent(now - 8 * 3_600_000L, "sueño", "a dormir (5h 0min)")
        val n = patternNudges(s, now, "2026-03-01").single()
        assertTrue(n.text.contains("duermes poco") && n.text.contains("7 de 8"))
        s.timeline += TimelineEvent(now - 3_600_000L, "estrés", "alto")
        assertTrue(patternNudges(s, now, "2026-03-01").isEmpty()) // el efecto ya pasó
    }
}
