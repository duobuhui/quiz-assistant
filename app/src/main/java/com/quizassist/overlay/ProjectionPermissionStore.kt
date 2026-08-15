package com.quizassist.overlay

import android.content.Intent

object ProjectionPermissionStore {
    @Volatile
    var resultCode: Int = 0

    @Volatile
    var data: Intent? = null

    fun update(code: Int, intent: Intent?) {
        resultCode = code
        data = intent
    }

    fun clear() {
        resultCode = 0
        data = null
    }
}
