package org.akanework.gramophone.ui.asmr

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.asmr.AsmrFile
import org.akanework.gramophone.logic.asmr.AsmrPlaylist
import org.akanework.gramophone.logic.asmr.AsmrRepository
import org.akanework.gramophone.logic.asmr.AsmrWork
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.setMediaItemsWithTitle
import org.akanework.gramophone.ui.GramophoneTheme
import org.akanework.gramophone.ui.fragments.BaseFragment

class AsmrFragment : BaseFragment(true) {
    private val model: AsmrViewModel by viewModels()
    private var active by mutableStateOf(true)

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        active = !hidden
        if (!hidden) model.reloadAccount()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply {
            // Compose installs its own insets listener on ComposeView. Apply Gramophone's
            // status-bar/mini-player padding to a parent that Compose does not replace.
            enableEdgeToEdgePaddingListener(ime = true, top = true)
            addView(ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    GramophoneTheme(pureDark = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("pureDark", false)) {
                        AsmrScreen(model, active,
                            onBack = { parentFragmentManager.popBackStack() },
                            onPlay = ::play,
                            onDownload = ::download)
                    }
                }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = arguments?.getString("work") ?: return
        if (model.selectedWork?.id == id) return
        viewLifecycleOwner.lifecycleScope.launch {
            try { model.openWork(model.repository.work(id)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast(R.string.asmr_connection_error) }
        }
    }

    private fun play(work: AsmrWork, files: List<AsmrFile>, start: Int, enqueue: Boolean) {
        if (files.isEmpty()) return
        val items = files.mapIndexed { i, file -> file.toMediaItem(work, i) }
        mainActivity.controllerViewModel.addControllerCallback(viewLifecycleOwner.lifecycle) { player, _ ->
            if (enqueue && player.mediaItemCount > 0) {
                player.addMediaItems(items)
                Toast.makeText(requireContext(), R.string.asmr_queued, Toast.LENGTH_SHORT).show()
            } else {
                player.setMediaItemsWithTitle(items, title = work.title, position = start, shuffleEnabled = false)
                player.prepare()
                player.play()
            }
            dispose()
        }
    }

    private fun download(work: AsmrWork, file: AsmrFile) {
        val url = file.url ?: return toast(R.string.asmr_file_unavailable)
        try {
            val filename = "${work.code}/${file.path}".split('/').joinToString("/") { part ->
                part.replace(Regex("[\\\\:*?\"<>|\\p{Cntrl}]"), "_").take(150).ifBlank { "file" }
                    .let { if (it == "." || it == "..") "_" else it }
            }
            val request = DownloadManager.Request(url.toUri())
                .setTitle(file.title)
                .setDescription(work.title)
                .addRequestHeader("Referer", AsmrRepository.WEBSITE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(requireContext(), Environment.DIRECTORY_DOWNLOADS, filename)
            (requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(R.string.asmr_download_started)
        } catch (_: Exception) { toast(R.string.asmr_download_failed) }
    }

    private fun toast(message: Int) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsmrScreen(vm: AsmrViewModel, active: Boolean, onBack: () -> Unit,
                       onPlay: (AsmrWork, List<AsmrFile>, Int, Boolean) -> Unit,
                       onDownload: (AsmrWork, AsmrFile) -> Unit) {
    var account by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(false) }
    val selected = vm.selectedWork
    BackHandler(active && selected != null) { vm.closeWork() }
    BackHandler(active && selected == null && vm.playlist != null) { vm.closePlaylist() }
    val header: @Composable () -> Unit = {
        TopAppBar(title = { Text(selected?.code ?: vm.playlist?.let { playlistName(it) } ?: stringResource(R.string.asmr_online)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = { ActionIcon(R.drawable.ic_arrow_back, R.string.asmr_back) {
                when { selected != null -> vm.closeWork(); vm.playlist != null -> vm.closePlaylist(); else -> onBack() }
            } },
            actions = {
                ActionIcon(R.drawable.ic_refresh, R.string.asmr_refresh) { if (selected == null) vm.refresh() else vm.loadDetails() }
                ActionIcon(R.drawable.ic_settings, R.string.asmr_account) { account = true }
            })
        EarControl()
    }
    Surface(Modifier.fillMaxSize()) {
        if (selected != null) {
            AsmrCollapsingLayout(selected.id, header) {
                key(selected.id) { WorkDetails(vm, selected, active, onPlay, onDownload, onLogin = { login = true }) }
            }
        } else {
            Library(vm, onLogin = { login = true }, header = header)
        }
    }
    if (account) AccountDialog(vm, onDismiss = { account = false }, onLogin = { account = false; login = true })
    if (login) LoginDialog(vm, onDismiss = { if (!vm.authenticating) login = false })
}

@Composable
private fun EarControl() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var mode by remember { mutableStateOf(prefs.getString("asmr_ear_preference", "off") ?: "off") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "asmr_ear_preference") mode = prefs.getString(key, "off") ?: "off"
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_headphones), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.asmr_channel_hint), style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off" to R.string.settings_asmr_ear_preference_off, "left" to R.string.settings_asmr_ear_preference_left,
                "right" to R.string.settings_asmr_ear_preference_right).forEach { (value, label) ->
                FilterChip(selected = mode == value, onClick = { prefs.edit { putString("asmr_ear_preference", value) } },
                    modifier = Modifier.weight(1f), label = { Text(stringResource(label)) })
            }
        }
    }
}

