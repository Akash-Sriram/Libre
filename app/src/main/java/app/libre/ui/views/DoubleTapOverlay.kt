package app.libre.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import app.libre.databinding.DoubleTapOverlayBinding

class DoubleTapOverlay(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    val binding = DoubleTapOverlayBinding.inflate(LayoutInflater.from(context), this, true)
}
