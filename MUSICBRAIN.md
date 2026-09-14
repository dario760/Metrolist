# MusicBrain para Metrolist

Módulo fuente Kotlin para añadir un recomendador musical local y una radio infinita que inyecta canciones en la cola de Metrolist. No modifica Metrolist automáticamente: incluye un motor desacoplado, persistencia DataStore, preferencias y un adaptador de cola.

## Capacidades incluidas

- **MusicBrain local:** aprende de starts, reproducciones completadas, stops, skips, likes y dislikes.
- **Ranking por gusto:** pondera afinidad de artista, similitud de artista/álbum/tags, novedad, popularidad, likes y skips.
- **Grafo musical local:** relaciona canciones por artista, álbum y tags; guarda artistas, álbumes y pistas para descubrir relacionados.
- **Radio infinita:** `nextRadioBatch()` genera lotes y `EndlessRadioController` los añade antes de que termine la cola.
- **Cola append-only:** usa `PlayerConnection.addToQueue()` y no reemplaza la cola existente.
- **On Repeat:** recupera las pistas más reproducidas/completadas.
- **Daily Mix:** distribuye canciones de varias pistas/artistas en lugar de repetir un solo artista.
- **Contenido relacionado:** `MusicCandidateProvider` permite usar el parser/endpoint de relacionados de Metrolist y combinarlo con el grafo local.
- **Feedback de artistas:** bloquea artistas con `setArtistBlocked()` y los excluye del ranking.
- **Persistencia:** guarda el estado del cerebro en DataStore mediante JSON.
- **Ajustes:** MusicBrain, radio infinita y tamaño de lote.

## Archivos

- `MusicBrainEngine.kt`: aprendizaje, ranking, grafo, radio, On Repeat, Daily Mix y artistas bloqueados.
- `MusicBrainIntegration.kt`: DataStore, settings, proveedor de candidatos, adaptador Media3 e inyector append-only.

## Dependencias

Metrolist ya usa las dependencias necesarias:

- Kotlin coroutines (`Mutex`, `Flow`).
- `kotlinx.serialization`.
- AndroidX DataStore Preferences.
- AndroidX Media3 (`MediaItem`).

## Instalación

Copia ambos `.kt` bajo:

```text
app/src/main/kotlin/com/metrolist/music/musicbrain/
```

## Crear el motor

```kotlin
val musicBrain = MusicBrainEngine(DataStoreMusicBrainStore(context))
```

## Registrar escucha

Conecta los callbacks de `Player.Listener` o del servicio:

```kotlin
scope.launch { musicBrain.record(ListenEvent(track, ListenEventType.STARTED)) }
scope.launch { musicBrain.record(ListenEvent(track, ListenEventType.COMPLETED, 1.0)) }
scope.launch { musicBrain.record(ListenEvent(track, ListenEventType.SKIPPED, progressPercent)) }
scope.launch { musicBrain.record(ListenEvent(track, ListenEventType.LIKED)) }
```

## Inyectar radio en la cola

`PlayerConnection` ya expone `addToQueue(items)`. Crea el puente:

```kotlin
val sink = QueueSink { items -> playerConnection.addToQueue(items) }
```

Implementa candidatos reutilizando el repositorio de relacionados/InnerTube de Metrolist:

```kotlin
val provider = MusicCandidateProvider { seed, limit ->
    relatedRepository.relatedTracks(seed.id, limit).map { song ->
        MusicTrack(
            id = song.id,
            title = song.title,
            artists = song.artists.map { it.name },
            albumId = song.album?.id,
            popularity = song.viewCount?.toDouble() ?: 0.0,
        )
    }
}
```

Construye el inyector y el controlador:

```kotlin
val injector = MusicBrainQueueInjector(
    engine = musicBrain,
    candidates = provider,
    sink = sink,
    toMediaItem = { track -> track.toMediaItem() },
)
val radio = EndlessRadioController(injector, refillThreshold = 3)
```

En un callback de posición de cola, añade un lote solo cuando quedan pocas pistas:

```kotlin
radio.onQueuePosition(
    current = currentTrack,
    remainingItems = player.mediaItemCount - player.currentMediaItemIndex - 1,
    batchSize = settings.batchSize,
)
```

El controlador evita rellenar dos veces para la misma canción. Llama `resetForNewSession()` al comenzar una playlist/álbum o cuando el usuario desactive radio.

## On Repeat y Daily Mix

```kotlin
val onRepeat = musicBrain.onRepeat(limit = 20)
val mix = musicBrain.dailyMix(candidateTracks, limit = 25)
val localRelated = musicBrain.localRelated(currentTrack, limit = 20)
```

Combina `localRelated` con los resultados frescos del proveedor remoto y pasa el conjunto a `nextRadioBatch()` para obtener el ranking final.

## Añadir ajustes

Metrolist usa `PreferenceKeys.kt` y DataStore. Las claves incluidas son:

```kotlin
MusicBrainSettings.enabled
MusicBrainSettings.endlessRadio
MusicBrainSettings.batchSize
```

Expón dos switches en ajustes:

- `MusicBrain`: activa/desactiva el ranking local.
- `Radio infinita`: añade canciones relacionadas automáticamente.

El lote se limita a 4–30 elementos y por defecto es 12.

## Reglas de integración

1. El módulo no hace red por sí mismo: reutiliza autenticación, región, caché y extracción de Metrolist.
2. `MusicCandidateProvider` debe filtrar canciones no reproducibles, duplicadas y la canción semilla.
3. Respeta shuffle/repeat de Metrolist; no inyectes radio cuando el usuario está en una playlist/álbum explícito, salvo que el switch lo permita.
4. En `Listen Together` guest, deja que `PlayerConnection` aplique su bloqueo normal.
5. Usa `Song.toMediaItem()` de Metrolist en producción para conservar artwork, explicit flag y metadata completa; `MusicTrack.toMediaItem()` es solo fallback.
6. Limita el tamaño del grafo y de `recentIds` si quieres reducir almacenamiento en dispositivos con mucho historial.

## Licencia

Código fuente preparado para integrarse en Metrolist. Respeta la GPL-3.0 del proyecto y conserva sus avisos de copyright al distribuir el APK.
