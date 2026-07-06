# capullo-source-telegram

Telegram/TDLib **media source** for the [Capullo Audio Platform](https://github.com/capullo-tech) -
Layer 3 (the integrator). It treats a Telegram group/channel as a music library and implements the
`capullo-audio-contracts` SPI so the shared `capullo-audio` delivery engine can play and broadcast it.

It is the platform's **contract-stability anchor**: unlike `capullo-source-radiobrowser`
(instant stream URL), here `mediaRequestFor(id)` **suspends on a real TDLib download** before
returning the local file URI, and `onQueueAdvanced` drives Telecloud's **2-track lookahead prefetch**.
The `src/test` driver test exercises that feedback path against a fake TDLib client - the behavioural
validation radiobrowser only had at the type level.

## Layout

| Module | What |
|---|---|
| `:capullo-source-telegram` | The library: `TelegramSource` (`MediaSourceProvider` + `NowPlayingSource`), TDLib client, chat sync, Room track index, on-demand download + 2-track prefetch. Namespace `tech.capullo.source.telegram`. |
| `:app` | Minimal harness proving the library links and assembles. |

TDLib (the `org.drinkless.tdlib` Java API + prebuilt `libtdjni.so`) comes from the
[`lib-tdlib-android`](https://github.com/capullo-tech/lib-tdlib-android) jitpack AAR (Layer 0) - a
normal dependency, no `setup_tdlib.sh` / git-lfs.

## The contract seam

```kotlin
class TelegramSource(scope, downloadManager, metadataExtractor) :
    MediaSourceProvider, NowPlayingSource {
    suspend fun mediaRequestFor(id): MediaRequest   // awaits the TDLib download → file:// URI (throws if deleted)
    fun queue(): PlaybackQueue                       // finite playlist, isRotating = false
    fun onQueueAdvanced(index)                        // fire-and-forget 2-track lookahead prefetch
    val nowPlaying: StateFlow<NowPlaying>            // DB tags immediately, ID3 tags + cover art post-download
}
```

**Validated contract note:** for a *fetch-based* source the now-playing side effect lives in
`mediaRequestFor` (ID3 tags + art are only readable once the file is on disk), not in `onQueueAdvanced`
as it does for live radio. This implies the engine must call `mediaRequestFor(idAt(i))` **before**
`onQueueAdvanced(i)`. No contract signature change was required - the non-suspend, fire-and-forget
`onQueueAdvanced` is the correct shape for cancellable prefetch. See
`src/test/.../TelegramSourceContractTest`.

## Build

```bash
./gradlew :capullo-source-telegram:testDebugUnitTest      # the contract-validation driver
./gradlew :app:assembleDebug                              # harness APK
```

TDLib resolves from `com.github.capullo-tech:lib-tdlib-android` (jitpack) - nothing to populate.

The library is DI-free (no Hilt): the consuming app supplies `Context`, credentials
(`TelegramCredentials`), and a coroutine scope.

**Security:** the TDLib local database (login session + cached chats) is **encrypted at rest by
default** - `TelegramCredentials` *requires* a `databaseEncryptionKey` (32 random bytes; use
`TelegramCredentials.newDatabaseEncryptionKey()`), which the app generates once and keeps in the
Android Keystore / `EncryptedSharedPreferences`. It is not a user-facing password - encryption is
transparent. There is no cleartext path. Consuming apps should also set `allowBackup=false` (or
exclude the `tdlib/` dir) so the session isn't captured in cloud backups. Dev builds resolve `capullo-audio-contracts` from a
sibling checkout (composite build); release/jitpack builds resolve it from
`com.github.capullo-tech:capullo-audio-contracts`.

## License

Copyright 2026 capullo-tech. Licensed under GPLv3 - see [`LICENSE`](LICENSE). TDLib is bundled (under the Boost Software License) via the [`lib-tdlib-android`](https://github.com/capullo-tech/lib-tdlib-android) dependency.
