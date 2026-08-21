package app.libre.extensions

import androidx.lifecycle.MutableLiveData

fun <T> MutableLiveData<T>.updateIfChanged(newValue: T) {
    if (value != newValue) value = newValue
}
