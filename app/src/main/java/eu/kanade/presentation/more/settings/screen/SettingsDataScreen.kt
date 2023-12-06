package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.EpisodeCache
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadCache
import eu.kanade.tachiyomi.ui.storage.StorageTab
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import logcat.LogPriority
import tachiyomi.core.i18n.stringResource
import tachiyomi.core.util.lang.launchNonCancellable
import tachiyomi.core.util.lang.withUIContext
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.backup.service.FLAG_CATEGORIES
import tachiyomi.domain.backup.service.FLAG_CHAPTERS
import tachiyomi.domain.backup.service.FLAG_CUSTOM_INFO
import tachiyomi.domain.backup.service.FLAG_EXTENSIONS
import tachiyomi.domain.backup.service.FLAG_EXT_SETTINGS
import tachiyomi.domain.backup.service.FLAG_HISTORY
import tachiyomi.domain.backup.service.FLAG_SETTINGS
import tachiyomi.domain.backup.service.FLAG_TRACK
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDataScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun getPreferences(): List<Preference> {
        val backupPreferences = Injekt.get<BackupPreferences>()
        val storagePreferences = Injekt.get<StoragePreferences>()
        val libraryPreferences = Injekt.get<LibraryPreferences>()

        return listOf(
            getStorageLocationPref(storagePreferences = storagePreferences),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),

            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getDataGroup(libraryPreferences = libraryPreferences),
        )
    }

    @Composable
    private fun getStorageLocationPref(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val storageDirPref = storagePreferences.baseStorageDirectory()
        val storageDir by storageDirPref.collectAsState()
        val pickStorageLocation = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                context.contentResolver.takePersistableUriPermission(uri, flags)

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set(it.uri.toString())
                }
                Injekt.get<AnimeDownloadCache>().invalidateCache()
            }
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location),
            subtitle = remember(storageDir) {
                (UniFile.fromUri(context, storageDir.toUri())?.filePath)
            } ?: stringResource(MR.strings.invalid_location, storageDir),
            onClick = {
                try {
                    pickStorageLocation.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.stringResource(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp().collectAsState()

        val backupIntervalPref = backupPreferences.backupInterval()
        val backupInterval by backupIntervalPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = listOf(
                // Manual actions
                getCreateBackupPref(),
                getRestoreBackupPref(),

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    pref = backupPreferences.backupInterval(),
                    title = stringResource(MR.strings.pref_backup_interval),
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    onValueChanged = {
                        BackupCreateJob.setupTask(context, it)
                        true
                    },
                ),

                Preference.PreferenceItem.MultiSelectListPreference(
                    pref = backupPreferences.backupFlags(),
                    enabled = backupInterval != 0,
                    title = stringResource(MR.strings.pref_backup_flags),
                    subtitle = stringResource(MR.strings.pref_backup_flags_summary),
                    entries = mapOf(
                        FLAG_CATEGORIES to stringResource(MR.strings.categories),
                        FLAG_CHAPTERS to stringResource(MR.strings.episodes),
                        FLAG_HISTORY to stringResource(MR.strings.history),
                        FLAG_TRACK to stringResource(MR.strings.track),
                        FLAG_SETTINGS to stringResource(MR.strings.settings),
                        FLAG_EXT_SETTINGS to stringResource(MR.strings.extension_settings),
                        FLAG_EXTENSIONS to stringResource(MR.strings.label_extensions),
                        FLAG_CUSTOM_INFO to stringResource(MR.strings.custom_entry_info),
                    ),
                    onValueChanged = {
                        if (FLAG_SETTINGS in it || FLAG_EXT_SETTINGS in it) {
                            context.stringResource(MR.strings.backup_settings_warning, Toast.LENGTH_LONG)
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getCreateBackupPref(): Preference.PreferenceItem.TextPreference {
        val navigator = LocalNavigator.currentOrThrow
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_create_backup),
            subtitle = stringResource(MR.strings.pref_create_backup_summ),
            onClick = { navigator.push(CreateBackupScreen()) },
        )
    }

    @Composable
    private fun getRestoreBackupPref(): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        var error by remember { mutableStateOf<Any?>(null) }
        if (error != null) {
            val onDismissRequest = { error = null }
            when (val err = error) {
                is InvalidRestore -> {
                    AlertDialog(
                        onDismissRequest = onDismissRequest,
                        title = { Text(text = stringResource(MR.strings.invalid_backup_file)) },
                        text = { Text(text = listOfNotNull(err.uri, err.message).joinToString("\n\n")) },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    context.copyToClipboard(err.message, err.message)
                                    onDismissRequest()
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_copy_to_clipboard))
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = onDismissRequest) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                    )
                }
                is MissingRestoreComponents -> {
                    AlertDialog(
                        onDismissRequest = onDismissRequest,
                        title = { Text(text = stringResource(MR.strings.pref_restore_backup)) },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            ) {
                                val msg = buildString {
                                    append(stringResource(MR.strings.backup_restore_content_full))
                                    if (err.sources.isNotEmpty()) {
                                        append("\n\n").append(
                                            stringResource(MR.strings.backup_restore_missing_sources),
                                        )
                                        err.sources.joinTo(
                                            this,
                                            separator = "\n- ",
                                            prefix = "\n- ",
                                        )
                                    }
                                    if (err.trackers.isNotEmpty()) {
                                        append("\n\n").append(
                                            stringResource(MR.strings.backup_restore_missing_trackers),
                                        )
                                        err.trackers.joinTo(
                                            this,
                                            separator = "\n- ",
                                            prefix = "\n- ",
                                        )
                                    }
                                }
                                Text(text = msg)
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    BackupRestoreJob.start(context, err.uri)
                                    onDismissRequest()
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_restore))
                            }
                        },
                    )
                }
                else -> error = null // Unknown
            }
        }

        val chooseBackup = rememberLauncherForActivityResult(
            object : ActivityResultContracts.GetContent() {
                override fun createIntent(context: Context, input: String): Intent {
                    val intent = super.createIntent(context, input)
                    return Intent.createChooser(
                        intent,
                        context.stringResource(MR.strings.file_select_backup),
                    )
                }
            },
        ) {
            if (it == null) {
                context.stringResource(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            val results = try {
                BackupFileValidator().validate(context, it)
            } catch (e: Exception) {
                error = InvalidRestore(it, e.message.toString())
                return@rememberLauncherForActivityResult
            }

            if (results.missingSources.isEmpty() && results.missingTrackers.isEmpty()) {
                BackupRestoreJob.start(context, it)
                return@rememberLauncherForActivityResult
            }

            error = MissingRestoreComponents(it, results.missingSources, results.missingTrackers)
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_restore_backup),
            subtitle = stringResource(MR.strings.pref_restore_backup_summ),
            onClick = {
                if (!BackupRestoreJob.isRunning(context)) {
                    if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                        context.stringResource(MR.strings.restore_miui_warning, Toast.LENGTH_LONG)
                    }
                    // no need to catch because it's wrapped with a chooser
                    chooseBackup.launch("*/*")
                } else {
                    context.stringResource(MR.strings.restore_in_progress)
                }
            },
        )
    }

    @Composable
    private fun getDataGroup(libraryPreferences: LibraryPreferences): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        val episodeCache = remember { Injekt.get<EpisodeCache>() }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableAnimeSize = remember(cacheReadableSizeSema) { episodeCache.readableSize }

        val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }

        // AM (FILE-SIZE) -->
        LaunchedEffect(Unit) {
            downloadPreferences.showEpisodeFileSize().changes()
                .drop(1)
                .collectLatest { value ->
                    if (value) {
                        Injekt.get<AnimeDownloadCache>().invalidateCache()
                    }
                }
        }
        // <-- AM (FILE-SIZE)

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_storage),
            preferenceItems = listOf(
                getAnimeStorageInfoPref(cacheReadableAnimeSize),

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_episode_cache),
                    subtitle = stringResource(
                        MR.strings.used_cache,
                        cacheReadableAnimeSize,
                    ),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = episodeCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.stringResource(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = libraryPreferences.autoClearItemCache(),
                    title = stringResource(MR.strings.pref_auto_clear_episode_cache),
                ),
                // AM (FS) -->
                Preference.PreferenceItem.SwitchPreference(
                    pref = downloadPreferences.showEpisodeFileSize(),
                    title = stringResource(MR.strings.pref_show_downloaded_episode_file_size),
                ),
                // <-- AM (FS)
            ),
        )
    }

    @Composable
    fun getAnimeStorageInfoPref(
        episodeCacheReadableSize: String,
    ): Preference.PreferenceItem.CustomPreference {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val available = remember {
            Formatter.formatFileSize(context, DiskUtil.getAvailableStorageSpace(Environment.getDataDirectory()))
        }
        val total = remember {
            Formatter.formatFileSize(context, DiskUtil.getTotalStorageSpace(Environment.getDataDirectory()))
        }

        return Preference.PreferenceItem.CustomPreference(
            title = stringResource(MR.strings.pref_storage_usage),
        ) {
            BasePreferenceWidget(
                title = stringResource(MR.strings.pref_storage_usage),
                subcomponent = {
                    // TODO: downloads, SD cards, bar representation?, i18n
                    Box(modifier = Modifier.padding(horizontal = PrefsHorizontalPadding)) {
                        Text(text = "Available: $available / $total (Episode cache: $episodeCacheReadableSize)")
                    }
                },
                onClick = { navigator.push(StorageTab()) }
            )
        }
    }
}

private data class MissingRestoreComponents(
    val uri: Uri,
    val sources: List<String>,
    val trackers: List<String>,
)

private data class InvalidRestore(
    val uri: Uri? = null,
    val message: String,
)
