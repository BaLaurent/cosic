package com.octopusman.cosic.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.R
import com.octopusman.cosic.monetization.AdsController
import com.octopusman.cosic.ui.AdBannerBar
import com.octopusman.cosic.ui.ConfirmDialog
import com.octopusman.cosic.ui.RenameDialog
import com.octopusman.cosic.ui.shareText
import com.octopusman.cosic.ui.tagged
import com.octopusman.cosic.ui.toast
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The home screen: the user's projects, the bundled examples, and the way to Settings and to removing ads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    ads: AdsController,
    adsRemoved: Boolean,
    onRemoveAds: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val projects by viewModel.projectList.collectAsStateWithLifecycle()
    val damaged by viewModel.damaged.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Only the id is kept, so the dialogs survive a rotation and always show the project's latest name.
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var restarting by rememberSaveable { mutableStateOf<String?>(null) }
    // A project of a lesson shows in the Lessons section, not under Projects; one whose lesson the app no longer has is an ordinary project.
    val lessonIds = remember(viewModel.lessons) { viewModel.lessons.mapTo(HashSet()) { it.id } }
    val ownProjects = projects.filter { it.lesson?.id !in lessonIds }
    // Most recent project first, so distinctBy keeps the one the lesson opens.
    val lessonSteps: Map<String, Int> = projects
        .mapNotNull { p -> p.lesson?.takeIf { it.id in lessonIds } }
        .distinctBy { it.id }
        .associate { it.id to it.step }
    // The ages ("5 min ago") move on while the screen stays open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (!adsRemoved) {
                        TextButton(
                            onClick = onRemoveAds,
                            modifier = Modifier.tagged("remove_ads", stringResource(R.string.library_remove_ads)),
                        ) { Text(stringResource(R.string.library_remove_ads)) }
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.tagged("settings", stringResource(R.string.action_settings))) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        val id = viewModel.createProject()
                        if (id != null) onOpenProject(id) else context.toast(R.string.library_save_failed)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.tagged("new_project", stringResource(R.string.library_new_project)),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
        bottomBar = { AdBannerBar(ads) },
    ) { padding ->
        LazyColumn(
            // Room for the floating button, so it never hides the last row.
            contentPadding = PaddingValues(bottom = 88.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(key = "projects-header") { SectionHeader(stringResource(R.string.library_projects)) }
            if (damaged > 0) {
                item(key = "projects-damaged") {
                    ListItem(
                        headlineContent = { Text(pluralStringResource(R.plurals.library_damaged, damaged, damaged)) },
                        trailingContent = {
                            TextButton(
                                onClick = { scope.launch { if (!viewModel.recoverDamaged()) context.toast(R.string.library_save_failed) } },
                                modifier = Modifier.tagged("recover_damaged", stringResource(R.string.library_recover)),
                            ) { Text(stringResource(R.string.library_recover)) }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            if (ownProjects.isEmpty()) {
                item(key = "projects-empty") {
                    Text(
                        text = stringResource(R.string.library_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(ownProjects, key = { it.id }) { project ->
                ProjectRow(
                    project = project,
                    now = now,
                    onOpen = { onOpenProject(project.id) },
                    onRename = { renaming = project.id },
                    onDuplicate = { scope.launch { if (!viewModel.duplicate(project.id)) context.toast(R.string.library_save_failed) } },
                    onShare = { context.shareText(project.code) },
                    onDelete = { deleting = project.id },
                )
            }
            item(key = "lessons-header") { SectionHeader(stringResource(R.string.library_lessons)) }
            items(viewModel.lessons, key = { "lesson-${it.id}" }) { lesson ->
                LessonRow(
                    lesson = lesson,
                    step = lessonSteps[lesson.id],
                    onOpen = {
                        scope.launch {
                            val id = viewModel.openLesson(lesson)
                            if (id != null) onOpenProject(id) else context.toast(R.string.library_save_failed)
                        }
                    },
                    onRestart = { restarting = lesson.id },
                )
            }
            item(key = "examples-header") { SectionHeader(stringResource(R.string.library_examples)) }
            items(viewModel.examples, key = { "example-${it.number}" }) { example ->
                ListItem(
                    headlineContent = { Text(example.title) },
                    leadingContent = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                val id = viewModel.createFromExample(example)
                                if (id != null) onOpenProject(id) else context.toast(R.string.library_save_failed)
                            }
                        }
                        .tagged("example_${example.number}", stringResource(R.string.library_open_example, example.title)),
                )
            }
        }
    }

    projects.firstOrNull { it.id == renaming }?.let { project ->
        RenameDialog(
            initial = project.name,
            onConfirm = {
                scope.launch { if (!viewModel.rename(project.id, it)) context.toast(R.string.library_save_failed) }
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    projects.firstOrNull { it.id == deleting }?.let { project ->
        ConfirmDialog(
            title = stringResource(R.string.library_delete_title),
            message = stringResource(R.string.library_delete_message, project.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(project.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    viewModel.lessons.firstOrNull { it.id == restarting }?.let { lesson ->
        ConfirmDialog(
            title = stringResource(R.string.library_restart_title),
            message = stringResource(R.string.library_restart_message, lesson.title),
            confirmLabel = stringResource(R.string.library_restart_lesson),
            onConfirm = {
                scope.launch {
                    val id = viewModel.restartLesson(lesson)
                    if (id != null) onOpenProject(id) else context.toast(R.string.library_save_failed)
                }
                restarting = null
            },
            onDismiss = { restarting = null },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Tapping opens the project; a long press opens its menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    project: Project,
    now: Long,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val firstLine = remember(project.code) { firstCodeLine(project.code) }
    val supporting: (@Composable () -> Unit)? = if (firstLine.isEmpty()) {
        null
    } else {
        { Text(firstLine, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

    Box {
        ListItem(
            headlineContent = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = supporting,
            trailingContent = { Text(ageText(project.updatedAt, now), style = MaterialTheme.typography.bodySmall) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
                onLongClickLabel = stringResource(R.string.library_project_options),
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = DpOffset(16.dp, 0.dp)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = { menuOpen = false; onDuplicate() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menuOpen = false; onShare() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; onDelete() })
        }
    }
}

/** Tapping opens the lesson at the step the user left; once started, a long press offers to start it over. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LessonRow(lesson: Lesson, step: Int?, onOpen: () -> Unit, onRestart: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = lesson.steps.size

    Box {
        ListItem(
            headlineContent = { Text(lesson.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(Icons.Filled.School, contentDescription = null) },
            supportingContent = {
                Text(
                    if (step == null) {
                        pluralStringResource(R.plurals.library_lesson_steps, count, count)
                    } else {
                        stringResource(R.string.lesson_step, minOf(step, lesson.steps.lastIndex) + 1, count)
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .combinedClickable(
                    onClick = onOpen,
                    onClickLabel = stringResource(R.string.library_open_lesson),
                    onLongClick = if (step != null) ({ menuOpen = true }) else null,
                    onLongClickLabel = stringResource(R.string.library_lesson_options),
                )
                .testTag("lesson_${lesson.id}"),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = DpOffset(16.dp, 0.dp)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_restart_lesson)) },
                onClick = { menuOpen = false; onRestart() },
                modifier = Modifier.testTag("lesson_restart"),
            )
        }
    }
}

@Composable
private fun ageText(updatedAt: Long, now: Long): String = when (val age = ageOf(updatedAt, now)) {
    Age.JustNow -> stringResource(R.string.time_just_now)
    is Age.Minutes -> stringResource(R.string.time_minutes_ago, age.value)
    is Age.Hours -> stringResource(R.string.time_hours_ago, age.value)
    is Age.Days -> stringResource(R.string.time_days_ago, age.value)
    is Age.OnDate -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(age.epochMillis))
}
