package com.aria.cookie.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Cualquier sitio donde Cookie puede investigar. */
interface Source {
    val name: String
    fun search(profile: Profile, topics: List<Topic>): List<Item>
}

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/** Busca en Google News RSS por cada tema (no necesita clave). */
class RssSource(
    private val lang: String = "es-419",
    private val country: String = "MX",
    private val perTopic: Int = 5,
    private val searchUrl: ((String) -> String)? = null,
) : Source {
    override val name = "rss"

    private fun urlFor(q: String): String = searchUrl?.invoke(q)
        ?: "https://news.google.com/rss/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$q when:7d")
            .addQueryParameter("hl", lang)
            .addQueryParameter("gl", country)
            .addQueryParameter("ceid", "$country:${lang.substringBefore('-')}")
            .build().toString()

    override fun search(profile: Profile, topics: List<Topic>): List<Item> {
        val out = mutableListOf<Item>()
        val errors = mutableListOf<String>()
        for (t in topics) {
            try {
                val req = Request.Builder().url(urlFor(t.name)).header("User-Agent", "aria-cookie/1.0").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    parseFeed(resp.body!!.byteStream()).take(perTopic).forEach { it.topic = t.name; out += it }
                }
            } catch (e: Exception) {
                errors += "${t.name}: ${e.message}"
            }
        }
        if (out.isEmpty() && errors.isNotEmpty()) error("rss: ${errors.joinToString("; ")}")
        return out
    }
}

/** Interpreta RSS 2.0 o Atom. */
fun parseFeed(input: InputStream): List<Item> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val doc = factory.newDocumentBuilder().parse(input)
    val out = mutableListOf<Item>()
    fun Element.text(tag: String): String = getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()

    val items = doc.getElementsByTagName("item")
    for (i in 0 until items.length) {
        val e = items.item(i) as Element
        val link = e.text("link")
        val title = e.text("title")
        out += Item(
            id = itemId(link, title), title = title, url = link,
            summary = cleanHtml(e.text("description")), source = e.text("source"),
            published = parseDate(e.text("pubDate")),
        )
    }
    val entries = doc.getElementsByTagName("entry")
    for (i in 0 until entries.length) {
        val e = entries.item(i) as Element
        val link = (e.getElementsByTagName("link").item(0) as? Element)?.getAttribute("href").orEmpty()
        val title = e.text("title")
        out += Item(
            id = itemId(link, title), title = title, url = link,
            summary = cleanHtml(e.text("summary")), published = parseDate(e.text("updated")),
        )
    }
    return out
}

private fun cleanHtml(s: String): String {
    val text = s.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    return if (text.length > 280) text.take(277) + "..." else text
}

private fun parseDate(s: String): Long {
    if (s.isBlank()) return 0
    return runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .getOrDefault(0)
}

/** Devuelve siempre los mismos ítems (pruebas / demo sin conexión). */
class StaticSource(private val items: List<Item>) : Source {
    override val name = "static"
    override fun search(profile: Profile, topics: List<Topic>) = items.map { it.copy() }
}
