package com.looplingo.horizon.ui.common

import timber.log.Timber

object ProcessLogger {
    fun log(tag: String, message: String) {
        Timber.tag(tag).d(message)
    }
}
