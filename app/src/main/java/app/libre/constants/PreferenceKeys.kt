package app.libre.constants

/**
 * Active keys for SharedPreferences in Libre
 */
object PreferenceKeys {

    // General & Localization
    const val LANGUAGE = "language"
    const val REGION = "region"
    const val UNLIMITED_SEARCH_HISTORY = "unlimited_search_history"
    const val AUDIO_ONLY_MODE = "audio_only_mode"
    const val AUTO_MUSIC_AUDIO_MODE = "auto_music_audio_mode"

    // Appearance & Library
    const val THEME_MODE = "theme_toggle"
    const val PURE_THEME = "pure_theme"
    const val ACCENT_COLOR = "accent_color"
    const val GRID_COLUMNS_PORTRAIT = "grid"
    const val GRID_COLUMNS_LANDSCAPE = "grid_landscape"
    const val APP_ICON = "icon_change"
    const val NEW_VIDEOS_BADGE = "new_videos_badge"
    const val PLAYLISTS_ORDER = "playlists_order"
    const val PLAYLIST_SORT_ORDER = "playlist_sort_order"
    const val SEARCH_SUGGESTIONS = "search_suggestions"
    const val SELECTED_FEED_FILTERS = "filter_feed"

    // Player Behavior & Playback
    const val AUTO_FULLSCREEN = "auto_fullscreen"
    const val AUTOPLAY = "autoplay"
    const val PLAYBACK_SPEED = "playback_speed"
    const val FULLSCREEN_ORIENTATION = "fullscreen_orientation"
    const val PAUSE_ON_SCREEN_OFF = "pause_screen_off"
    const val WATCH_POSITIONS = "watch_positions"
    const val SEARCH_HISTORY_TOGGLE = "search_history_toggle"
    const val WATCH_HISTORY_TOGGLE = "watch_history_toggle"
    const val SYSTEM_CAPTION_STYLE = "system_caption_style"
    const val RICH_CAPTION_RENDERING = "rich_caption_rendering"
    const val SEEK_INCREMENT = "seek_increment"
    const val DEFAULT_RESOLUTION = "default_res"
    const val DEFAULT_RESOLUTION_MOBILE = "default_res_mobile"
    const val BUFFERING_GOAL = "buffering_goal"
    const val PLAYER_AUDIO_QUALITY = "player_audio_quality"
    const val PLAYER_AUDIO_QUALITY_MOBILE = "player_audio_quality_mobile"
    const val DEFAULT_SUBTITLE = "default_subtitle"
    const val SKIP_BUTTONS = "skip_buttons"
    const val PLAYER_RESIZE_MODE = "current_player_resize_mode"
    const val SHOW_TIME_LEFT = "show_time_left"
    const val QUEUE_AUTO_INSERT_RELATED = "queue_insert_related_videos"
    const val AUTOPLAY_PLAYLISTS = "autoplay_playlists"
    const val PLAYER_SWIPE_CONTROLS = "player_swipe_controls"
    const val PLAYER_PINCH_CONTROL = "player_pinch_control"
    const val CAPTIONS_SIZE = "captions_size"
    const val DOUBLE_TAP_TO_SEEK = "double_tap_seek"
    const val LONG_PRESS_FAST_FORWARD = "long_press_fast_forward"
    const val ALTERNATIVE_PIP_CONTROLS = "alternative_pip_controls"
    const val SKIP_SILENCE = "skip_silence"
    const val AUTOPLAY_COUNTDOWN = "autoplay_countdown"
    const val AUTO_FULLSCREEN_SHORTS = "auto_fullscreen_shorts"
    const val PLAY_AUTOMATICALLY = "play_automatically"
    const val FULLSCREEN_GESTURES = "fullscreen_gestures"
    const val ALLOW_PLAYBACK_DURING_CALL = "playback_during_call"
    const val BEHAVIOR_WHEN_MINIMIZED = "behavior_when_minimized"
    const val REPEAT_MODE = "repeat_mode"

    // Storage, Backup & Updates
    const val AUTOMATIC_UPDATE_CHECKS = "automatic_update_checks"
    const val DATA_SAVER_MODE = "data_saver_mode_key"
    const val BACKUP_FOLDER_URI = "backup_folder_uri"
    const val ENABLE_AUTO_BACKUP = "enable_auto_backup"
    const val OFFLINE_SONGS_FOLDER_URI = "offline_songs_folder_uri"
    const val INCLUDE_TIMESTAMP_IN_BACKUP_FILENAME = "include_timestamp_in_filename"
    const val SHARE_WITH_TIME_CODE = "share_with_time_code"
    const val ERROR_LOG = "error_log"
    const val IMAGE_PROXY_URL = "image_proxy_url"
    const val LAST_SHOWN_INFO_MESSAGE_VERSION_CODE = "last_shown_info_message_version"
    const val PREFERENCE_VERSION = "PREFERENCE_VERSION"
}
