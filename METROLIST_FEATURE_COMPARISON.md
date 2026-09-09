# Implementation Plan: Focused Adaptation (Lyrics Fallback & Streaming Resilience)

> **User Feedback Incorporated**:
> Component 3 (M4A Tag & Cover Art Editor / `metrolist-coverart-lib`) has been **completely removed**.
> The plan is now strictly focused on the **two high-value core features**:
> 1. **Expanded Multi-Provider Lyrics Fallback Pipeline**
> 2. **YouTube Streaming Resilience & Cipher Handling**

---

## User Review Required

> [!NOTE]
> **Pure Kotlin / Network Scope**:
> With native M4A tagging removed, this plan requires **zero native C++ NDK code or `.so` libraries**. Everything is implemented cleanly in Kotlin using Libre's existing OkHttp / Retrofit and Media3 ExoPlayer stack.

---

## Open Questions

> [!NOTE]
> None. Scope is clear and strictly limited to the two approved features.

---

## Proposed Changes

---

### Component 1: Multi-Provider Lyrics Fallback Pipeline

Eliminate single points of failure in lyrics retrieval by extending [`LyricsFallbackHelper.kt`](file:///c:/Users/akash/Downloads/Project/Libre/app/src/main/java/app/libre/lyrics/LyricsFallbackHelper.kt):

#### [MODIFY] [LyricsFallbackHelper.kt](file:///c:/Users/akash/Downloads/Project/Libre/app/src/main/java/app/libre/lyrics/LyricsFallbackHelper.kt)
- **Paxsenix Provider (`fetchFromPaxsenix`)**:
  - Queries `https://lyrics.paxsenix.org` and Apple Music catalog endpoints.
  - Acts as a direct, independent backup for Apple Music TTML synced lyrics when the Boidu proxy (`lyrics-api.boidu.dev`) is down or blocked.
- **LyricsPlus / Binimum Provider (`fetchFromLyricsPlus`)**:
  - Connects to the Binimum API (`https://lyrics-api.binimum.org`) and failover mirror list (`https://lyricsplus.binimum.org`, `https://lyricsplus.atomix.one`, `https://lyricsplus.prjktla.my.id`).
  - Supports ISRC lookup and Title + Artist searches for Spotify/Musixmatch synced lyrics with line-by-line and word-sync parsing.
- **YouTube Subtitles Provider (`fetchFromYouTubeSubtitles`)**:
  - Extracts closed captions / auto-generated transcript directly from YouTube video subtitles when no commercial database (LRCLIB, Apple, Spotify) contains the song.
  - Converts caption timestamps into standard synchronized LRC.
- **Fallback Execution Order**:
  `LRCLIB -> BetterLyrics (Apple TTML) -> Paxsenix -> KuGou -> LyricsPlus -> YouTube Subtitles`.

---

### Component 2: YouTube Streaming Resilience & Cipher Updates

Protect Libre against breaking YouTube cipher rotations, bandwidth throttling (~40 KB/s buffering), and HTTP 403 Forbidden errors:

#### [NEW] [StreamFallbackResolver.kt](file:///c:/Users/akash/Downloads/Project/Libre/app/src/main/java/app/libre/player/StreamFallbackResolver.kt)
- Secondary stream resolution engine inspired by `faraday` and `innertubex`:
  - Retrieves remote cipher deobfuscation rules (`sig`, `nClass`, `sts`) to correctly resolve `n`-sig parameters when NewPipeExtractor's hardcoded regular expressions break due to a YouTube `player.js` rotation.
  - Generates valid PO-Tokens and formats for high-bitrate audio streams.
  - Feeds into Libre's existing [`SabrClient.kt`](file:///c:/Users/akash/Downloads/Project/Libre/app/src/main/java/app/libre/player/parser/SabrClient.kt) and [`SabrMediaSource.kt`](file:///c:/Users/akash/Downloads/Project/Libre/app/src/main/java/app/libre/player/SabrMediaSource.kt) when YouTube requires UMP chunked streaming.

#### [MODIFY] [NewPipeMediaServiceRepository.kt](file:///c:/Users/akash/Downloads/Project/Libre/app/src/main/java/app/libre/api/NewPipeMediaServiceRepository.kt)
- In `getStreams(videoId)`: If `StreamInfo.getInfo` fails with an extraction error or cipher signature error, automatically query `StreamFallbackResolver.resolveStream(videoId)` as a transparent failover.

#### [MODIFY] [OnlinePlayerService.kt](file:///c:/Users/akash/Downloads/Project/Libre/app/src/main/java/app/libre/services/OnlinePlayerService.kt)
- In ExoPlayer's error listener: Detect playback errors caused by HTTP 403 Forbidden or expired YouTube CDN URLs, request a fresh stream URL from `StreamFallbackResolver`, and resume playback seamlessly.

---

## Verification Plan

### Automated Tests
- Build verification:
  ```powershell
  ./gradlew :app:assembleDebug
  ```
- Unit tests for lyrics providers and fallback cascade:
  ```powershell
  ./gradlew testDebugUnitTest
  ```

### Manual Verification
1. **Multi-Provider Synced Lyrics**:
   - Play a newly released or niche track that returns nothing on LRCLIB.
   - Verify that the lyrics sheet in `AudioPlayerFragment` automatically loads synced lyrics from Paxsenix, LyricsPlus, or YouTube Subtitles.
2. **Streaming Resilience & Cipher Fallback**:
   - Play a YouTube track that uses signature cipher deobfuscation.
   - Verify playback begins immediately without infinite buffering or HTTP 403 errors.
   - Confirm Libre's existing JioSaavn streaming and local audio playback remain completely unaffected.
