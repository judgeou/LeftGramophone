package org.akanework.gramophone.ui.asmr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.asmr.AsmrReview
import org.akanework.gramophone.logic.asmr.AsmrWork

@Composable
internal fun CreateAsmrPlaylistDialog(vm: AsmrViewModel, work: AsmrWork? = null,
                                     onDismiss: () -> Unit, onCreated: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var privacy by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.clearOperationError() }
    AlertDialog(onDismissRequest = { if (!vm.operationBusy) onDismiss() },
        title = { Text(stringResource(R.string.asmr_create_playlist)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.asmr_playlist_name)) },
                singleLine = true, enabled = !vm.operationBusy)
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.asmr_description)) },
                enabled = !vm.operationBusy, maxLines = 4)
            Text(stringResource(R.string.asmr_visibility), style = MaterialTheme.typography.labelLarge)
            listOf(R.string.asmr_private, R.string.asmr_unlisted, R.string.asmr_public).forEachIndexed { index, label ->
                FilterChip(privacy == index, onClick = { privacy = index }, enabled = !vm.operationBusy,
                    label = { Text(stringResource(label)) })
            }
            AsmrOperationStatus(vm)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !vm.operationBusy,
            onClick = { vm.operate({ vm.repository.createPlaylist(name, description, privacy, work) }) { onDismiss(); onCreated() } }) {
            Text(stringResource(R.string.asmr_create_playlist))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.operationBusy) { Text(stringResource(R.string.asmr_cancel)) } })
}

@Composable
internal fun AsmrMembershipDialog(vm: AsmrViewModel, work: AsmrWork, onDismiss: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    LaunchedEffect(work.id) { vm.loadMemberships(work) }
    AlertDialog(onDismissRequest = { if (!vm.operationBusy) onDismiss() },
        title = { Text(stringResource(R.string.asmr_add_to_playlist)) },
        text = { Column {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(vm.memberships, key = { it.id }) { playlist ->
                    Row {
                        Checkbox(checked = playlist.containsWork, enabled = !vm.operationBusy, onCheckedChange = { included ->
                            vm.operate({ vm.repository.setPlaylistMembership(playlist.id, work.id, included) }) { vm.loadMemberships(work) }
                        })
                        Text(playlistName(playlist), Modifier.padding(top = 12.dp))
                    }
                }
                if (vm.membershipsMore) item {
                    TextButton(onClick = { vm.loadMemberships(work, more = true) }, enabled = !vm.operationBusy) { Text(stringResource(R.string.asmr_more)) }
                }
            }
            if (vm.memberships.isEmpty() && !vm.operationBusy && vm.operationError == null) Text(stringResource(R.string.asmr_empty))
            AsmrOperationStatus(vm)
            if (vm.operationError != null) TextButton(onClick = { vm.loadMemberships(work) }, enabled = !vm.operationBusy) {
                Text(stringResource(R.string.asmr_retry))
            }
            TextButton(onClick = { create = true }, enabled = !vm.operationBusy) { Text(stringResource(R.string.asmr_create_playlist)) }
        } },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !vm.operationBusy) { Text(stringResource(R.string.asmr_done)) } })
    if (create) CreateAsmrPlaylistDialog(vm, work, onDismiss = { create = false }, onCreated = { vm.loadMemberships(work) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AsmrReviewDialog(vm: AsmrViewModel, work: AsmrWork, onDismiss: () -> Unit) {
    var rating by remember { mutableFloatStateOf(work.review.rating.toFloat()) }
    var text by remember { mutableStateOf(work.review.text) }
    var progress by remember { mutableStateOf(work.review.progress) }
    var delete by remember { mutableStateOf(false) }
    LaunchedEffect(work.id) { vm.clearOperationError() }
    fun reload() { onDismiss(); vm.loadDetails() }
    AlertDialog(onDismissRequest = { if (!vm.operationBusy) onDismiss() },
        title = { Text(stringResource(R.string.asmr_review_progress)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (rating == 0f) stringResource(R.string.asmr_unrated) else stringResource(R.string.asmr_your_rating, rating.toString()))
            Slider(rating, onValueChange = { rating = it }, valueRange = 0f..5f, steps = 9, enabled = !vm.operationBusy)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to R.string.asmr_progress_none, "marked" to R.string.asmr_marked,
                    "listening" to R.string.asmr_listening, "listened" to R.string.asmr_listened,
                    "replay" to R.string.asmr_replay, "postponed" to R.string.asmr_postponed).forEach { (value, label) ->
                    FilterChip(progress == value, onClick = { progress = value }, enabled = !vm.operationBusy,
                        label = { Text(stringResource(label)) })
                }
            }
            OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.asmr_review_text)) },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, enabled = !vm.operationBusy)
            AsmrOperationStatus(vm)
            if (work.review != AsmrReview()) TextButton(onClick = { delete = true }, enabled = !vm.operationBusy) {
                Text(stringResource(R.string.asmr_delete_review), color = MaterialTheme.colorScheme.error)
            }
        } },
        confirmButton = { TextButton(enabled = !vm.operationBusy,
            onClick = { vm.operate({ vm.repository.saveReview(work.id, AsmrReview(rating.toDouble(), text, progress)) }) { reload() } }) {
            Text(stringResource(R.string.asmr_save_changes))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.operationBusy) { Text(stringResource(R.string.asmr_cancel)) } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.asmr_delete_review)) },
        text = { Text(stringResource(R.string.asmr_delete_review_confirm)) },
        confirmButton = { TextButton(enabled = !vm.operationBusy,
            onClick = { vm.operate({ vm.repository.deleteReview(work.id) }) { delete = false; reload() } }) { Text(stringResource(R.string.asmr_delete_review)) } },
        dismissButton = { TextButton(onClick = { delete = false }, enabled = !vm.operationBusy) { Text(stringResource(R.string.asmr_cancel)) } })
}

@Composable
private fun AsmrOperationStatus(vm: AsmrViewModel) {
    if (vm.operationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    vm.operationError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}
