package com.itexpert120.attendanceManager

import android.app.Application

class AttendanceApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startup()
    }
}
