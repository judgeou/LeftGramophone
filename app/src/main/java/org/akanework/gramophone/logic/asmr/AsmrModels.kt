package org.akanework.gramophone.logic.asmr

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

data class AsmrLabel(val id: String, val name: String)
data class AsmrFacet(val kind: String, val label: AsmrLabel)

data class AsmrWork(
    val id: String,
    val code: String,
    val title: String,
    val circle: AsmrLabel?,
    val actors: List<AsmrLabel>,
    val tags: List<AsmrLabel>,
    val cover: String?,
    val release: String,
    val duration: Double,
    val rating: Double,
    val review: AsmrReview = AsmrReview(),
) {
    companion object {
        fun fromJson(json: JSONObject): AsmrWork {
            val id = json.get("id").toString()
            return AsmrWork(
                id, json.text("source_id") ?: "RJ${id.padStart(6, '0')}",
                json.optString("title"), json.optJSONObject("circle")?.let(::label),
                labels(json.optJSONArray("vas")), labels(json.optJSONArray("tags")),
                json.text("mainCoverUrl") ?: json.text("coverUrl"),
                json.optString("release"), json.optDouble("duration", 0.0),
                json.optDouble("rate_average_2dp", 0.0),
                AsmrReview(json.optDouble("userRating", 0.0), json.text("review_text").orEmpty(), json.text("progress")),
            )
        }

        fun label(json: JSONObject) = AsmrLabel(json.optString("id"), json.optString("name"))
        fun labels(json: JSONArray?) = (0 until (json?.length() ?: 0)).mapNotNull {
            json?.optJSONObject(it)?.let(::label)
        }
    }
}

data class AsmrPage<T>(val items: List<T>, val page: Int, val pageSize: Int, val total: Int) {
    val hasMore: Boolean get() = items.isNotEmpty() && page.toLong() * pageSize < total
}

data class AsmrReview(val rating: Double = 0.0, val text: String = "", val progress: String? = null)

data class AsmrPlaylist(val id: String, val name: String, val count: Int, val containsWork: Boolean = false)

data class AsmrFile(
    val title: String,
    val path: String,
    val type: String,
    val url: String?,
    val duration: Double,
    val children: List<AsmrFile>,
) {
    val isFolder: Boolean get() = type == "folder" || children.isNotEmpty()
    val isAudio: Boolean get() = !isFolder && url != null && (type == "audio" ||
        title.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS)

    fun audioFiles(): List<AsmrFile> = if (isAudio) listOf(this) else children.flatMap { it.audioFiles() }

    fun toMediaItem(work: AsmrWork, index: Int): MediaItem {
        require(isAudio)
        return MediaItem.Builder()
            .setMediaId("asmr:${work.id}:${Uri.encode(path)}")
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title.substringBeforeLast('.', title))
                .setArtist(work.actors.joinToString(" / ") { it.name }.ifBlank { work.circle?.name.orEmpty() })
                .setAlbumArtist(work.circle?.name)
                .setAlbumTitle(work.title)
                .setArtworkUri(work.cover?.let(Uri::parse))
                .setTrackNumber(index + 1)
                .setExtras(Bundle().apply { putString("asmr_work_id", work.id) })
                .setDurationMs((duration * 1000).toLong().takeIf { it > 0 })
                .setIsBrowsable(false).setIsPlayable(true).build())
            .build()
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "aif", "aiff")

        fun parse(json: JSONArray, baseUrl: String, parent: String = ""): List<AsmrFile> =
            (0 until json.length()).map { i ->
                val node = json.getJSONObject(i)
                val title = node.optString("title")
                val path = if (parent.isBlank()) title else "$parent/$title"
                val rawUrl = node.text("mediaStreamUrl") ?: node.text("mediaDownloadUrl")
                AsmrFile(title, path, node.optString("type"), rawUrl?.let { resolveMediaUrl(baseUrl, it) },
                    node.optDouble("duration", 0.0),
                    node.optJSONArray("children")?.let { parse(it, baseUrl, path) } ?: emptyList())
            }
    }
}

internal fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun MediaItem.asmrWorkId(): String? = mediaMetadata.extras?.getString("asmr_work_id")
    ?: mediaId.split(':').takeIf { it.size >= 3 && it[0] == "asmr" && it[1].all(Char::isDigit) }
        ?.get(1)?.takeIf { it.isNotEmpty() }

/** Never pass javascript:, file:, credentials, or an insecure stream into the player. */
internal fun resolveMediaUrl(baseUrl: String, value: String): String? {
    return try {
        val uri = java.net.URI(baseUrl).resolve(value)
        uri.toString().takeIf {
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }
    } catch (_: IllegalArgumentException) { null }
    catch (_: java.net.URISyntaxException) { null }
}
