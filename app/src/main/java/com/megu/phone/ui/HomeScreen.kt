package com.megu.phone.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.megu.phone.data.Anime
import com.megu.phone.data.Progress
import com.megu.phone.data.Repo
import com.megu.phone.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class HomeData(
    val trending: List<Anime>,
    val popular: List<Anime>,
    val airing: List<Anime>,
    val topRated: List<Anime>
)

@Composable
fun HomeScreen(onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<Load<HomeData>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }

    // Refresh "Continue watching" every time the screen comes back (e.g. from the player).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val continueEntries = remember(tick, Progress.version) { Progress.continueWatching() }

    LaunchedEffect(reload) {
        state = Load.Loading
        state = try {
            coroutineScope {
                val trending = async { Repo.page("TRENDING_DESC") }
                val popular = async { Repo.page("POPULARITY_DESC") }
                val airing = async { Repo.page("POPULARITY_DESC", "RELEASING") }
                val topRated = async { Repo.page("SCORE_DESC") }
                Load.Ready(
                    HomeData(trending.await(), popular.await(), airing.await(), topRated.await())
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Network error")
        }
    }

    when (val s = state) {
        Load.Loading -> LoadingBox()
        is Load.Failed -> ErrorBox(s.message) { reload++ }
        is Load.Ready -> HomeContent(
            data = s.data,
            continueEntries = continueEntries,
            onOpen = onOpen,
            onContinue = { e -> PlayerActivity.start(context, e.animeId, e.episode, e.dub) }
        )
    }
}

@Composable
private fun HomeContent(
    data: HomeData,
    continueEntries: List<com.megu.phone.data.WatchEntry>,
    onOpen: (Int) -> Unit,
    onContinue: (com.megu.phone.data.WatchEntry) -> Unit
) {
    val hero = data.trending.firstOrNull { it.banner != null } ?: data.trending.firstOrNull()
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "title") {
            Text(
                "MeguAnimes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        if (continueEntries.isNotEmpty()) {
            item(key = "continue") { ContinueRow(continueEntries, onContinue) }
        }
        if (hero != null) {
            item(key = "hero") { Hero(hero, onOpen) }
        }
        item(key = "trending") { AnimeRow("Trending now", data.trending, onOpen) }
        item(key = "popular") { AnimeRow("Popular right now", data.popular, onOpen) }
        item(key = "airing") { AnimeRow("Airing now", data.airing, onOpen) }
        item(key = "top") { AnimeRow("Top rated", data.topRated, onOpen) }
    }
}
