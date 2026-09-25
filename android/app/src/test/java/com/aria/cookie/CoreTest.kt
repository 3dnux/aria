package com.aria.cookie

import com.aria.cookie.core.Engine
import com.aria.cookie.core.Item
import com.aria.cookie.core.Profile
import com.aria.cookie.core.SpotifyArtist
import com.aria.cookie.core.SpotifyPlay
import com.aria.cookie.core.SpotifySnapshot
import com.aria.cookie.core.StaticSource
import com.aria.cookie.core.SpotifyAuth
import com.aria.cookie.core.extract
import com.aria.cookie.core.learnFromSpotify
import com.aria.cookie.core.parseClaudeItems
import com.aria.cookie.core.parseFeed
import com.aria.cookie.core.twinSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

class CoreTest {
    private fun at(day: Int, hour: Int) =
        LocalDateTime.of(2026, 3, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun extractsProfileFromFreeText() {
        val s = extract(
            "Me llamo Ana, trabajo como diseñadora gráfica y vivo en Monterrey. " +
                "Me encanta la fotografía y me interesa la inteligencia artificial, pero no me gusta el fútbol. Acabo de llegar a la oficina"
        ).groupBy({ it.kind }, { it.value })
        assertEquals(listOf("Ana"), s["nombre"])
        assertEquals(listOf("diseñadora gráfica"), s["trabajo"])
        assertEquals(listOf("Monterrey"), s["lugar"])
        assertTrue(s["gusto"]!!.containsAll(listOf("fotografía", "inteligencia artificial")))
        assertEquals(listOf("fútbol"), s["rechazo"])
        assertEquals(listOf("oficina"), s["actividad"])
    }

    @Test
    fun learnsFromSpotifyWithoutInflatingOnResync() {
        val p = Profile()
        val snap = SpotifySnapshot(
            displayName = "Luis Pérez",
            topArtists = listOf(SpotifyArtist("Bad Bunny", listOf("reggaeton")), SpotifyArtist("Natalia Lafourcade", listOf("pop latino"))),
            topTracks = listOf("DtMF — Bad Bunny"),
            recent = listOf(SpotifyPlay("DtMF", "Bad Bunny", at(2, 7)), SpotifyPlay("Hasta la Raíz", "Natalia Lafourcade", at(2, 22))),
            nowPlaying = "DtMF — Bad Bunny",
        )
        assertEquals(2, learnFromSpotify(p, snap, at(3, 9)))
        val w = p.interests["bad bunny"]!!.weight
        assertEquals("música", p.interests["bad bunny"]!!.kind)
        assertEquals("Luis", p.name)
        assertEquals("Bad Bunny", p.music.favoriteAt(7))
        assertEquals(2, p.rhythm.sumOf { it.sum() }) // 2 reproducciones = 2 marcas de actividad

        // Una segunda sincronización no duplica reproducciones ni infla pesos.
        assertEquals(0, learnFromSpotify(p, snap, at(3, 10)))
        assertEquals(w, p.interests["bad bunny"]!!.weight, 0.05)
    }

    @Test
    fun researchRanksFiltersAndLearnsFromFeedback() = runBlocking {
        val now = at(9, 12)
        val file = File(Files.createTempDirectory("cookie").toFile(), "c.json")
        val engine = Engine(file) { now }
        engine.learn("Trabajo como diseñadora gráfica, me encanta la fotografía y no me gusta el fútbol")
        val src = StaticSource(
            listOf(
                Item(id = "1", title = "Nueva cámara para fotografía nocturna", published = now - 3_600_000),
                Item(id = "2", title = "Resultados del fútbol", published = now),
                Item(id = "3", title = "Precio del petróleo", published = now),
                Item(id = "4", title = "Tendencias de diseño gráfico 2026", published = now - 7_200_000),
            )
        )
        assertEquals(2, engine.research(listOf(src)))
        assertEquals(0, engine.research(listOf(src))) // no repite
        val before = engine.current().profile.interests["fotografía"]!!.weight
        engine.feedback("1", useful = true)
        assertTrue(engine.current().profile.interests["fotografía"]!!.weight > before)

        // Persistencia.
        val reloaded = Engine(file) { now }
        assertEquals("diseñadora gráfica", reloaded.current().profile.occupation)
        assertEquals(2, reloaded.current().inbox.size)
    }

    @Test
    fun parsesRssAndClaudeJson() {
        val xml = """<rss><channel><item><title>Hola</title><link>https://x/1</link>
            <description>&lt;b&gt;Texto&lt;/b&gt;</description><pubDate>Mon, 02 Mar 2026 08:00:00 GMT</pubDate></item></channel></rss>"""
        val items = parseFeed(xml.byteInputStream())
        assertEquals("Hola", items.single().title)
        assertEquals("Texto", items.single().summary)
        assertTrue(items.single().published > 0)

        val c = parseClaudeItems("""Aquí: [{"title":"A","url":"https://x/a","topic":"La Fotografía","published":"2026-03-01"}]""", 0)
        assertEquals("fotografía", c.single().topic)
    }

    @Test
    fun twinPromptKnowsTheOwner() {
        val p = Profile(name = "Ana", occupation = "diseñadora")
        p.music.topArtists = listOf("Bad Bunny")
        p.addDiary("hoy fui al gimnasio temprano")
        val sys = twinSystem(p)
        assertTrue(sys.contains("Ana") && sys.contains("diseñadora") && sys.contains("Bad Bunny") && sys.contains("gimnasio"))
    }

    @Test
    fun spotifyAuthUrlUsesPkce() {
        val pkce = SpotifyAuth.newPkce()
        val url = SpotifyAuth.authorizeUrl("abc", pkce)
        assertTrue(url.contains("code_challenge_method=S256") && url.contains("client_id=abc"))
        assertTrue(url.contains("ariacookie%3A%2F%2Fspotify-callback"))
        assertFalse(pkce.verifier.contains("="))
        assertNotNull(pkce.state)
    }
}
