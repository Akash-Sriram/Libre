package app.libre.extensions

import android.content.res.Resources
import androidx.core.util.TypedValueCompat

/**
 * Convert dp to pixels
 */
fun Float.dpToPx(): Int {
    return (TypedValueCompat.dpToPx(this, Resources.getSystem().displayMetrics) + 0.5f).toInt()
}
