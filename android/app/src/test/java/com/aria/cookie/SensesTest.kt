package com.aria.cookie

import com.aria.cookie.core.ActionTypes
import com.aria.cookie.core.AppSession
import com.aria.cookie.core.CalendarEvent
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.Engine
import com.aria.cookie.core.MemoryDigest
import com.aria.cookie.core.MemoryDraft
import com.aria.cookie.core.SleepNight
import com.aria.cookie.core.QuizRound
import com.aria.cookie.core.TwinContext
import com.aria.cookie.core.applyDigest
import com.aria.cookie.core.describeContext
import com.aria.cookie.core.learnApps
import com.aria.cookie.core.learnCalendar
import com.aria.cookie.core.learnSleep
import com.aria.cookie.core.nudges
import com.aria.cookie.core.observeLocation
import com.aria.cookie.core.parseJsonObject
import com.aria.cookie.core.relevant
import com.aria.cookie.core.remember
import com.aria.cookie.core.twinSimilarity
import com.aria.cookie.core.twinSystem
import com.aria.cookie.core.Codec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

class SensesTest {
    private fun at(day: Int, hour: Int, min: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun memoriesMergeAndAreRecalledByTopic() {
        val s = CookieState()
        val now = at(2, 10)
        s.memories.remember("persona", "Mi hermana Laura vive en Madrid", now)
        s.memories.remember("persona", "Mi hermana Laura vive en Madrid desde 2020", now)
        s.memories.remember("meta", "Estoy preparando un maratón en abril", now)
        assertEquals(2, s.memories.size)
        assertEquals(2, s.memories.first { it.kind == "persona" }.mentions)
        assertTrue(s.memories.first().text.endsWith("2020"))
        assertEquals("meta", s.memories.relevant("¿cómo va lo del maratón?", now, 1).single().kind)
    }

    @Test
    fun digestFromClaudeUpdatesProfileAndMemory() {
        val json = """Claro: {"name":"Leo","likes":["pádel"],"dislikes":["reguetón"],"activities":["pádel"],
            "memories":[{"kind":"ánimo","text":"Hoy estoy agobiado por una entrega"}]}"""
        val d = parseJsonObject<MemoryDigest>(json)!!
        val s = CookieState()
        applyDigest(s, d, at(2, 10))
        assertEquals("Leo", s.profile.name)
        assertEquals("gusto", s.profile.interests["pádel"]!!.kind)
        assertTrue("reguetón" in s.profile.dislikes)
        assertEquals("ánimo", s.memories.single().kind)
        assertEquals(MemoryDigest(), parseJsonObject<MemoryDigest>("sin json") ?: MemoryDigest())
    }

    @Test
    fun placesAreDiscoveredAndLabelled() {
        val s = CookieState()
        val home = 25.6866 to -100.3161
        val gym = 25.7000 to -100.3000
        for (d in 2..8) {
            observeLocation(s, home.first, home.second, at(d, 23))
            observeLocation(s, home.first + 0.0003, home.second, at(d, 2)) // ~30 m: mismo lugar
            observeLocation(s, gym.first, gym.second, at(d, 7))
        }
        assertEquals(2, s.places.size)
        assertEquals("casa", s.places.first { it.samples == 14 }.label)
        val g = s.places.first { it.label != "casa" }
        assertEquals(7, g.visits)
        // Al volver a casa se detecta la llegada.
        assertTrue(observeLocation(s, home.first, home.second, at(9, 22)).arrived)
        assertFalse(observeLocation(s, home.first, home.second, at(9, 23)).arrived)
    }

    @Test
    fun calendarSleepAndAppsTeachRoutine() {
        val s = CookieState()
        val now = at(10, 12)
        val events = (2..6).map { CalendarEvent(it.toLong(), "Clase de inglés", at(it, 19), at(it, 20)) } +
            CalendarEvent(99, "Dentista", at(10, 13), at(10, 14), "Clínica Sur")
        assertEquals(5, learnCalendar(s, events, now))
        assertEquals(0, learnCalendar(s, events, now)) // no repite
        assertEquals(5, s.profile.routines["clase de inglés"]!!.count)
        assertEquals("Dentista", s.upcoming.single().title)

        assertEquals(2, learnSleep(s, listOf(SleepNight(at(8, 23), at(9, 7)), SleepNight(at(10, 1), at(10, 6))), 3000, 8000, now))
        assertEquals(5 * 60, s.health.lastNight(now)!!.minutes)

        val n = learnApps(s, listOf(AppSession("com.x", "Strava", "viajes", at(10, 7), at(10, 7, 30))), now)
        assertEquals(1, n)
        assertEquals("Strava", s.apps["com.x"]!!.label)
    }

    @Test
    fun nudgesAnticipateAndDoNotRepeat() {
        val s = CookieState()
        val now = at(10, 8)
        s.upcoming += CalendarEvent(1, "Reunión con Ana", now + 45 * 60_000, now + 105 * 60_000, "Oficina Centro")
        s.health.sleep += SleepNight(at(10, 2), at(10, 7))
        val list = nudges(s, now)
        assertTrue(list.any { it.title.contains("Reunión con Ana") && it.action?.type == ActionTypes.OPEN })
        assertTrue(list.any { it.key.startsWith("sueño") })
        list.forEach { s.nudged[it.key] = now }
        assertTrue(nudges(s, now + 60_000).isEmpty())
    }

    @Test
    fun arrivingAtKnownPlaceSuggestsItsMusic() {
        val s = CookieState()
        s.profile.music.nowPlaying = "Titi Me Preguntó — Bad Bunny"
        repeat(3) { d -> observeLocation(s, 25.70, -100.30, at(2 + d, 7)); s.currentPlaceId = null }
        val gym = s.places.single().also { it.label = "gimnasio"; it.custom = true }
        val n = nudges(s, at(9, 7), arrivedAt = gym).first { it.key.startsWith("llegada") }
        assertEquals("Bad Bunny", n.action!!.params["consulta"])
    }

    @Test
    fun twinPromptIncludesSensesMemoriesAndStyle() = runBlocking {
        val e = Engine(File(Files.createTempDirectory("c").toFile(), "c.json")) { at(10, 9) }
        e.learn("Me llamo Ana y trabajo como diseñadora")
        e.addStyle("¿vamos al cine?", "uff hoy no, mejor el finde 😅")
        e.addQuiz(QuizRound("¿Pizza o tacos?", "pizza", "tacos siempre", 0.0, "Prefiero tacos antes que pizza"))
        val s = e.current()
        val ctx = TwinContext(s.profile, s.memories, s.style, s.places, s.upcoming, s.health, emptyList(), listOf("light.salon (Salón: off)"))
        val sys = twinSystem(ctx).let { it.stable + it.volatile }
        assertTrue(sys.contains("Ana") && sys.contains("mejor el finde") && sys.contains("tacos") && sys.contains("light.salon"))
        assertEquals(0, twinSimilarity(s))
        assertTrue(describeContext(ctx).contains("Dispositivos"))
    }

    @Test
    fun encryptedStateRoundTripsAndReadsOldPlainFiles() = runBlocking {
        val xor = object : Codec {
            override fun encode(text: String) = text.toByteArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun decode(bytes: ByteArray): String {
                val s = String(bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
                require(s.startsWith("{")) { "no cifrado" }
                return s
            }
        }
        val file = File(Files.createTempDirectory("c").toFile(), "c.json")
        Engine(file).learn("Me llamo Leo") // archivo antiguo, sin cifrar
        val enc = Engine(file, xor)
        assertEquals("Leo", enc.current().profile.name)
        enc.learn("Me encanta el pádel")
        assertFalse(String(file.readBytes()).contains("Leo")) // ya cifrado en disco
        assertNotNull(Engine(file, xor).current().profile.interests["pádel"])
        assertNull(null)
    }
}
