package eu.kanade.tachiyomi.animeextension.es.jkhentai.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class SolidFilesExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("\"downloadUrl\":")) {
                val data = script.data().substringAfter("\"downloadUrl\":").substringBefore(",")
                val url = data.replace("\"", "")
                val videoUrl = url
                val quality = "SolidFiles"
                videoList.add(Video(videoUrl, quality, videoUrl))
            }
        }
        return videoList
    }
}
