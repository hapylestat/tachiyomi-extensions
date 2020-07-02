package eu.kanade.tachiyomi.extension.en.manganelo

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.ArrayList
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Manganelo : ParsedHttpSource() {

    private val chapterRegEx: Regex = ".*(Chapter)\\s([\\d]+).*".toRegex()
    override val baseUrl: String = "https://manganelo.com"
    override val lang: String = "en"
    override val name: String = "Manganelo"
    override val supportsLatest: Boolean = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
        " (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36"

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", userAgent)
    }

    override fun popularMangaNextPageSelector(): String = "a.page-select"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    override fun popularMangaSelector(): String = "div.content-genres-item"
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = "div.panel-search-story > div.search-story-item"
    override fun chapterListSelector(): String = "ul.row-content-chapter > li"

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/genre-all?type=topview"
        else "$baseUrl/genre-all/$page?type=topview"

        return GET(url, headersBuilder().build())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/genre-all"
        else "$baseUrl/genre-all/$page"

        return GET(url, headersBuilder().build())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val rawq = query.replace(" ", "_").toLowerCase()
        val q = HttpUrl.parse("$baseUrl/search/story/$rawq")!!.newBuilder()

        q.addQueryParameter("page", page.toString())
        return GET(q.toString(), headersBuilder().build(), CacheControl.FORCE_NETWORK)
    }

    override fun chapterFromElement(element: Element): SChapter = throw Exception("Not used")

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return if (manga.status != SManga.LICENSED) {
            client.newCall(chapterListRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    chapterListParse(response, manga)
                }
        } else {
            Observable.error(java.lang.Exception("Licensed - No chapters to show"))
        }
    }

    private fun parseChapterName(name: String): String {
        return chapterRegEx.find(name)!!.groupValues.subList(1, 3).joinToString(" ")
    }

    private fun chapterListParse(response: Response, manga: SManga): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).map { chapterFromElement(it, manga) }
    }

    private fun chapterFromElement(element: Element, manga: SManga): SChapter {
        val chapter = SChapter.create()
        val root = element.select("li")
        val url_element = root.select("a.chapter-name")

        chapter.name = url_element.text()
        chapter.name.split(Regex(""))
        try {
            chapter.chapter_number = parseChapterName(chapter.name).split(" ")[1].toFloat()
        } catch (e: java.lang.Exception) {
            Log.e("wow", "dfa")
        }
        chapter.setUrlWithoutDomain(url_element.attr("href").toString())
        chapter.date_upload = root.select("span.chapter-time")
            .attr("title").toString().let {
                SimpleDateFormat("MMM dd,yyyy kk:mm", Locale.US).parse(it).time
            }
        return chapter
    }

    private fun parseStatus(status: String): Int {
        return when (status.trim().toLowerCase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val root = document.select("div.story-info-right")
        val table = document.select("div.story-info-right > table > tbody")
        val genres = ArrayList<String>()
        val authors = ArrayList<String>()

        table.select("tr:has(td > i.info-genres) > td.table-value > a").forEach { x ->
            genres.add(x.text())
        }
        table.select("tr:has(td > i.info-author) > td.table-value > a").forEach { x ->
            authors.add(x.text())
        }

        manga.title = root.select("li > h1").text()
        manga.status = parseStatus(table.select("tr:has(td > i.info-status) > td.table-value").text())
        manga.thumbnail_url = document.select("div.story-info-left > span > img")
            .attr("src").toString()
        manga.description = document.select("div.panel-story-info-description").text()
        manga.author = authors.joinToString()
        manga.genre = genres.joinToString()

        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        val manga_item = element.select("a.genres-item-img")

        manga.title = manga_item.attr("title").toString()
        manga.setUrlWithoutDomain(manga_item.attr("href").toString())
        manga.thumbnail_url = manga_item.select("img").attr("src").toString()

        return manga
    }

    override fun popularMangaFromElement(element: Element): SManga {
        return latestUpdatesFromElement(element)
    }

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val item = element.select("div.search-story-item")
        val sub_item = item.select("div.item-right > h3")

        manga.thumbnail_url = item.select("a.item-img > img").attr("src").toString()
        manga.title = sub_item.select("a.item-title").text()
        manga.setUrlWithoutDomain(sub_item.select("a.item-title")
            .attr("href").toString())
        return manga
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val imgHeader = Headers.Builder().apply {
            add("User-Agent", userAgent)
            add("sec-fetch-dest", "image")
            add("sec-fetch-mode", "no-cors")
            add("sec-fetch-site", "cross-site")
            add("Referer", page.url)
        }.build()
        return GET(page.imageUrl!!, imgHeader)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document: Document = response.asJsoup()
        val refUrl = response.request().url().toString()
        var i = 0
        return document.select("div.container-chapter-reader > img").map { el ->
            Page(i++, refUrl, el.attr("src").toString())
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }
}