@Composable
private fun Library(vm: AsmrViewModel, onLogin: () -> Unit, header: @Composable () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var draft by rememberSaveable(vm.query, vm.section) { mutableStateOf(vm.query) }
    var sortOpen by remember { mutableStateOf(false) }
    var createPlaylist by remember { mutableStateOf(false) }
    if (createPlaylist) CreateAsmrPlaylistDialog(vm, onDismiss = { createPlaylist = false }, onCreated = vm::refresh)
    val isLabels = vm.section in listOf("tags", "vas", "circles") && vm.playlist == null
    val sections = listOf("works" to R.string.asmr_discover, "tags" to R.string.asmr_tags, "vas" to R.string.asmr_actors,
        "circles" to R.string.asmr_circles, "saved" to R.string.asmr_saved, "playlists" to R.string.asmr_playlists)
    AsmrCollapsingLayout(resetKey = listOf(vm.section, vm.query, vm.facet, vm.playlist?.id, vm.order, vm.subtitles), header = {
        header()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sections.forEach { (id, label) -> FilterChip(vm.section == id,
                onClick = { vm.selectSection(id) }, label = { Text(stringResource(label)) }) }
        }
        if (vm.playlist == null && (vm.section == "works" || isLabels)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                label = { Text(stringResource(if (isLabels) R.string.asmr_search_labels else R.string.asmr_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (!isLabels) vm.search(draft); keyboard?.hide() }),
                trailingIcon = { if (!isLabels) ActionIcon(R.drawable.ic_search, R.string.asmr_search_action) { vm.search(draft); keyboard?.hide() } })
        }
        if (vm.section == "works" && vm.playlist == null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    TextButton(onClick = { sortOpen = true }) {
                        Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(sortOptions.first { it.first == vm.order }.second))
                    }
                    DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                        sortOptions.forEach { (value, label) -> DropdownMenuItem(text = { Text(stringResource(label)) },
                            onClick = { sortOpen = false; vm.sort(value) }) }
                    }
                }
                FilterChip(vm.subtitles, onClick = { vm.filterSubtitles(!vm.subtitles) }, label = { Text(stringResource(R.string.asmr_subtitles)) })
            }
        }
        if (vm.section == "saved") Text(stringResource(R.string.asmr_saved_hint),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }) {
        if (vm.loading && vm.works.isEmpty() && vm.playlists.isEmpty()) Loading()
        else if (vm.section == "playlists" && vm.playlist == null && !vm.signedIn) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.asmr_sign_in_required))
                Button(onClick = onLogin, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.asmr_login)) }
            }
        } else if (isLabels) {
            val visible = remember(vm.labels, draft) { vm.labels.filter { it.name.contains(draft, true) } }
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                vm.error?.let { item { ErrorMessage(it, vm::refresh) } }
                items(visible, key = { it.id }) { label ->
                    ListItem(headlineContent = { Text(label.name) },
                        modifier = Modifier.clickable { vm.openFacet(vm.section, label) })
                }
                if (visible.isEmpty() && vm.error == null) item { EmptyMessage() }
            }
        } else if (vm.section == "playlists" && vm.playlist == null) {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item { TextButton(onClick = { createPlaylist = true }) { Text(stringResource(R.string.asmr_create_playlist)) } }
                vm.error?.let { item { ErrorMessage(it, vm::refresh) } }
                items(vm.playlists, key = { it.id }) { playlist -> ListItem(
                    headlineContent = { Text(playlistName(playlist)) },
                    supportingContent = { Text(stringResource(R.string.asmr_playlist_count, playlist.count)) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_playlist_play), null) },
                    modifier = Modifier.clickable { vm.openPlaylist(playlist) }) }
                if (vm.playlists.isEmpty() && vm.error == null) item { EmptyMessage() }
                if (vm.hasMore) item { MoreButton(vm.loading, vm::loadMore) }
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                vm.error?.let { item(span = { GridItemSpan(maxLineSpan) }) { ErrorMessage(it, vm::refresh) } }
                gridItems(vm.works, key = { it.id }) { work -> WorkCard(work) { vm.openWork(work) } }
                if (vm.works.isEmpty() && vm.error == null) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage() }
                if (vm.hasMore) item(span = { GridItemSpan(maxLineSpan) }) { MoreButton(vm.loading, vm::loadMore) }
            }
        }
    }
}

