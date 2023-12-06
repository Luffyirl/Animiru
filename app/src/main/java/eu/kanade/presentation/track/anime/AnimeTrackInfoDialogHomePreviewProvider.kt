package eu.kanade.presentation.track.anime

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import eu.kanade.tachiyomi.ui.entries.anime.track.AnimeTrackItem
import eu.kanade.test.DummyTracker
import java.text.DateFormat
import tachiyomi.domain.track.anime.model.AnimeTrack

internal class AnimeTrackInfoDialogHomePreviewProvider :
    PreviewParameterProvider<@Composable () -> Unit> {

    private val aTrack = AnimeTrack(
        id = 1L,
        animeId = 2L,
        syncId = 3L,
        remoteId = 4L,
        libraryId = null,
        title = "Manage Name On Tracker Site",
        lastEpisodeSeen = 2.0,
        totalEpisodes = 12L,
        status = 1L,
        score = 2.0,
        remoteUrl = "https://example.com",
        startDate = 0L,
        finishDate = 0L,
    )
    private val trackItemWithoutTrack = AnimeTrackItem(
        track = null,
        tracker = DummyTracker(
            id = 1L,
            name = "Example Tracker",
        ),
    )
    private val trackItemWithTrack = AnimeTrackItem(
        track = aTrack,
        tracker = DummyTracker(
            id = 2L,
            name = "Example Tracker 2",
        ),
    )

    private val trackersWithAndWithoutTrack = @Composable {
        AnimeTrackInfoDialogHome(
            trackItems = listOf(
                trackItemWithoutTrack,
                trackItemWithTrack,
            ),
            dateFormat = DateFormat.getDateInstance(),
            onStatusClick = {},
            onEpisodeClick = {},
            onScoreClick = {},
            onStartDateEdit = {},
            onEndDateEdit = {},
            onNewSearch = {},
            onOpenInBrowser = {},
            onRemoved = {},
        )
    }

    private val noTrackers = @Composable {
        AnimeTrackInfoDialogHome(
            trackItems = listOf(),
            dateFormat = DateFormat.getDateInstance(),
            onStatusClick = {},
            onEpisodeClick = {},
            onScoreClick = {},
            onStartDateEdit = {},
            onEndDateEdit = {},
            onNewSearch = {},
            onOpenInBrowser = {},
            onRemoved = {},
        )
    }

    override val values: Sequence<@Composable () -> Unit>
        get() = sequenceOf(
            trackersWithAndWithoutTrack,
            noTrackers,
        )
}
