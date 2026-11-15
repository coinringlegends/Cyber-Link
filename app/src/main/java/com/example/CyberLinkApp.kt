package com.example

import android.app.Application
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class CyberLinkApp : Application() {

    lateinit var container: CyberLinkContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidGraphicFactory.createInstance(this)
        container = CyberLinkContainer(this)
    }
}
