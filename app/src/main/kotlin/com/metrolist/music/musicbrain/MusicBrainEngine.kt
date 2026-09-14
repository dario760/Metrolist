package com.metrolist.music.musicbrain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ln
import kotlin.math.max

/** Local, deterministic music taste engine. Network access stays in Metrolist. */
class MusicBrainEngine(
    private val store: MusicBrainStore = InMemoryMusicBrainStore(),
) {
    private val mutex = Mutex()

    suspend fun record(event: ListenEvent) = mutex.withLock {
        store.update { state ->
            val old = state.items[event.track.id] ?: TrackTaste()
            val next = when (event.type) {
                ListenEventType.STARTED -> old.copy(starts = old.starts + 1)
                ListenEventType.COMPLETED -> old.copy(completions = old.completions + 1)
                ListenEventType.SKIPPED -> old.copy(skips = old.skips + 1)
                ListenEventType.LIKED -> old.copy(likes = old.likes + 1)
                ListenEventType.DISLIKED -> old.copy(dislikes = old.dislikes + 1)
                ListenEventType.STOPPED -> old
            }
            val artists = state.artistAffinity.toMutableMap()
            event.track.artists.forEach { artist ->
                val key = artist.normalized()
                artists[key] = (artists[key] ?: 0.0) + affinityDelta(event)
            }
            val related = state.graph.addTrack(event.track)
            state.copy(
                items = state.items + (event.track.id to next),
                artistAffinity = artists,
                recentIds = (listOf(event.track.id) + state.recentIds.filterNot { it == event.track.id }).take(100),
                graph = related,
            )
        }
    }

    suspend fun setArtistBlocked(artist: String, blocked: Boolean) = mutex.withLock {
        store.update { state ->
            val next = state.blockedArtists.toMutableSet()
            if (blocked) next += artist.normalized() else next -= artist.normalized()
            state.copy(blockedArtists = next)
        }
    }

    suspend fun rank(seed: MusicTrack?, candidates: List<MusicTrack>, limit: Int = 20): List<MusicTrack> = mutex.withLock {
        rankLocked(store.read(), seed, candidates, limit)
    }

    /** Endless-radio batch. Metrolist supplies related/continuation candidates. */
    suspend fun nextRadioBatch(current: MusicTrack, candidates: List<MusicTrack>, batchSize: Int = 12): List<MusicTrack> =
        mutex.withLock { rankLocked(store.read(), current, candidates, batchSize) }

    suspend fun onRepeat(limit: Int = 20): List<MusicTrack> = mutex.withLock {
        val state = store.read()
        state.items.entries
            .sortedByDescending { (_, taste) -> taste.completions * 2 + taste.likes * 5 - taste.skips * 2 }
            .take(limit)
            .mapNotNull { (id, _) -> state.graph.tracks[id] }
    }

    /** Builds a Daily Mix from multiple artist/interest lanes instead of one artist only. */
    suspend fun dailyMix(candidates: List<MusicTrack>, limit: Int = 25): List<MusicTrack> = mutex.withLock {
        val state = store.read()
        val ranked = rankLocked(state, null, candidates, candidates.size)
        val lanes = ranked.groupBy { it.artists.firstOrNull()?.normalized() ?: "unknown" }
        lanes.values
            .sortedByDescending { lane -> lane.maxOfOrNull { score(it, null, state) } ?: 0.0 }
            .flatMap { lane -> lane.take(2) }
            .distinctBy { it.id }
            .take(limit)
    }

    /** Returns local related tracks already seen by the graph, useful before a network fetch. */
    suspend fun localRelated(seed: MusicTrack, limit: Int = 20): List<MusicTrack> = mutex.withLock {
        val state = store.read()
        val ids = state.graph.relatedIds(seed)
        rankLocked(state, seed, ids.mapNotNull(state.graph.tracks::get), limit)
    }

    suspend fun snapshot(): MusicBrainState = mutex.withLock { store.read() }

    private fun rankLocked(state: MusicBrainState, seed: MusicTrack?, candidates: List<MusicTrack>, limit: Int) =
        candidates.asSequence()
            .filter { it.id != seed?.id }
            .filterNot { it.artists.any { artist -> artist.normalized() in state.blockedArtists } }
            .distinctBy { it.id }
            .map { track -> track to score(track, seed, state) }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(0))
            .map { it.first }
            .toList()

    private fun score(track: MusicTrack, seed: MusicTrack?, state: MusicBrainState): Double {
        val taste = state.items[track.id]
        val artistScore = track.artists.sumOf { state.artistAffinity[it.normalized()] ?: 0.0 }
        val seedSimilarity = if (seed == null) 0.0 else similarity(track, seed)
        val novelty = if (track.id in state.recentIds) -1.5 else 0.35
        val popularity = ln(1.0 + track.popularity.coerceAtLeast(0.0)) * 0.05
        return seedSimilarity * 3.0 + artistScore + novelty + popularity +
            (taste?.likes ?: 0) * 4.0 + (taste?.completions ?: 0) * 0.8 +
            (taste?.starts ?: 0) * 0.1 - (taste?.skips ?: 0) * 1.5 - (taste?.dislikes ?: 0) * 5.0
    }

    private fun similarity(a: MusicTrack, b: MusicTrack): Double {
        val artists = a.artists.map(String::normalized).intersect(b.artists.map(String::normalized).toSet()).size.toDouble()
        val tags = a.tags.map(String::normalized).intersect(b.tags.map(String::normalized).toSet()).size.toDouble()
        return artists + tags * 0.35 + if (a.albumId != null && a.albumId == b.albumId) 0.5 else 0.0
    }

    private fun affinityDelta(event: ListenEvent): Double = when (event.type) {
        ListenEventType.LIKED -> 3.0
        ListenEventType.COMPLETED -> 1.0
        ListenEventType.STARTED -> 0.15
        ListenEventType.SKIPPED -> -0.8
        ListenEventType.DISLIKED -> -3.0
        ListenEventType.STOPPED -> max(0.0, event.progressPercent - 0.5)
    }
}

