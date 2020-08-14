package eu.kanade.tachiyomi.extension.en.mangakomi

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.ArrayList
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Mangakomi : ParsedHttpSource() {

    private val chapterRegEx: Regex = ".*(Chapter)\\s([\\d]+).*".toRegex()
    private val ajaxRequest: String = "/wp-admin/admin-ajax.php"
    private val customPageIterator: String = "mypagenum"

    override val baseUrl: String = "https://mangakomi.com"
    override val lang: String = "en"
    override val name: String = "Mangakomi"
    override val supportsLatest: Boolean = true
    private val rateLimitInterceptor = RateLimitInterceptor(1)
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor).build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
        " (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36"

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", userAgent)
    }

    override fun popularMangaSelector(): String = "div.page-item-detail"
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = "div.c-tabs-item__content"
    override fun chapterListSelector(): String = "ul.version-chap > li.wp-manga-chapter"

    override fun popularMangaNextPageSelector(): String = popularMangaSelector()
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = searchMangaSelector()

    private fun makePaginatedRequest(req: Request): Request {
        if (req.url().queryParameterNames().contains(customPageIterator)) {
            val url: HttpUrl = req.url()
            val pageN: Int = url.queryParameter(customPageIterator)!!.toInt()
            val isSearch: Boolean = url.queryParameterNames().contains("s")
            var metaKey: String = ""

            if (url.queryParameterNames().contains("m_orderby")) {
                metaKey = when (url.queryParameter("m_orderby")) {
                    "latest" -> "_latest_update"
                    "views" -> "_wp_manga_views"
                    else -> ""
                }
            }

            val newUrl = HttpUrl.parse("$baseUrl$ajaxRequest")!!.newBuilder()
            val originalUrl = HttpUrl.parse(url.toString().split("?")[0])!!.newBuilder()
            url.queryParameterNames().forEach { name ->
                if (name != customPageIterator) {
                    originalUrl.addQueryParameter(name, url.queryParameter(name))
                }
            }

            if (pageN == 1) { // do not change anything, just remove "technical params"
                return GET(originalUrl.toString(), req.headers(), req.cacheControl())
            }

            val headers = Headers.Builder().apply {
                add("x-requested-with", "XMLHttpRequest")
                add("origin", baseUrl)
                add("sec-fetch-site", "same-origin")
                add("sec-fetch-mode", "cors")
                add("sec-fetch-dest", "empty")
                add("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                add("referer", originalUrl.toString())
            }

            val formData: HashMap<String, String> = HashMap<String, String>().apply {
                if (isSearch) {

                    this["action"] = "madara_load_more"
                    this["page"] = pageN.toString()
                    this["template"] = "madara-core/content/content-search"
                    this["vars[s]"] = url.queryParameter("s").toString()
                    this["vars[orderby]"] = ""
                    this["vars[paged]"] = "1"
                    this["vars[template]"] = "search"
                    this["vars[meta_query][0][relation]"] = "AND"
                    this["vars[meta_query][relation]"] = "OR"
                    this["vars[post_type]"] = "wp-manga"
                    this["vars[post_status]"] = "publish"
                    this["vars[manga_archives_item_layout]"] = "default"
                } else {
                    this["action"] = "madara_load_more"
                    this["page"] = pageN.toString()
                    this["template"] = "madara-core/content/content-archive"
                    this["vars[paged]"] = "1"
                    this["vars[orderby]"] = "meta_value_num"
                    this["vars[template]"] = "archive"
                    this["vars[sidebar]"] = "full"
                    this["vars[post_type]"] = "wp-manga"
                    this["vars[post_status]"] = "publish"
                    this["vars[meta_key]"] = metaKey
                    this["vars[order]"] = "desc"
                    this["vars[meta_query][relation]"] = "OR"
                    this["vars[manga_archives_item_layout]"] = "default"
                }
            }

            val body: RequestBody = RequestBody.create(
                MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"),
                formData.entries.joinToString("&") { (x, v) ->
                    URLEncoder.encode(x, "utf-8") +
                        "=" +
                        URLEncoder.encode(v, "utf-8")
                }
            )
            return POST(newUrl.toString(), headers.build(), body, CacheControl.FORCE_NETWORK)
        }
        return req
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/?m_orderby=views&$customPageIterator=$page"

        return makePaginatedRequest(
            GET(url, headersBuilder().build())
        )
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga/?m_orderby=latest&$customPageIterator=$page"

        return makePaginatedRequest(
            GET(url, headersBuilder().build())
        )
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val rawq = query.toLowerCase()
        val q = HttpUrl.parse(baseUrl)!!.newBuilder()

        q.addQueryParameter("s", rawq)
        q.addQueryParameter(customPageIterator, page.toString())
        q.addQueryParameter("post_type", "wp-manga")

        return makePaginatedRequest(
            GET(q.toString(), headersBuilder().build(), CacheControl.FORCE_NETWORK)
        )
    }

    override fun chapterFromElement(element: Element): SChapter = throw Exception("Not used")

    private fun getMangaId(manga: SManga): Int {
        val genres: List<String> = manga.genre!!.split(", ")
        if (genres.isNullOrEmpty()) return -1

        try {
            return genres.first().toInt()
        } catch (e: java.lang.Exception) {
            // Do nothing
        }
        return -1
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = manga.url
        val mangaId = getMangaId(manga)

        val headers = Headers.Builder().apply {
            add("dnt", "1")
            add("x-requested-with", "XMLHttpRequest")
            add("user-agent", userAgent)
            add("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            add("origin", baseUrl)
            add("sec-fetch-site", "same-origin")
            add("sec-fetch-mode", "cors")
            add("sec-fetch-dest", "empty")
            add("referer", "$baseUrl$url")
        }
        val body: RequestBody = RequestBody.create(
            MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"),
            "action=manga_get_chapters&manga=$mangaId"
        )

        return POST("$baseUrl$ajaxRequest", headers.build(), body, CacheControl.FORCE_NETWORK)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return if (manga.status != SManga.LICENSED && getMangaId(manga) != -1) {
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
        val root = element.select("li")
        val url_element = root.select("a")

        chapter.name = parseChapterName(root.select("a").text())
        try {
            chapter.chapter_number = chapter.name.split(" ")[1].toFloat()
        } catch (e: java.lang.Exception) {
            chapter.chapter_number = -1.0F
        }
        chapter.setUrlWithoutDomain(url_element.attr("href").toString())
        chapter.date_upload = root.select("span.chapter-release-date > i").text()
            .let {
                try {
                    SimpleDateFormat("MM/dd/yyyy", Locale.US).parse(it).time
                } catch (e: ParseException) {
                    0
                }
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

        val mangaId = document.select("input.rating-post-id").attr("value")
        val table = document.select("div.tab-summary")
        val genres = ArrayList<String>()
        val authors = ArrayList<String>()

        // ugh, it is hack to push mangaId for Chapters retrieval later
        genres.add(mangaId)
        table.select("div.genres-content > a").forEach { x ->
            genres.add(x.text())
        }
        table.select("div.author-content > a").forEach { x ->
            authors.add(x.text())
        }

        manga.title = document.select("div.post-title > h1").text()
        manga.status = parseStatus(table.select("div.post-status > div:nth-child(2) > div.summary-content").text())
        manga.thumbnail_url = document.select("div.summary_image > a > img")
            .attr("data-src").toString()
        manga.description = document.select("div.description-summary > div > p:nth-child(1)").text()
        manga.author = authors.joinToString()
        manga.genre = genres.joinToString()

        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        val manga_item = element.select("div.item-summary > div.post-title > h3.h5 > a")

        manga.title = manga_item.text()
        manga.setUrlWithoutDomain(manga_item.attr("href").toString())
        manga.thumbnail_url = element.select("div.item-thumb > a > img").attr("src").toString()

        return manga
    }

    override fun popularMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val item = element.select("div > div.tab-thumb > a >  img")
        val sub_item = element.select("div > div.tab-summary > div.post-title > h3.h4 > a")

        manga.thumbnail_url = item.attr("src").toString()
        manga.title = sub_item.text()
        manga.setUrlWithoutDomain(sub_item.attr("href").toString())
        return manga
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val imgHeader = Headers.Builder().apply {
            add("User-Agent", userAgent)
            add("sec-fetch-dest", "image")
            add("sec-fetch-mode", "no-cors")
            add("sec-fetch-site", "same-site")
            add("Referer", baseUrl)
        }.build()
        return GET(page.imageUrl!!, imgHeader)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document: Document = response.asJsoup()
        val refUrl = response.request().url().toString()
        var i = 0
        return document.select("div.reading-content > div > img.wp-manga-chapter-img").map { el ->
            val imgSrc: String = if (el.hasAttr("data-lazy-src")) {
                el.attr("data-lazy-src").toString()
            } else {
                el.attr("src").toString()
            }

            Page(i++, refUrl, imgSrc)
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }
}
