package com.aria.cookie

import com.aria.cookie.core.Backup
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.Engine
import com.aria.cookie.core.HeartSample
import com.aria.cookie.core.MissionReport
import com.aria.cookie.core.NudgeStat
import com.aria.cookie.core.PhotoInfo
import com.aria.cookie.core.QuizRound
import com.aria.cookie.core.SelfModel
import com.aria.cookie.core.TimelineEvent
import com.aria.cookie.core.Weather
import com.aria.cookie.core.applyMissionReport
import com.aria.cookie.core.concepts
import com.aria.cookie.core.dayContext
import com.aria.cookie.core.fidelityQuestions
import com.aria.cookie.core.learnHeart
import com.aria.cookie.core.learnMovement
import com.aria.cookie.core.learnNotification
import com.aria.cookie.core.learnPhotos
import com.aria.cookie.core.log
import com.aria.cookie.core.memoriesWithoutAnswer
import com.aria.cookie.core.nudges
import com.aria.cookie.core.parseJsonObject
import com.aria.cookie.core.predictContext
import com.aria.cookie.core.relevant
import com.aria.cookie.core.remember
import com.aria.cookie.core.shouldShowNudge
import com.aria.cookie.core.stem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class MindTest {
    private fun at(day: Int, hour: Int, min: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun semanticRecallFindsSynonymsStemsAndTags() {
        val s = CookieState()
        val now = at(2, 10)
        s.memories.remember("persona", "Mi madre vive en Guadalajara", now)
        s.memories.remember("meta", "Estoy entrenando para correr un maratón", now)
        s.memories.remember("hecho", "Trabajo en una agencia de diseño", now, tags = listOf("chamba", "empleo"))
        s.memories.remember("gusto", "Me encanta el sushi", now)
        assertEquals(stem("corriendo"), stem("correr"))
        assertTrue(concepts("mamá").contains(stem("madre")))
        assertEquals("persona", s.memories.relevant("¿cómo está mi mamá?", now, 1).single().kind)
        assertEquals("meta", s.memories.relevant("sigo corriendo?", now, 1).single().kind)
        assertEquals("hecho", s.memories.relevant("mi chamba", now, 1).single().kind)
    }

    @Test
    fun thompsonSamplingSilencesUselessNudgesAndKeepsUsefulOnes() {
        val rng = Random(7)
        val t = at(2, 9)
        val bad = mapOf("clima@mañana" to NudgeStat(useful = 0, useless = 12, shown = 14))
        val good = mapOf("clima@mañana" to NudgeStat(useful = 12, useless = 1, shown = 13))
        val shownBad = (1..200).count { shouldShowNudge(bad, "clima:x", t, rng) }
        val shownGood = (1..200).count { shouldShowNudge(good, "clima:x", t, rng) }
        assertTrue("malos=$shownBad", shownBad < 30)
        assertTrue("buenos=$shownGood", shownGood > 180)
        assertTrue(shouldShowNudge(emptyMap(), "clima:x", t, rng)) // sin datos: se muestra
    }

    @Test
    fun contextPredictorLearnsWeekdayMorningsAtThePlace() {
        val s = CookieState()
        for (d in 2..20) {
            val weekend = LocalDate.of(2026, 3, d).dayOfWeek.value >= 6
            s.log(at(d, 6, 50), "lugar", "llegaste a ${if (weekend) "casa" else "gimnasio"}")
            s.log(at(d, 7), "actividad", if (weekend) "desayunar" else "pesas")
            s.log(at(d, 20), "actividad", "leer")
        }
        val g = predictContext(s, at(23, 7), "gimnasio") // lunes 23
        assertNotNull(g)
        assertEquals("pesas", g!!.activity)
        assertTrue(g.probability > 0.6)
        assertEquals("leer", predictContext(s, at(23, 20), null)!!.activity)
    }

    @Test
    fun weatherAndMissionsProduceNudges() {
        val s = CookieState()
        val now = at(3, 8)
        s.weather = Weather(temperature = 18.0, code = 61, rainProbNext3h = 80, maxToday = 21.0, minToday = 14.0, at = now)
        val m = com.aria.cookie.core.Mission(goal = "Preparar el maratón de abril")
        applyMissionReport(m, MissionReport("Esta semana: 3 rodajes suaves y dormir 7h", listOf("rodaje 5 km", "series"), false, 48), now - 60_000)
        s.missions += m
        val list = nudges(s, now)
        assertTrue(list.any { it.key.startsWith("clima") && it.text.contains("Paraguas") })
        assertTrue(list.any { it.key.startsWith("misión") })
        assertEquals(48, m.everyHours)
        assertEquals(2, m.plan.size)
    }

    @Test
    fun passiveSensesLearnWithoutStoringMessageText() {
        val s = CookieState()
        val now = at(5, 12)
        repeat(10) { learnNotification(s, "WhatsApp", "Laura", "¿Vamos al concierto de jazz el viernes?", now + it) }
        assertEquals(10, s.contacts["Laura"]!!.count)
        assertTrue(s.memories.any { it.text.contains("Laura") })
        assertFalse(s.timeline.any { it.text.contains("concierto") }) // el texto no se guarda
        learnNotification(s, "WhatsApp", "3 mensajes", "x", now)
        assertNull(s.contacts["3 mensajes"])

        assertEquals("correr", learnMovement(s, "RUNNING", now))
        assertNull(learnMovement(s, "STILL", now))

        assertEquals(12, learnPhotos(s, (1..12).map { PhotoInfo(at(4, 18, it)) }, now))
        assertTrue(s.memories.any { it.kind == "diario" && it.text.contains("12 fotos") })
        assertEquals(0, learnPhotos(s, listOf(PhotoInfo(at(4, 18, 1))), now))

        learnHeart(s, (0 until 60).map { HeartSample(now - 3 * 86_400_000L + it * 60_000L, 60) } +
            (0 until 5).map { HeartSample(now - it * 60_000L, 95) }, now)
        assertEquals(60L, s.health.restingBpm)
        assertEquals("alto", s.health.stress)
    }

    @Test
    fun diaryContextReflectionAndFidelityHoldOut() = runBlocking {
        val e = Engine(File(Files.createTempDirectory("c").toFile(), "c.json")) { at(6, 23) }
        e.learn("Hoy fui al gimnasio y me encanta el jazz")
        val day = dayContext(e.current(), LocalDate.of(2026, 3, 6))
        assertTrue(day.contains("gimnasio") && day.contains("[dijo]"))

        val self = parseJsonObject<SelfModel>("""{"summary":"Soy constante","values":["salud"],"decisionStyle":"planifico","communication":"breve","worries":[],"goals":["maratón"],"changes":""}""")!!
        e.addSelfModel(self.copy(at = at(6, 23)))
        assertEquals("Soy constante", e.current().selfModels.last().summary)
        assertFalse(e.reflectionDue())

        repeat(8) { i -> e.addQuiz(QuizRound("Pregunta $i", "g", "respuesta $i", 1.0, "")) }
        val exam = fidelityQuestions(e.current())
        assertEquals(5, exam.size)
        assertTrue(exam.none { it.question in listOf("Pregunta 5", "Pregunta 6", "Pregunta 7") })
        val q = exam.first()
        assertTrue(memoriesWithoutAnswer(e.current().memories, q).none { it.text.contains(q.question) })
    }

    @Test
    fun encryptedBackupRestoresTheWholeCopy() = runBlocking {
        val e = Engine(File(Files.createTempDirectory("c").toFile(), "c.json"))
        e.learn("Me llamo Sofía y me encanta la fotografía")
        e.addMission("Aprender japonés")
        val blob = Backup.encrypt(e.exportJson(), "contraseña-segura")
        assertFalse(String(blob).contains("Sofía"))
        val restored = Engine(File(Files.createTempDirectory("c").toFile(), "c.json"))
        restored.importJson(Backup.decrypt(blob, "contraseña-segura"))
        assertEquals("Sofía", restored.current().profile.name)
        assertEquals("Aprender japonés", restored.current().missions.single().goal)
        assertTrue(runCatching { Backup.decrypt(blob, "otra-contraseña") }.isFailure)
    }

    @Test
    fun wakeWordIsDetected() {
        assertEquals("pon música", afterWakeWord("Oye Cookie, pon música"))
        assertEquals("", afterWakeWord("hey cuki"))
        assertNull(afterWakeWord("mañana voy al cine"))
        assertTrue(TimelineEvent(0, "a", "b").text == "b")
    }
}
