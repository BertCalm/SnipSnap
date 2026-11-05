package com.snipsnap.app

import android.app.Application

/** The study needs audio focus, with no capture, MIDI or sampler services. */
class CreaseStudyApplication : Application() {
    override fun onCreate() { super.onCreate();AudioFocus.install(this) }
}
