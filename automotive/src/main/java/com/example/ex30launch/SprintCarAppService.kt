package com.example.ex30launch

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class SprintCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Debug derlemede emulator/yerel host'lara izin ver; yayin derlemesinde
        // yalnizca imzasi dogrulanan bilinen host'lar baglanabilsin (prompt.md §7.2).
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = SprintSession()
}

class SprintSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SprintScreen(carContext)
}
