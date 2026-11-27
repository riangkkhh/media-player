package com.mont.mediaplayer

import android.app.Application

class MediaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
