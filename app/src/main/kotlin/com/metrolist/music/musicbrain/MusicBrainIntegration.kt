package com.metrolist.music.musicbrain

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.musicBrainDataStore by preferencesDataStore(name = "music_brain")

class DataStoreMusicBrainStore(private val context: Context) : MusicBrainStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateKey = stringPreferencesKey("state_v1")

    override suspend fun read(): MusicBrainState {
        val raw = context.musicBrainDataStore.data.first()[stateKey] ?: return MusicBrainState()
        return runCatching { json.decodeFromString<MusicBrainState>(raw) }.getOrDefault(MusicBrainState())
    }

    override suspend fun update(transform: (MusicBrainState) -> MusicBrainState) {
        context.musicBrainDataStore.edit { prefs ->
            val old = prefs[stateKey]?.let { runCatching { json.decodeFromString<MusicBrainState>(it) }.getOrNull() }
                ?: MusicBrainState()
            prefs[stateKey] = json.encodeToString(transform(old))
        }
    }
}

object MusicBrainSettings {
    val enabled = booleanPreferencesKey("music_brain_enabled")
    val endlessRadio = booleanPreferencesKey("music_brain_endless_radio")
    val batchSize = stringPreferencesKey("music_brain_batch_size")

    suspend fun read(context: Context): Settings = context.musicBrainDataStore.data.first().let { prefs ->
        Settings(
            enabled = prefs[enabled] ?: true,
            endlessRadio = prefs[endlessRadio] ?: true,
            batchSize = prefs[batchSize]?.toIntOrNull()?.coerceIn(4, 30) ?: 12,
        )
    }

    suspend fun write(context: Context, settings: Settings) {
        context.musicBrainDataStore.edit { prefs ->
            prefs[enabled] = settings.enabled
            prefs[endlessRadio] = settings.endlessRadio
            prefs[batchSize] = settings.batchSize.coerceIn(4, 30).toString()
        }
    }

    data class Settings(
        val enabled: Boolean = true,
        val endlessRadio: Boolean = true,
        val batchSize: Int = 12,
    )
}

/** The only app-specific bridge needed by the engine. */
fun interface MusicCandidateProvider {
    suspend fun related(seed: MusicTrack, limit: Int): List<MusicTrack>
}

fun interface QueueSink {
    fun append(items: List<MediaItem>)
}

class MusicBrainQueueInjector(
    private val engine: MusicBrainEngine,
    private val candidates: MusicCandidateProvider,
    private val sink: QueueSink,
    private val toMediaItem: (MusicTrack) -> MediaItem,
) {
    suspend fun injectAfter(current: MusicTrack, requestedSize: Int = 12): List<MusicTrack> {
        val pool = candidates.related(current, requestedSize * 3)
        val next = engine.nextRadioBatch(current, pool, requestedSize)
        if (next.isNotEmpty()) sink.append(next.map(toMediaItem))
        return next
    }
}

/** Convenience converter when the provider returns only basic metadata. */
fun MusicTrack.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri("https://music.youtube.com/watch?v=$id")
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artists.joinToString())
            .build(),
    )
    .build()


/** Prevents duplicate refills while the player approaches the end of the queue. */
class EndlessRadioController(
    private val injector: MusicBrainQueueInjector,
    private val refillThreshold: Int = 3,
) {
    private val refilledFor = mutableSetOf<String>()

    suspend fun onQueuePosition(current: MusicTrack, remainingItems: Int, batchSize: Int): List<MusicTrack> {
        if (remainingItems > refillThreshold || !refilledFor.add(current.id)) return emptyList()
        return injector.injectAfter(current, batchSize)
    }

    fun resetForNewSession() = refilledFor.clear()
}
