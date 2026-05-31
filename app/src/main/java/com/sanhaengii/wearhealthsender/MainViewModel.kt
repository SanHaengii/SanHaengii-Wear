package com.sanhaengii.wearhealthsender

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    var bpm by mutableIntStateOf(0)
    var eta by mutableStateOf("-")
    var distance by mutableStateOf("-")
    var isPaused by mutableStateOf(false)
    var isHikingActive by mutableStateOf(false)
    var isSosReporting by mutableStateOf(false)

    fun resetSosReporting() {
        isSosReporting = false
    }

    fun updateHeartRate(newBpm: Int) {
        bpm = newBpm
    }

    fun updateEta(newEta: String) {
        eta = newEta
    }

    fun updateDistance(newDistance: String) {
        distance = newDistance
    }

    fun togglePause() {
        isPaused = !isPaused
    }

    fun updateHikingActive(active: Boolean) {
        isHikingActive = active
    }
}
