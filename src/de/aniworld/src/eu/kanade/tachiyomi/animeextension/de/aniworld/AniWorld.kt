package eu.kanade.tachiyomi.animeextension.de.aniworld

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.aniworld.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.de.aniworld.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AniWorld : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AniWorld"

    override val baseUrl = "https://aniworld.to"

    private val baseLogin by lazy { AWConstants.getPrefBaseLogin(preferences) }
    private val basePassword by lazy { AWConstants.getPrefBasePassword(preferences) }

    override val lang = "de"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val authClient = network.client.newBuilder()
        .addInterceptor(AniWorldInterceptor(client, preferences))
        .build()

    private val json: Json by injectLazy()

    val context = Injekt.get<Application>()

    // ===== POPULAR ANIME =====
    override fun popularAnimeSelector(): String = "div.seriesListContainer div"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/beliebte-animes")

    override fun popularAnimeFromElement(element: Element): SAnime {
        context
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img").attr("data-src")
        anime.title = element.selectFirst("h3").text()
        return anime
    }

    // ===== LATEST ANIME =====
    override fun latestUpdatesSelector(): String = "div.seriesListContainer div"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neu")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img").attr("data-src")
        anime.title = element.selectFirst("h3").text()
        return anime
    }

    // ===== SEARCH =====

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        POST("$baseUrl/ajax/search", body = FormBody.Builder().add("keyword", query).build())

    override fun searchAnimeSelector() = throw UnsupportedOperationException("Not used.")

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException("Not used.")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body!!.string()
        val results = json.decodeFromString<JsonArray>(body)
        val animes = results.filter {
            val link = it.jsonObject["link"]!!.jsonPrimitive.content
            link.startsWith("/anime/stream/") &&
                link.count { c -> c == '/' } == 3
        }.map {
            animeFromSearch(it.jsonObject)
        }
        return AnimesPage(animes, false)
    }

    private fun animeFromSearch(result: JsonObject): SAnime {
        val anime = SAnime.create()
        val title = result["title"]!!.jsonPrimitive.content
        val link = result["link"]!!.jsonPrimitive.content
        anime.title = title
        anime.url = link
        return anime
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException("Not used.")

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.series-title h1 span").text()
        anime.thumbnail_url = baseUrl +
            document.selectFirst("div.seriesCoverBox img").attr("data-src")
        anime.genre = document.select("div.genres ul li").joinToString { it.text() }
        anime.description = document.selectFirst("p.seri_des").attr("data-full-description")
        document.selectFirst("div.cast li:contains(Produzent:) ul")?.let {
            val author = it.select("li").joinToString { li -> li.text() }
            anime.author = author
        }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ===== EPISODE =====
    override fun episodeListSelector() = throw UnsupportedOperationException("Not used.")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seasons = document.selectFirst("#stream > ul:nth-child(1)")
        val episodeList = mutableListOf<SEpisode>()
        val episodes = document.select("table.seasonEpisodesList > tbody > tr")
        episodeList.addAll(episodes.map { episodeFromElement(it) })

        if (seasons.childrenSize() > 2) {
            val nextSeasonElements = seasons.select("li a").prevAll("li a.active")
            val prevSeasonElements = seasons.select("li a").nextAll("li a.active")
            nextSeasonElements.forEach { season ->
                val seasonDocument =
                    client.newCall(GET(baseUrl + season.attr("href"))).execute().asJsoup()
                val seasonEpisodes = seasonDocument.select("table.seasonEpisodesList > tbody > tr")
                episodeList.addAll(seasonEpisodes.map { episodeFromElement(it) })
            }
            prevSeasonElements.forEach { season ->
                val seasonDocument =
                    client.newCall(GET(baseUrl + season.attr("href"))).execute().asJsoup()
                val seasonEpisodes = seasonDocument.select("table.seasonEpisodesList > tbody > tr")
                episodeList.addAll(0, seasonEpisodes.map { episodeFromElement(it) })
            }
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val titleElement = element.selectFirst("td.seasonEpisodeTitle a")
        val episodeHeaderElement = element.selectFirst("td:nth-child(1)")
        episode.name = episodeHeaderElement.text() + ": " + titleElement.text()
        val numberElement = episodeHeaderElement.selectFirst("meta")
        episode.episode_number = numberElement.attr("content").toFloat()
        episode.url = titleElement.attr("href")
        return episode
    }

    // ===== VIDEO SOURCES =====
    override fun videoListSelector() = throw UnsupportedOperationException("Not used.")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val gerSubs = getRedirectLinks(document, AWConstants.KEY_GER_SUB)
        val gerDubs = getRedirectLinks(document, AWConstants.KEY_GER_DUB)
        val engSubs = getRedirectLinks(document, AWConstants.KEY_ENG_SUB)
        val videoList = mutableListOf<Video>()
        gerSubs.forEach {
            val redirect = baseUrl + it.selectFirst("a.watchEpisode").attr("href")
            val token = getSourcesLink(redirect) ?: return@forEach
            Log.i("bruh", "$redirect?token=$token")
        }
        return videoList
    }

    private fun getRedirectLinks(document: Document, key: Int): List<Element> {
        val hosterSelection = preferences.getStringSet(AWConstants.HOSTER_SELECTION, null)
        val selector = "li[class*=episodeLink][data-lang-key="
        return document.select("$selector$key]")
            .filter { hosterSelection?.contains(it.select("div > a > h4").text()) == true }
    }

    private fun getSourcesLink(url: String): String? {
        val referer = Headers.headersOf("referer", baseUrl)
        val response = authClient
            .newCall(GET(url, referer))
            .execute()
        val hosterLink = response.request.url.toString()
        Log.i("bruh", hosterLink)
        val html = response.body!!.string()
        val hosterName = when (hosterLink) {
            "Doodstream" -> AWConstants.NAME_DOOD
            "Streamtape" -> AWConstants.NAME_STAPE
            else -> null
        } ?: return null
        return "key"
    }

    private fun getVidLink(document: Document): String {
        val b64html = document.selectFirst(".c36 > script").data().substringAfter("atob('").substringBefore("');")
        val decodedHtml = Base64.decode(b64html, Base64.DEFAULT).decodeToString()
        return baseUrl + decodedHtml.substringAfter("src=\"").substringBefore('"')
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used.")

    private fun videoFromElement(element: Element, document: Document): Video? {
        val hosterLinkName = element.attr("href").substringAfter("/folge-").substringAfter('/')
        val hosterName = when (hosterLinkName) {
            "DODO" -> AWConstants.NAME_DOOD
            "Streamtape" -> AWConstants.NAME_STAPE
            else -> null
        } ?: return null

        val lang =
            when (element.selectFirst(".languagehoster img").attr("src").substringAfter("/img/").substringBefore(".")) {
                "de" -> AWConstants.LANG_GER_DUB
                "jap" -> AWConstants.LANG_GER_SUB
                "en" -> AWConstants.LANG_ENG_SUB
                else -> "Unknown"
            }

        val link: String = when {
            // If video link of loaded page is already the right one
            element.selectFirst(".host").hasClass("hoston") -> getVidLink(document)
            // If video link of loaded page is not the right one, get url for the right page and extract video link
            else -> {
                val response = client.newCall(GET("$baseUrl${element.attr("href")}")).execute()
                getVidLink(response.asJsoup())
            }
        }

        val quality = "$hosterName, $lang"
        val hosterSelection = preferences.getStringSet(AWConstants.HOSTER_SELECTION, null)
        when {
            hosterName == AWConstants.NAME_DOOD && hosterSelection?.contains(AWConstants.NAME_DOOD) == true -> {
                val video = try {
                    DoodExtractor(client).videoFromUrl(link, quality)
                } catch (e: Exception) {
                    null
                }
                if (video != null) {
                    return video
                }
            }
            hosterName == AWConstants.NAME_STAPE && hosterSelection?.contains(AWConstants.NAME_STAPE) == true -> {
                val video = StreamTapeExtractor(client).videoFromUrl(link, quality)
                if (video != null) {
                    return video
                }
            }
        }
        return null
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(AWConstants.PREFERRED_HOSTER, null)
        val subPreference = preferences.getString(AWConstants.PREFERRED_LANG, "Sub")!!
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else otherList += this
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        for (video in otherList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }

        return newList
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used.")

    // ===== PREFERENCES ======
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = AWConstants.PREFERRED_HOSTER
            title = "Standard-Hoster"
            entries = AWConstants.HOSTER_NAMES
            entryValues = AWConstants.HOSTER_URLS
            setDefaultValue(AWConstants.URL_STAPE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = AWConstants.PREFERRED_LANG
            title = "Bevorzugte Sprache"
            entries = AWConstants.LANGS
            entryValues = AWConstants.LANGS
            setDefaultValue(AWConstants.LANG_GER_SUB)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hosterSelection = MultiSelectListPreference(screen.context).apply {
            key = AWConstants.HOSTER_SELECTION
            title = "Hoster auswählen"
            entries = AWConstants.HOSTER_NAMES
            entryValues = AWConstants.HOSTER_NAMES
            setDefaultValue(AWConstants.HOSTER_NAMES.toSet())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(screen.editTextPreference(AWConstants.LOGIN_TITLE, AWConstants.LOGIN_DEFAULT, baseLogin, false, ""))
        screen.addPreference(screen.editTextPreference(AWConstants.PASSWORD_TITLE, AWConstants.PASSWORD_DEFAULT, basePassword, true, ""))
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(hosterSelection)
    }

    private fun PreferenceScreen.editTextPreference(title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String): EditTextPreference {
        return EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value.ifEmpty { placeholder }
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Starte Aniyomi neu, um die Einstellungen zu übernehmen.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    Log.e("Anicloud", "Fehler beim festlegen der Einstellung.", e)
                    false
                }
            }
        }
    }
}
