package eu.kanade.tachiyomi.extension.en.newmanganelos

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlin.collections.ArrayList
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Newmanganelos : ParsedHttpSource() {

    private val chapterRegEx: Regex = ".*(Chapter)\\s([\\d.]+).*".toRegex()

    private val protocol: String = "http:"
    override val baseUrl: String = "$protocol//manganelos.com"
    override val lang: String = "en"
    override val name: String = "NewManganelos"
    override val supportsLatest: Boolean = true
    private val rateLimitInterceptor = RateLimitInterceptor(2)
    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor).build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
        " (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36"

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", userAgent)
    }

    override fun popularMangaNextPageSelector(): String = "ul.pagination > li > a[rel=next]"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    override fun popularMangaSelector(): String = "div.cate-manga > div.col-md-6"
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun chapterListSelector(): String = "div.chapter-list:nth-child(1) > ul > li.row > div.chapter > h4 > a"

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/popular-manga"
        else "$baseUrl/popular-manga/?page=$page"

        return GET(url, headersBuilder().build())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/latest-manga"
        else "$baseUrl/latest-manga?page=$page"

        return GET(url, headersBuilder().build())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val rawq = query.toLowerCase()
        val q = HttpUrl.parse("$baseUrl/search")!!.newBuilder()

        q.addQueryParameter("q", rawq)
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
        return document.select(chapterListSelector())
            .map { chapterFromElement(it, manga) }
            .distinctBy { Pair(it.name, it.chapter_number) }
            .sortedBy { it.chapter_number }
            .reversed()
    }

    private fun chapterFromElement(element: Element, manga: SManga): SChapter {
        val chapter = SChapter.create()

        chapter.name = parseChapterName(element.text())
        try {
            chapter.chapter_number = parseChapterName(chapter.name).split(" ")[1].toFloat()
        } catch (e: java.lang.Exception) {
            chapter.chapter_number = 0.0F
        }
        chapter.setUrlWithoutDomain(element.attr("href").toString())
        chapter.date_upload = 0
        return chapter
    }

    private fun parseStatus(status: String): Int {
        return when (status.trim().toLowerCase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun getSubString(text: String, start: String, end: String): String {
        var startPos = text.indexOf(start)
        if (startPos == -1) return ""
        startPos += start.length

        var endPos = text.indexOf(end, startPos)
        if (endPos == -1) return ""
        endPos -= 1

        return text.subSequence(startPos, endPos).toString()
    }

    private fun fixThumbURL(thumbUrl: String): String {
        if (thumbUrl.startsWith("//")) {
            return "$protocol$thumbUrl"
        }
        return thumbUrl
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val root = document.select("div.manga-detail")
        val table = document.select("div.media-body")
        val tableBody = table.select("p.description-update").toString()
        val genres = ArrayList<String>()
        val authors = ArrayList<String>()

        table.select("p.description-update > a").text()
            .split("; ").forEach { x ->
                genres.add(x.trim())
            }

        getSubString(tableBody, "<span>Author(s): </span>", "<br>").split(";")
            .forEach { x ->
                authors.add(x.trim())
            }

        manga.title = root.select("h1.title-manga").text()
        manga.status = parseStatus(getSubString(tableBody, "<span>Status: </span>", "<br>"))
        manga.thumbnail_url = fixThumbURL(document.select("div.manga-detail > div.cover-detail > img")
            .attr("src").toString())
        manga.description = document.select("div.manga-content > p").text()
        manga.author = authors.joinToString()
        manga.genre = genres.joinToString()

        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        val mangaItem = element.select("div.media")
        val mangaLink = mangaItem.select("div.media-body > a")

        manga.thumbnail_url = fixThumbURL(mangaItem.select("div.cover-manga > a > img")
            .attr("src").toString())
        manga.title = mangaLink.attr("title").toString()
        manga.setUrlWithoutDomain(mangaLink.attr("href").toString())

        return manga
    }

    override fun popularMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val imgHeader = Headers.Builder().apply {
            add("User-Agent", userAgent)
            add("sec-fetch-dest", "image")
            add("sec-fetch-mode", "no-cors")
            add("sec-fetch-site", "cross-site")
            add("dnt", "1")
            add("Referer", page.url)
        }.build()
        return GET(page.imageUrl!!, imgHeader)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document: Document = response.asJsoup()
        val refUrl = response.request().url().toString()
        var i = 0
        val chapters = document.select("p[id=arraydata]").text()
        return chapters.split(",").map { el ->
            Page(i++, refUrl, el)
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }
}
