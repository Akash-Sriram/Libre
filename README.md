# Libre

A lean, privacy-focused media player designed for a pure, high-performance, music-first experience.

> **Note:** Libre is a hard fork of LibreTube, heavily modified and customized as desired to provide a fast, music-first media player powered natively by **LibreTube's fork of NewPipeExtractor** and dual-source streaming.

## Core Features

- **Dual-Source Streaming:** Stream seamlessly from YouTube (via LibreTube's NewPipeExtractor fork) and JioSaavn.
- **Offline Media Integration:** Choose a custom local folder to seamlessly index and play your offline music library right alongside your online streams.
- **Music-First UX:** "Music" category videos auto-route to the background audio player. Search tabs strictly prioritize albums and songs.
- **Interactive Synced Lyrics:** Premium-scrolled synced lyrics overlay that smoothly flips directly from the thumbnail view, featuring slowed-down gliding animations and fluid ArgbEvaluator alpha/color cross-fades.
- **Smart Caching Engine:** Features a native ExoPlayer `SimpleCache` integration that auto-caches up to 512MB of played audio segments locally for zero data usage and instant loading on replays.
- **Two-Tier Lyrics Cache:** Memory (LruCache) and disk JSON caching for fetched lyrics to prevent network pool starvation with audio streaming threads.
- **Silent Launch Auto-Updater:** Silently checks for updates on launch and displays a native Material Dialog only when an update is available.
- **Robust Data Management:** 
  - **Auto-Backups:** Automated, daily background backups with strict auto-pruning to guarantee storage limits (toggleable in Settings).
  - **Metadata Sanitizer:** Scans local playlists on startup to clean up "off" YouTube metadata (stripping titles, correcting artist lists, and resolving missing album names) directly in the Room SQLite database.
- **Ergonomic Mini-Player:** A tactile, floating mini-player designed for seamless audio/video transitions.

## License

Libre is Free Software under the GNU General Public License version 3.