private fun String.normalized() = trim().lowercase()

@kotlinx.serialization.Serializable
data class MusicTrack(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val albumId: String? = null,
    val tags: Set<String> = emptySet(),
    val popularity: Double = 0.0,
)

enum class ListenEventType { STARTED, STOPPED, COMPLETED, SKIPPED, LIKED, DISLIKED }

data class ListenEvent(val track: MusicTrack, val type: ListenEventType, val progressPercent: Double = 0.0)

@kotlinx.serialization.Serializable
data class TrackTaste(
    val starts: Int = 0,
    val completions: Int = 0,
    val skips: Int = 0,
    val likes: Int = 0,
    val dislikes: Int = 0,
)

@kotlinx.serialization.Serializable
data class MusicGraph(
    val tracks: Map<String, MusicTrack> = emptyMap(),
    val edges: Map<String, Set<String>> = emptyMap(),
) {
    fun addTrack(track: MusicTrack): MusicGraph {
        val nextTracks = tracks + (track.id to track)
        val nextEdges = edges.toMutableMap()
        val neighbors = tracks.values.filter { other ->
            other.id != track.id && (
                other.artists.map(String::normalized).intersect(track.artists.map(String::normalized).toSet()).isNotEmpty() ||
                    (track.albumId != null && track.albumId == other.albumId) ||
                    other.tags.map(String::normalized).intersect(track.tags.map(String::normalized).toSet()).isNotEmpty()
                )
        }.map { it.id }.toSet()
        nextEdges[track.id] = (nextEdges[track.id].orEmpty() + neighbors)
        neighbors.forEach { id -> nextEdges[id] = nextEdges[id].orEmpty() + track.id }
        return copy(tracks = nextTracks, edges = nextEdges)
    }

    fun relatedIds(seed: MusicTrack): Set<String> = edges[seed.id].orEmpty()
}

@kotlinx.serialization.Serializable
data class MusicBrainState(
    val items: Map<String, TrackTaste> = emptyMap(),
    val artistAffinity: Map<String, Double> = emptyMap(),
    val blockedArtists: Set<String> = emptySet(),
    val recentIds: List<String> = emptyList(),
    val graph: MusicGraph = MusicGraph(),
)

interface MusicBrainStore {
    suspend fun read(): MusicBrainState
    suspend fun update(transform: (MusicBrainState) -> MusicBrainState)
}

class InMemoryMusicBrainStore(initial: MusicBrainState = MusicBrainState()) : MusicBrainStore {
    private var state = initial
    override suspend fun read(): MusicBrainState = state
    override suspend fun update(transform: (MusicBrainState) -> MusicBrainState) { state = transform(state) }
}
