package org.akanework.gramophone.logic.asmr

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.coroutineContext

class AsmrApiException(val status: Int) : IOException("ASMR HTTP $status")

/** Requests are made only to the selected API origin. Tokens never go to artwork or media hosts. */
class AsmrRepository(context: Context,
                     private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    private val prefs = context.applicationContext.getSharedPreferences("asmr_online", Context.MODE_PRIVATE)
    private val sessions = AsmrSessionStore(context.applicationContext)
    var baseUrl: String
        get() = prefs.getString("server", DEFAULT_SERVER)!!
        set(value) {
            val normalized = normalizeServer(value)
            if (normalized != baseUrl) {
                sessions.clear()
                prefs.edit { putString("server", normalized); remove("username") }
            }
        }
    val username: String? get() = prefs.getString("username", null)
    val signedIn: Boolean get() = username != null

    suspend fun works(query: String, order: String, page: Int, subtitle: Boolean, seed: Int,
                      facet: AsmrFacet? = null): AsmrPage<AsmrWork> {
        val path = if (facet != null) {
            require(facet.kind in setOf("tags", "vas", "circles"))
            "${facet.kind}/${Uri.encode(facet.label.id)}/works"
        } else if (query.isBlank()) "works" else "search/${Uri.encode(query.trim())}"
        return workPage(request(path, mapOf("page" to "$page", "pageSize" to "20",
            "order" to order, "sort" to "desc", "subtitle" to if (subtitle) "1" else "0", "seed" to "$seed")), page)
    }

    suspend fun work(id: String): AsmrWork = AsmrWork.fromJson(request("workInfo/${Uri.encode(id)}") as JSONObject)
    suspend fun workDetails(id: String): AsmrWork = AsmrWork.fromJson(request("work/${Uri.encode(id)}") as JSONObject)

    suspend fun files(id: String): List<AsmrFile> {
        val response = request("tracks/${Uri.encode(id)}", mapOf("v" to "2"))
        val array = response as? JSONArray ?: (response as JSONObject).getJSONArray("tracks")
        return AsmrFile.parse(array, baseUrl)
    }

    suspend fun labels(kind: String): List<AsmrLabel> {
        require(kind in setOf("tags", "vas", "circles"))
        val response = request(kind)
        return AsmrWork.labels(response as? JSONArray ?: (response as JSONObject).getJSONArray(kind))
    }

    suspend fun playlists(page: Int): AsmrPage<AsmrPlaylist> {
        val json = request("playlist/get-playlists", mapOf("page" to "$page", "pageSize" to "20", "filterBy" to "all")) as JSONObject
        return playlistPage(json, page)
    }

    suspend fun playlistMemberships(workId: String, page: Int): AsmrPage<AsmrPlaylist> {
        val json = request("playlist/get-work-exist-status-in-my-playlists", mapOf(
            "workID" to workId, "page" to "$page", "pageSize" to "20", "version" to "2")) as JSONObject
        return playlistPage(json, page)
    }

    private fun playlistPage(json: JSONObject, requestedPage: Int): AsmrPage<AsmrPlaylist> {
        val items = json.getJSONArray("playlists")
        val playlists = (0 until items.length()).map { i ->
            val p = items.getJSONObject(i)
            AsmrPlaylist(p.getString("id"), p.optString("name"), p.optInt("works_count"), p.optBoolean("exist"))
        }
        return page(json, playlists, requestedPage)
    }

    suspend fun playlistWorks(id: String, page: Int): AsmrPage<AsmrWork> = workPage(
        request("playlist/get-playlist-works", mapOf("id" to id, "page" to "$page", "pageSize" to "20")), page)

    suspend fun login(name: String, password: String) {
        authenticate("auth/me", name, password)
    }

    suspend fun registrationEnabled(): Boolean =
        (request("auth/reg", authenticated = false) as JSONObject).optBoolean("reg")

    suspend fun register(name: String, password: String) {
        authenticate("auth/reg", name, password)
    }

    private suspend fun authenticate(path: String, name: String, password: String) {
        val origin = baseUrl
        val result = request(path, method = "POST", body = JSONObject()
            .put("name", name.trim()).put("password", password), authenticated = false) as JSONObject
        val token = result.text("token") ?: throw IOException("Missing session token")
        coroutineContext.ensureActive()
        if (origin != baseUrl) throw IOException("Server changed")
        withContext(Dispatchers.IO) { sessions.write(origin, token) }
        prefs.edit { putString("username", name.trim()) }
    }

    suspend fun createPlaylist(name: String, description: String, privacy: Int, work: AsmrWork? = null) {
        require(name.isNotBlank() && privacy in 0..2)
        request("playlist/create-playlist", method = "POST", body = JSONObject().put("name", name.trim())
            .put("description", description).put("privacy", privacy).put("locale", "zh-CN")
            .put("works", JSONArray().apply { work?.let { put(it.code) } }))
    }

    suspend fun setPlaylistMembership(playlistId: String, workId: String, included: Boolean) {
        request("playlist/${if (included) "add" else "remove"}-works-${if (included) "to" else "from"}-playlist",
            method = "POST", body = JSONObject().put("id", playlistId).put("works", JSONArray().put(workId.toLong())))
    }

    suspend fun saveReview(workId: String, review: AsmrReview) {
        require(review.rating in 0.0..5.0 && review.progress in setOf(null, "marked", "listening", "listened", "replay", "postponed"))
        request("review", method = "PUT", body = JSONObject().put("work_id", workId.toLong())
            .put("rating", review.rating.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("review_text", review.text).put("progress", review.progress ?: JSONObject.NULL))
    }

    suspend fun deleteReview(workId: String) {
        request("review", mapOf("work_id" to workId), method = "DELETE")
    }

    fun logout() {
        sessions.clear()
        prefs.edit { remove("username"); remove("website_logout") }
    }

    fun isSaved(id: String): Boolean = prefs.getStringSet("saved", emptySet())!!.contains(id)
    fun save(work: AsmrWork, saved: Boolean) {
        saveId(work.id, saved)
    }
    fun saveId(id: String, saved: Boolean) {
        val ids = prefs.getStringSet("saved", emptySet())!!.toMutableSet()
        if (saved) ids.add(id) else ids.remove(id)
        prefs.edit { putStringSet("saved", ids) }
    }
    fun savedIds(): List<String> = prefs.getStringSet("saved", emptySet())!!.sortedDescending()

    private fun workPage(value: Any, requestedPage: Int): AsmrPage<AsmrWork> {
        val json = value as JSONObject
        val items = json.getJSONArray("works")
        return page(json, (0 until items.length()).map { AsmrWork.fromJson(items.getJSONObject(it)) }, requestedPage)
    }

    private fun <T> page(json: JSONObject, items: List<T>, requestedPage: Int): AsmrPage<T> {
        val p = json.optJSONObject("pagination") ?: json
        val size = p.optInt("pageSize", 20).coerceAtLeast(1)
        val current = p.optInt("currentPage", requestedPage).coerceAtLeast(1)
        val total = p.optInt("totalCount", if (items.size < size) (current - 1) * size + items.size else current * size + 1)
        return AsmrPage(items, current, size, total)
    }

    private suspend fun request(path: String, query: Map<String, String> = emptyMap(), method: String = "GET",
                                body: JSONObject? = null, authenticated: Boolean = true): Any = withContext(Dispatchers.IO) {
        val origin = baseUrl
        val url = Uri.parse("$origin/api/$path").buildUpon().apply {
            query.forEach { (key, value) -> appendQueryParameter(key, value) }
        }.build().toString()
        val connection = openConnection(URL(url))
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Gramophone-ASMR/1.0")
            connection.setRequestProperty("Referer", WEBSITE)
            val token = if (authenticated) sessions.read(origin) else null
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            coroutineContext.ensureActive()
            if (status !in 200..299) {
                if (status == 401 && authenticated && origin == baseUrl && sessions.read(origin) == token) logout()
                throw AsmrApiException(status)
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            coroutineContext.ensureActive()
            when {
                text.trimStart().startsWith("[") -> JSONArray(text)
                text.isBlank() -> JSONObject()
                else -> JSONObject(text)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://api.asmr.one"
        const val WEBSITE = "https://www.asmr.one/"
        val SERVERS = listOf(DEFAULT_SERVER, "https://api.asmr-200.com", "https://api.asmr-100.com", "https://api.asmr-300.com")

        fun normalizeServer(value: String): String {
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty())
            return uri.toASCIIString()
        }
    }
}
