package com.sanhaengii.wearhealthsender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.DataItem
import androidx.health.services.client.HealthServices
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseTrackedStatus
import com.sanhaengii.wearhealthsender.ui.AlertScreen
import com.sanhaengii.wearhealthsender.ui.MainDashboard
import com.sanhaengii.wearhealthsender.ui.SosScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class ComposeMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var mainViewModel: MainViewModel

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val heartRate = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                .lastOrNull()?.value?.roundToInt()
            heartRate?.let { mainViewModel.updateHeartRate(it) }
        }
        override fun onAvailabilityChanged(dataType: androidx.health.services.client.data.DataType<*, *>, availability: androidx.health.services.client.data.Availability) {}
        override fun onExerciseEventReceived(event: androidx.health.services.client.data.ExerciseEvent) {}
        override fun onLapSummaryReceived(lapSummary: androidx.health.services.client.data.ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            mainViewModel = viewModel()
            
            // 초기값 설정 및 Wearable 데이터 로드
            LaunchedEffect(Unit) {
                // ... (existing logic)
            }

            MaterialTheme {
                var isAnomalyDetected by remember { mutableStateOf(false) }
                var alertMessage by remember { mutableStateOf("심박수 과부하! 휴식이 필요합니다.") }

                val pagerState = rememberPagerState(pageCount = { 2 })

                if (isAnomalyDetected) {
                    AlertScreen(message = alertMessage, isWarning = true)
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> MainDashboard(
                                bpm = mainViewModel.bpm,
                                eta = mainViewModel.eta,
                                distance = mainViewModel.distance
                            )
                            1 -> SosScreen(
                                isPaused = mainViewModel.isPaused,
                                isSosReporting = mainViewModel.isSosReporting,
                                onPauseToggle = { mainViewModel.togglePause() },
                                onSosClick = { 
                                    mainViewModel.isSosReporting = true
                                    notifySosTriggered()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun notifySosTriggered() {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@ComposeMainActivity).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@ComposeMainActivity)
                        .sendMessage(node.id, "/sos_triggered", "SOS".toByteArray())
                        .await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDataChanged(dataEvents: com.google.android.gms.wearable.DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                if (path == "/hiking_info") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val eta = dataMap.getString("eta", "-")
                    val distance = dataMap.getString("distance", "-")
                    val bpm = dataMap.getInt("bpm", 0)
                    mainViewModel.updateEta(eta)
                    mainViewModel.updateDistance(distance)
                    if (bpm > 0) mainViewModel.updateHeartRate(bpm)
                } else if (path == "/sos_status") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val status = dataMap.getString("status")
                    if (status == "finished") {
                        mainViewModel.resetSosReporting()
                    }
                }
            }
        }
    }

    @android.annotation.SuppressLint("RestrictedApi", "WrongConstant")
    private fun startHeartRateUpdates() {
        scope.launch {
            try {
                // 현재 진행 중인 운동이 있는지 확인
                val exerciseInfo = exerciseClient.getCurrentExerciseInfoAsync().await()
                
                exerciseClient.setUpdateCallback(exerciseUpdateCallback)
                
                if (exerciseInfo.exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
                    // 이미 운동이 진행 중이면 콜백만 등록 (이미 위에서 등록함)
                } else {
                    // 운동이 없으면 새로 시작
                    exerciseClient.startExerciseAsync(
                        ExerciseConfig(
                            exerciseType = ExerciseType.WALKING,
                            dataTypes = setOf(DataType.HEART_RATE_BPM),
                            isAutoPauseAndResumeEnabled = false,
                            isGpsEnabled = false
                        )
                    ).await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback)
    }
}