private val sortOptions = listOf("create_date" to R.string.asmr_latest, "release" to R.string.asmr_release,
    "dl_count" to R.string.asmr_popular, "rate_average_2dp" to R.string.asmr_rating, "random" to R.string.asmr_random)

@Composable
private fun WorkCard(work: AsmrWork, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        AsmrCover(work, Modifier.fillMaxWidth().aspectRatio(1.0f))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(work.code, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            Text(work.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(work.circle?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (work.rating > 0) Text("★ ${work.rating}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun AsmrCover(work: AsmrWork, modifier: Modifier) {
    AsyncImage(model = work.cover, contentDescription = work.title,
        placeholder = painterResource(R.drawable.ic_album), error = painterResource(R.drawable.ic_album),
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentScale = ContentScale.Crop)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkDetails(vm: AsmrViewModel, work: AsmrWork, active: Boolean,
                        onPlay: (AsmrWork, List<AsmrFile>, Int, Boolean) -> Unit,
                        onDownload: (AsmrWork, AsmrFile) -> Unit, onLogin: () -> Unit) {
    var folder by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var memberships by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    if (memberships) AsmrMembershipDialog(vm, work, onDismiss = { memberships = false })
    if (review) AsmrReviewDialog(vm, work, onDismiss = { review = false })
    fun nodesAt(path: List<Int>): List<AsmrFile> = path.fold(vm.files) { nodes, index -> nodes.getOrNull(index)?.children ?: emptyList() }
    val nodes = nodesAt(folder)
    val audio = nodes.flatMap { it.audioFiles() }
    BackHandler(active && folder.isNotEmpty()) { folder = folder.dropLast(1) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            AsmrCover(work, Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(1.5f).clip(RoundedCornerShape(20.dp)))
            Text(work.title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
            Text("${work.code} · ${work.release}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                work.circle?.let { label -> AssistChip(onClick = { vm.closeWork(); vm.openFacet("circles", label) }, label = { Text(label.name) }) }
                work.actors.forEach { label -> AssistChip(onClick = { vm.closeWork(); vm.openFacet("vas", label) }, label = { Text(label.name) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                work.tags.forEach { label -> SuggestionChip(onClick = { vm.closeWork(); vm.openFacet("tags", label) }, label = { Text(label.name) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(work.id in vm.saved, onClick = { vm.toggleSaved(work) },
                    label = { Text(stringResource(if (work.id in vm.saved) R.string.asmr_unsave else R.string.asmr_save)) })
                AssistChip(onClick = { if (vm.signedIn) memberships = true else onLogin() },
                    label = { Text(stringResource(R.string.asmr_add_to_playlist)) })
                AssistChip(onClick = { if (vm.signedIn) review = true else onLogin() }, enabled = !vm.detailLoading && vm.detailError == null,
                    label = { Text(stringResource(R.string.asmr_review_progress)) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.asmr_files), style = MaterialTheme.typography.titleMedium)
        }
        if (vm.detailLoading) item { Loading() }
        vm.detailError?.let { item { ErrorMessage(it, vm::loadDetails) } }
        if (!vm.detailLoading && vm.detailError == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (folder.isNotEmpty()) ActionIcon(R.drawable.ic_arrow_back, R.string.asmr_back) { folder = folder.dropLast(1) }
                    Button(onClick = { onPlay(work, audio, 0, false) }, enabled = audio.isNotEmpty()) { Text(stringResource(R.string.asmr_play_all)) }
                    ActionIcon(R.drawable.ic_playlist_play, R.string.asmr_enqueue, enabled = audio.isNotEmpty()) { onPlay(work, audio, 0, true) }
                }
            }
            items(nodes.size, key = { nodes[it].path }) { index ->
                val file = nodes[index]
                ListItem(headlineContent = { Text(file.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (file.duration > 0) Text(timestamp(file.duration)) },
                    leadingContent = { Icon(painterResource(if (file.isFolder) R.drawable.ic_folder else if (file.isAudio) R.drawable.ic_play_arrow else R.drawable.ic_article), null) },
                    trailingContent = { if (!file.isFolder && file.url != null) ActionIcon(R.drawable.ic_file_download, R.string.asmr_download) { onDownload(work, file) } },
                    modifier = Modifier.clickable {
                        when { file.isFolder -> folder = folder + index
                            file.isAudio -> onPlay(work, audio, audio.indexOf(file).coerceAtLeast(0), false)
                            file.url != null -> onDownload(work, file) }
                    })
            }
            if (nodes.isEmpty()) item { EmptyMessage() }
        }
    }
}

@Composable
private fun LoginDialog(vm: AsmrViewModel, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    // Intentionally not saveable: never put a password in saved instance state.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var registrationAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(vm.repository.baseUrl) {
        try { registrationAvailable = vm.repository.registrationEnabled() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { registrationAvailable = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (register) R.string.asmr_register else R.string.asmr_login)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.asmr_username)) }, singleLine = true, enabled = !vm.authenticating)
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.asmr_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = !vm.authenticating)
            if (register) OutlinedTextField(confirmation, { confirmation = it }, label = { Text(stringResource(R.string.asmr_confirm_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = !vm.authenticating,
                isError = confirmation.isNotEmpty() && confirmation != password)
            Text(vm.repository.baseUrl, style = MaterialTheme.typography.bodySmall)
            vm.loginError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (vm.authenticating) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (registrationAvailable) TextButton(onClick = { register = !register; vm.clearLoginError() }, enabled = !vm.authenticating) {
                Text(stringResource(if (register) R.string.asmr_back_to_login else R.string.asmr_register))
            }
        } },
        confirmButton = { TextButton(onClick = { vm.login(username, password, register) { password = ""; confirmation = ""; onDismiss() } },
            enabled = !vm.authenticating && username.isNotBlank() && password.isNotEmpty() && (!register || confirmation == password)) {
            Text(stringResource(if (register) R.string.asmr_register else R.string.asmr_login)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.authenticating) { Text(stringResource(R.string.asmr_cancel)) } })
}

@Composable
private fun AccountDialog(vm: AsmrViewModel, onDismiss: () -> Unit, onLogin: () -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf(vm.repository.baseUrl) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.asmr_account)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.username ?: stringResource(R.string.asmr_guest), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { if (vm.signedIn) { vm.logout(); onDismiss() } else onLogin() }) {
                Text(stringResource(if (vm.signedIn) R.string.asmr_logout else R.string.asmr_login))
            }
            OutlinedTextField(server, { server = it; invalid = false }, label = { Text(stringResource(R.string.asmr_server)) },
                singleLine = true, isError = invalid, supportingText = { Text(stringResource(if (invalid) R.string.asmr_server_invalid else R.string.asmr_server_changed)) })
            AsmrRepository.SERVERS.forEach { value -> TextButton(onClick = { server = value; invalid = false }) { Text(value) } }
            TextButton(onClick = {
                try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); onDismiss() }
                catch (_: Exception) { Toast.makeText(context, R.string.asmr_download_failed, Toast.LENGTH_SHORT).show() }
            }) { Text(stringResource(R.string.asmr_downloads)) }
        } },
        confirmButton = { TextButton(onClick = {
            try { vm.server(server); onDismiss() } catch (_: IllegalArgumentException) { invalid = true }
            catch (_: java.net.URISyntaxException) { invalid = true }
        }) { Text(stringResource(R.string.asmr_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.asmr_cancel)) } })
}

@Composable
private fun ActionIcon(drawable: Int, label: Int, enabled: Boolean = true, onClick: () -> Unit) =
    IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(drawable), stringResource(label)) }

@Composable
private fun ErrorMessage(message: Int, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text(stringResource(R.string.asmr_retry)) }
    }
}

@Composable
private fun EmptyMessage() = Text(stringResource(R.string.asmr_empty), Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun MoreButton(loading: Boolean, load: () -> Unit) = Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
    if (loading) CircularProgressIndicator() else OutlinedButton(onClick = load) { Text(stringResource(R.string.asmr_more)) }
}

@Composable
internal fun playlistName(playlist: AsmrPlaylist): String = when (playlist.name) {
    "__SYS_PLAYLIST_LIKED" -> stringResource(R.string.asmr_liked)
    "__SYS_PLAYLIST_MARKED" -> stringResource(R.string.asmr_marked)
    else -> playlist.name
}

private fun timestamp(duration: Double): String {
    val seconds = duration.toLong()
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
