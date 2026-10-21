package org.akanework.gramophone

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.akanework.gramophone.logic.asmr.*
import org.akanework.gramophone.ui.asmr.AsmrCover
import org.akanework.gramophone.ui.asmr.AsmrViewModel
import org.akanework.gramophone.ui.GramophoneTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AsmrOnlineTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("asmr_online", 0).edit().clear().commit()
    }

    @Test fun coverPlaceholderRendersBeforeArtworkIsAvailable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_Gramophone)
            val work = AsmrWork.fromJson(JSONObject("""{"id":1234,"title":"Artwork not loaded"}"""))
            val compose = ComposeView(activity).apply {
                setContent { GramophoneTheme(pureDark = false) { AsmrCover(work, Modifier) } }
            }
            activity.setContentView(compose)
            compose.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            compose.layout(0, 0, 400, 400)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(compose.width > 0)
            assertTrue(compose.childCount > 0)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nestedFoldersPreservePathsAndExcludeAttachmentsFromAudioQueue() {
        val files = AsmrFile.parse(JSONArray("""[
          {"title":"WAV","type":"folder","children":[
            {"title":"01.wav","type":"audio","duration":12.5,"mediaStreamUrl":"/api/media/stream/a"},
            {"title":"cover.jpg","type":"image","mediaDownloadUrl":"https://cdn.example/cover.jpg"},
            {"title":"subtitle.lrc","type":"text","mediaDownloadUrl":"https://cdn.example/track.lrc"},
            {"title":"02.wav","mediaStreamUrl":"https://cdn.example/b"}
          ]},
          {"title":"MP3","type":"folder","children":[
            {"title":"01.mp3","type":"audio","mediaStreamUrl":"https://cdn.example/c"}
          ]}
        ]"""), "https://api.example")
        assertEquals(listOf("WAV/01.wav", "WAV/02.wav"), files[0].audioFiles().map { it.path })
        assertEquals("https://api.example/api/media/stream/a", files[0].audioFiles()[0].url)
        assertEquals(3, files.flatMap { it.audioFiles() }.size)
        assertFalse(files[0].children[1].isAudio)
    }

    @Test fun invalidStreamDoesNotPreventValidTracksFromLoading() {
        val files = AsmrFile.parse(JSONArray("""[
          {"title":"bad.mp3","type":"audio","mediaStreamUrl":"javascript:alert(1)"},
          {"title":"invalid.mp3","type":"audio","mediaStreamUrl":"https://bad host/a"},
          {"title":"good.mp3","type":"audio","mediaStreamUrl":"https://cdn.example/good"}
        ]"""), "https://api.example")
        assertEquals(listOf("good.mp3"), files.flatMap { it.audioFiles() }.map { it.title })
    }

    @Test fun mediaItemsCarryStreamAndMetadataToTheExistingPlayer() {
        val work = AsmrWork.fromJson(JSONObject("""{
          "id":1234,"source_id":"RJ01001234","title":"Test work",
          "circle":{"id":1,"name":"Circle"},"vas":[{"id":"voice","name":"Voice"}],
          "mainCoverUrl":"https://cdn.example/cover.jpg"
        }"""))
        val file = AsmrFile("01.wav", "WAV/01.wav", "audio", "https://cdn.example/01", 12.5, emptyList())
        val item = file.toMediaItem(work, 0)
        assertEquals("1234", work.id)
        assertEquals("RJ01001234", work.code)
        assertEquals("https://cdn.example/01", item.localConfiguration?.uri.toString())
        assertTrue(item.mediaId.startsWith("asmr:1234:"))
        assertEquals("1234", item.asmrWorkId())
        assertEquals("01", item.mediaMetadata.title)
        assertEquals("Voice", item.mediaMetadata.artist)
        assertEquals("Circle", item.mediaMetadata.albumArtist)
        assertEquals("Test work", item.mediaMetadata.albumTitle)
        assertEquals(12_500L, item.mediaMetadata.durationMs)
        assertEquals(1, item.mediaMetadata.trackNumber)
        assertEquals(true, item.mediaMetadata.isPlayable)
    }

    @Test fun sourceCodeFallbackDoesNotTruncateEightDigitIds() {
        assertEquals("RJ12345678", AsmrWork.fromJson(JSONObject("""{"id":12345678,"title":"work"}""")).code)
        assertEquals("RJ001234", AsmrWork.fromJson(JSONObject("""{"id":1234,"title":"work"}""")).code)
    }

    @Test fun streamUrlsRequireHttpsWithoutEmbeddedCredentials() {
        assertNull(resolveMediaUrl("https://api.example", "file:///storage/file.wav"))
        assertNull(resolveMediaUrl("https://api.example", "http://cdn.example/track"))
        assertNull(resolveMediaUrl("https://api.example", "https://user:pass@cdn.example/track"))
        assertEquals("https://cdn.example/track?key=abc", resolveMediaUrl("https://api.example", "//cdn.example/track?key=abc"))
    }

    @Test fun apiOriginsCannotContainCredentialOrPathOverrides() {
        assertEquals("https://api.example", AsmrRepository.normalizeServer(" https://api.example/ "))
        listOf("http://api.example", "https://api.example/api", "https://user:pass@api.example",
            "https://api.example?token=x", "https://api.example#fragment", "file:///tmp").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { AsmrRepository.normalizeServer(value) }
        }
    }

    @Test fun pagingDoesNotRequestAnExtraPageForExactMultiplesOrEmptyPages() {
        assertFalse(AsmrPage(listOf(1, 2), 2, 2, 4).hasMore)
        assertFalse(AsmrPage(emptyList<Int>(), 1, 20, 100).hasMore)
        assertTrue(AsmrPage(listOf(1, 2), 1, 2, 4).hasMore)
    }

    @Test fun searchEncodesAKeywordAsOnePathSegmentAndUsesServerPagination() = runBlocking {
        lateinit var captured: FakeConnection
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 200, """{
          "works":[{"id":1,"title":"Work"}],
          "pagination":{"currentPage":2,"pageSize":40,"totalCount":80}
        }""").also { captured = it } }
        val result = repository.works("\$tag:1\$ 耳/ASMR?#", "release", 2, true, 7)
        assertEquals("/api/search/%24tag%3A1%24%20%E8%80%B3%2FASMR%3F%23", captured.url.toURI().rawPath)
        assertTrue(captured.url.query.contains("subtitle=1"))
        assertEquals(40, result.pageSize)
        assertFalse(result.hasMore)
        assertFalse(captured.instanceFollowRedirects)
        assertNull(captured.getRequestProperty("Authorization"))
        assertEquals(AsmrRepository.WEBSITE, captured.getRequestProperty("Referer"))
        assertTrue(captured.disconnected)
    }

    @Test fun serverErrorsAreSurfacedAndConnectionIsClosed() {
        val connection = FakeConnection(URL(AsmrRepository.DEFAULT_SERVER), 429, "{}")
        val repository = AsmrRepository(context) { connection }
        val exception = assertThrows(AsmrApiException::class.java) {
            runBlocking { repository.works("", "release", 1, false, 7) }
        }
        assertEquals(429, exception.status)
        assertTrue(connection.disconnected)
    }

    @Test fun metadataFiltersUseTheExactLabelRouteInsteadOfAnUnsupportedKeyword() = runBlocking {
        lateinit var captured: FakeConnection
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 200,
            """{"works":[],"pagination":{"currentPage":1,"pageSize":20,"totalCount":0}}""")
            .also { captured = it } }
        repository.works("ASMR", "release", 1, false, 7, AsmrFacet("tags", AsmrLabel("497", "ASMR")))
        assertEquals("/api/tags/497/works", captured.url.path)
    }

    @Test fun savedWorksPersistAndAreExplicitlySeparateFromOnlinePlaylists() {
        val work = AsmrWork.fromJson(JSONObject("""{"id":1234,"title":"work"}"""))
        AsmrRepository(context).save(work, true)
        val repository = AsmrRepository(context)
        assertTrue(repository.isSaved("1234"))
        repository.save(work, false)
        assertFalse(repository.isSaved("1234"))
    }

    @Test fun nativePlaylistPickerPreservesServerMembershipAndPaging() = runBlocking {
        lateinit var captured: FakeConnection
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 200, """{
          "playlists":[{"id":"liked","name":"__SYS_PLAYLIST_LIKED","works_count":3,"exist":true},
            {"id":"later","name":"Later","works_count":0,"exist":false}],
          "pagination":{"currentPage":1,"pageSize":2,"totalCount":3}
        }""").also { captured = it } }
        val result = repository.playlistMemberships("1638386", 1)
        assertEquals("/api/playlist/get-work-exist-status-in-my-playlists", captured.url.path)
        assertTrue(captured.url.query.contains("workID=1638386"))
        assertTrue(captured.url.query.contains("version=2"))
        assertTrue(result.items[0].containsWork)
        assertFalse(result.items[1].containsWork)
        assertTrue(result.hasMore)
    }

    @Test fun nativePlaylistEditsUseWorkIdsAndPrivateCreationUsesSourceCodes() = runBlocking {
        val requests = mutableListOf<FakeConnection>()
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 200, "{}").also(requests::add) }
        val work = AsmrWork.fromJson(JSONObject("""{"id":1638386,"source_id":"RJ01638386","title":"Work"}"""))
        repository.createPlaylist(" My list ", "Description", 0, work)
        repository.setPlaylistMembership("my-list", work.id, true)
        repository.setPlaylistMembership("my-list", work.id, false)
        assertEquals(listOf("/api/playlist/create-playlist", "/api/playlist/add-works-to-playlist",
            "/api/playlist/remove-works-from-playlist"), requests.map { it.url.path })
        assertTrue(requests.all { it.requestMethod == "POST" && !it.instanceFollowRedirects && it.disconnected })
        val creation = JSONObject(requests[0].output.toString("UTF-8"))
        assertEquals("My list", creation.getString("name"))
        assertEquals(0, creation.getInt("privacy"))
        assertEquals("RJ01638386", creation.getJSONArray("works").getString(0))
        val membership = JSONObject(requests[1].output.toString("UTF-8"))
        assertEquals("my-list", membership.getString("id"))
        assertEquals(1638386L, membership.getJSONArray("works").getLong(0))
    }

    @Test fun progressUpdatesKeepReviewTextAndUnratedIsNullRatherThanZero() = runBlocking {
        val requests = mutableListOf<FakeConnection>()
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 204, "").also(requests::add) }
        repository.saveReview("1638386", AsmrReview(0.0, "My existing review", "listening"))
        repository.deleteReview("1638386")
        val saved = JSONObject(requests[0].output.toString("UTF-8"))
        assertEquals("PUT", requests[0].requestMethod)
        assertEquals(1638386L, saved.getLong("work_id"))
        assertTrue(saved.isNull("rating"))
        assertEquals("My existing review", saved.getString("review_text"))
        assertEquals("listening", saved.getString("progress"))
        assertEquals("DELETE", requests[1].requestMethod)
        assertEquals("work_id=1638386", requests[1].url.query)
    }

    @Test fun detailedWorkIncludesTheCurrentAccountReviewWithoutTreatingNullAsText() = runBlocking {
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 200, """{
          "id":1638386,"title":"Work","userRating":4.5,"review_text":"Review","progress":"listened"
        }""") }
        val work = repository.workDetails("1638386")
        assertEquals(AsmrReview(4.5, "Review", "listened"), work.review)
        assertEquals(AsmrReview(), AsmrWork.fromJson(JSONObject("""{
          "id":1,"title":"Guest","userRating":null,"review_text":null,"progress":null
        }""")).review)
    }

    @Test fun registrationHonorsServerAvailabilityAndDoesNotPersistRejectedCredentials() = runBlocking {
        val requests = mutableListOf<FakeConnection>()
        val repository = AsmrRepository(context) { url -> FakeConnection(url,
            if (requests.isEmpty()) 200 else 409, """{"reg":false}""").also(requests::add) }
        assertFalse(repository.registrationEnabled())
        val failure = try { repository.register("New user", "not-a-real-password"); null } catch (e: AsmrApiException) { e }
        assertEquals(409, failure?.status)
        assertEquals("/api/auth/reg", requests[1].url.path)
        assertEquals("POST", requests[1].requestMethod)
        val sent = JSONObject(requests[1].output.toString("UTF-8"))
        assertEquals("New user", sent.getString("name"))
        assertEquals("not-a-real-password", sent.getString("password"))
        assertFalse(repository.signedIn)
        assertFalse(context.getSharedPreferences("asmr_online", 0).all.values.contains("not-a-real-password"))
    }

    @Test fun successfulAuthenticationUnlocksDialogBeforeCallingItsDismissHandler() = runBlocking {
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 200, """{"works":[]}""") }
        val vm = AsmrViewModel(context, repository)
        try {
            var closed = false
            vm.authenticate({ assertTrue(vm.authenticating) }) {
                // The actual screen ignores dismissals while authenticating is true.
                if (!vm.authenticating) closed = true
            }
            assertTrue("A successful sign-in/registration must close its dialog", closed)
            assertFalse(vm.authenticating)
            assertNull(vm.loginError)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun failedAuthenticationKeepsDialogOpenAndAllowsAnotherAttempt() = runBlocking {
        val repository = AsmrRepository(context) { url -> FakeConnection(url, 200, """{"works":[]}""") }
        val vm = AsmrViewModel(context, repository)
        try {
            var closed = false
            vm.authenticate({ throw AsmrApiException(401) }) { closed = true }
            assertFalse(closed)
            assertFalse(vm.authenticating)
            assertEquals(R.string.asmr_login_failed, vm.loginError)
        } finally { vm.viewModelScope.cancel() }
    }

    private class FakeConnection(url: URL, private val status: Int, private val response: String) : HttpURLConnection(url) {
        var disconnected = false
        val output = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getOutputStream() = output
    }
}
