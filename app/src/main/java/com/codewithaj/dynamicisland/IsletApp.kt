package com.codewithaj.dynamicisland

import android.app.Application

/** Runs in both processes, so keep it trivial: no eager work here. */
class IsletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
