package org.akanework.gramophone.ui.asmr

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.asmr.*
import java.net.SocketTimeoutException
import kotlin.random.Random

class AsmrViewModel @JvmOverloads constructor(application: Application,
    val repository: AsmrRepository = AsmrRepository(application)) : AndroidViewModel(application) {
    var section by mutableStateOf("works"); private set
    var query by mutableStateOf(""); private set
    var facet by mutableStateOf<AsmrFacet?>(null); private set
    var order by mutableStateOf("create_date"); private set
    var subtitles by mutableStateOf(false); private set
    var works by mutableStateOf(emptyList<AsmrWork>()); private set
    var labels by mutableStateOf(emptyList<AsmrLabel>()); private set
    var playlists by mutableStateOf(emptyList<AsmrPlaylist>()); private set
    var playlist by mutableStateOf<AsmrPlaylist?>(null); private set
    var selectedWork by mutableStateOf<AsmrWork?>(null); private set
    var files by mutableStateOf(emptyList<AsmrFile>()); private set
    var loading by mutableStateOf(false); private set
    var detailLoading by mutableStateOf(false); private set
    var error by mutableStateOf<Int?>(null); private set
    var detailError by mutableStateOf<Int?>(null); private set
    var hasMore by mutableStateOf(false); private set
    var signedIn by mutableStateOf(repository.username != null); private set
    var username by mutableStateOf(repository.username); private set
    var saved by mutableStateOf(emptySet<String>()); private set
    var authenticating by mutableStateOf(false); private set
    var loginError by mutableStateOf<Int?>(null); private set
    var operationBusy by mutableStateOf(false); private set
    var operationError by mutableStateOf<Int?>(null); private set
    var memberships by mutableStateOf(emptyList<AsmrPlaylist>()); private set
    var membershipsMore by mutableStateOf(false); private set
    private var membershipPage = 1
    private var operationJob: Job? = null
    private var nextPage = 1
    private var seed = Random.nextInt(1, Int.MAX_VALUE)
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var listGeneration = 0
    private var detailGeneration = 0
    private val preferences = application.getSharedPreferences("asmr_online", 0)
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "saved") {
            saved = repository.savedIds().toSet()
            if (section == "saved" && selectedWork == null) refresh()
        }
        if (key == "username") syncAccount()
    }

    init {
        saved = repository.savedIds().toSet()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh()
    }

    override fun onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onCleared()
    }

    fun selectSection(value: String) {
        section = value
        facet = null
        playlist = null
        query = ""
        refresh()
    }

    fun search(value: String) {
        facet = null
        section = "works"
        playlist = null
        query = value.trim()
        refresh()
    }

    fun openFacet(kind: String, label: AsmrLabel) {
        facet = AsmrFacet(kind, label)
        section = "works"
        playlist = null
        query = label.name
        refresh()
    }

    fun sort(value: String) {
        order = value
        seed = Random.nextInt(1, Int.MAX_VALUE)
        refresh()
    }

    fun filterSubtitles(value: Boolean) { subtitles = value; refresh() }

    fun openPlaylist(value: AsmrPlaylist) { playlist = value; refresh() }
    fun closePlaylist() { playlist = null; refresh() }

    fun refresh() {
        listGeneration++
        listJob?.cancel()
        nextPage = 1
        works = emptyList()
        labels = emptyList()
        playlists = emptyList()
        hasMore = false
        load()
    }

    fun loadMore() { if (!loading && hasMore) load() }

    private fun load() {
        val generation = listGeneration
        listJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                when {
                    playlist != null -> accept(repository.playlistWorks(playlist!!.id, nextPage))
                    section == "works" -> accept(repository.works(query, order, nextPage, subtitles, seed, facet))
                    section == "saved" -> {
                        val ids = repository.savedIds().drop((nextPage - 1) * 20).take(20)
                        val result = ids.map { repository.work(it) }
                        accept(AsmrPage(result, nextPage, 20, saved.size))
                    }
                    section == "playlists" -> {
                        if (!signedIn) return@launch
                        val result = repository.playlists(nextPage)
                        playlists = (playlists + result.items).distinctBy { it.id }
                        hasMore = result.hasMore
                        nextPage = result.page + 1
                    }
                    else -> labels = repository.labels(section)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = message(e)
                syncAccount()
            } finally {
                if (generation == listGeneration) loading = false
            }
        }
    }

    private fun accept(result: AsmrPage<AsmrWork>) {
        works = (works + result.items).distinctBy { it.id }
        nextPage = result.page + 1
        hasMore = result.hasMore
    }

    fun openWork(work: AsmrWork) {
        selectedWork = work
        files = emptyList()
        loadDetails()
    }

    fun loadDetails() {
        val work = selectedWork ?: return
        val generation = ++detailGeneration
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            detailLoading = true
            detailError = null
            try {
                files = repository.files(work.id)
                selectedWork = repository.workDetails(work.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detailError = message(e)
            } finally { if (generation == detailGeneration) detailLoading = false }
        }
    }

    fun closeWork() { detailGeneration++; detailJob?.cancel(); selectedWork = null; files = emptyList(); detailError = null }

    fun toggleSaved(work: AsmrWork) {
        repository.save(work, work.id !in saved)
        saved = repository.savedIds().toSet()
    }

    fun login(name: String, password: String, register: Boolean = false, onSuccess: () -> Unit) {
        if (authenticating) return
        viewModelScope.launch {
            authenticate({
                if (register) repository.register(name, password) else repository.login(name, password)
            }) {
                syncAccount()
                onSuccess()
                if (selectedWork != null) loadDetails()
                refresh()
            }
        }
    }

    internal suspend fun authenticate(action: suspend () -> Unit, onSuccess: () -> Unit) {
        authenticating = true
        loginError = null
        var success = false
        try { action(); success = true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { loginError = if (e is AsmrApiException && e.status == 401) R.string.asmr_login_failed else message(e) }
        finally { authenticating = false }
        // Dialog dismissal is guarded while a request is running. Complete that state
        // change before asking the UI to close, for both sign-in and registration.
        if (success) onSuccess()
    }

    fun clearOperationError() { operationError = null }
    fun clearLoginError() { loginError = null }

    fun operate(action: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        if (operationBusy) return
        operationJob = viewModelScope.launch {
            operationBusy = true
            operationError = null
            var success = false
            try { action(); success = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { operationError = message(e); syncAccount() }
            finally { operationBusy = false }
            if (success) onSuccess()
        }
    }

    fun loadMemberships(work: AsmrWork, more: Boolean = false) {
        if (!more) { membershipPage = 1; memberships = emptyList(); membershipsMore = false }
        operate({
            val result = repository.playlistMemberships(work.id, membershipPage)
            memberships = (memberships + result.items).distinctBy { it.id }
            membershipPage = result.page + 1
            membershipsMore = result.hasMore
        })
    }

    fun logout() {
        repository.logout(); syncAccount(); memberships = emptyList()
        selectedWork = selectedWork?.copy(review = AsmrReview())
        if (selectedWork != null) loadDetails()
        refresh()
    }
    fun reloadAccount() {
        val changed = username != repository.username
        syncAccount()
        if (changed && section == "playlists") refresh()
    }
    private fun syncAccount() { signedIn = repository.username != null; username = repository.username }
    fun server(value: String) {
        listJob?.cancel()
        detailJob?.cancel()
        operationJob?.cancel()
        operationError = null
        repository.baseUrl = value
        syncAccount()
        closeWork()
        refresh()
    }

    companion object {
        private fun message(e: Exception): Int = when {
            e is AsmrApiException && e.status == 401 -> R.string.asmr_sign_in_required
            e is AsmrApiException && e.status == 403 -> R.string.asmr_access_denied
            e is AsmrApiException && e.status in setOf(400, 422) -> R.string.asmr_invalid_request
            e is AsmrApiException && e.status == 409 -> R.string.asmr_name_taken
            e is AsmrApiException && e.status == 429 -> R.string.asmr_rate_limited
            e is SocketTimeoutException -> R.string.asmr_timeout
            else -> R.string.asmr_connection_error
        }
    }
}
