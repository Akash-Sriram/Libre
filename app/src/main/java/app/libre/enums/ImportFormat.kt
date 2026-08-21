package app.libre.enums

import androidx.annotation.StringRes
import app.libre.R

enum class ImportFormat(@StringRes val value: Int, val fileExtension: String) {
    YOUTUBECSV(R.string.import_format_youtube_csv, "csv"),
    PIPED(R.string.import_format_piped, "json"),
    URLSORIDS(R.string.import_format_list_of_urls, "txt")
}
