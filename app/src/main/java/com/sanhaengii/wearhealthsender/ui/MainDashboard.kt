package com.sanhaengii.wearhealthsender.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 1. 메인 대시보드 화면 (ComposeMainActivity에서 직접 호출됨)
@Composable
fun MainDashboard(bpm: Int, eta: String, distance: String) {
    // 심박수에 따른 하트 색상 변경 로직
    val heartColor = when {
        bpm >= 150 -> Color.Red
        bpm >= 125 -> Color.Yellow
        else -> Color.Green
    }

    // 하트 깜빡임 애니메이션 (InfiniteTransition)
    val infiniteTransition = rememberInfiniteTransition(label = "heartBlink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartAlpha"
    )

    // 스마트워치의 둥근 화면에 맞춘 중앙 정렬 레이아웃
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black), // 번인(Burn-in) 방지용 올블랙 배경
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 하트 아이콘
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "Heart Rate",
            tint = heartColor,
            modifier = Modifier
                .size(40.dp)
                .alpha(alpha)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 현재 심박수 수치
        Text(
            text = "$bpm BPM",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 산행 ETA 및 거리 정보
        val etaDisplay = if (eta == "-" || eta.isEmpty()) "-" else if (eta.contains("분")) eta else "${eta}분"
        val distDisplay = if (distance == "-" || distance.isEmpty()) "-" else if (distance.contains("km")) distance else "${distance}km"

        Text(text = "⏰ 남은 시간: $etaDisplay", color = Color.LightGray, fontSize = 12.sp)
        Text(text = "🥾 잔여 거리: $distDisplay", color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
fun BackendTestEntryScreen(onOpenBackendTest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Backend Test",
            tint = Color(0xFF4ADE80),
            modifier = Modifier.size(42.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "백엔드 전송",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onOpenBackendTest,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4ADE80)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "테스트 열기",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// 2. 특이사항 발생 시 띄워줄 알림 화면 (나중에 네비게이션으로 연결)
@Composable
fun AlertScreen(message: String, isWarning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val tint = if (isWarning) Color.Red else Color.White

        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Alert",
            tint = tint,
            modifier = Modifier.size(60.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = tint,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// 3. SOS 구조 조작 화면 (위아래 반반 분할 및 크기 조정)
@Composable
fun SosScreen(
    isHikingActive: Boolean,
    isSosReporting: Boolean,
    onHikingToggle: () -> Unit,
    onSosClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sosBlink")
    var isPressingSos by remember { mutableStateOf(false) }

    val sosAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPressingSos) 0.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sosAlpha"
    )

    if (isSosReporting) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Reporting",
                tint = Color.Red,
                modifier = Modifier.size(60.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "신고 접수중",
                color = Color.Red,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 상단: 산행 시작 / 정지 버튼
            Button(
                onClick = onHikingToggle,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isHikingActive) Color.DarkGray else Color(0xFF2196F3)
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isHikingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isHikingActive) "Stop hiking" else "Start hiking",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isHikingActive) "정지" else "시작",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 하단: 긴급 신고 버튼 (커스텀 5초 롱프레스)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .alpha(if (isPressingSos) sosAlpha else 1f)
                    .pointerInput(Unit) {
                        coroutineScope {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        isPressingSos = true
                                        val job = launch {
                                            delay(5000) // 5초 대기
                                            if (isPressingSos) {
                                                onSosClick()
                                                isPressingSos = false
                                            }
                                        }
                                        
                                        // Release 또는 Cancel 대기
                                        while (isPressingSos) {
                                            val nextEvent = awaitPointerEvent()
                                            if (nextEvent.type == PointerEventType.Release || 
                                                nextEvent.type == PointerEventType.Exit) {
                                                job.cancel()
                                                isPressingSos = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                androidx.wear.compose.material.Card(
                    onClick = { /* pointerInput에서 처리함 */ },
                    backgroundPainter = androidx.wear.compose.material.CardDefaults.cardBackgroundPainter(
                        startBackgroundColor = Color.Red,
                        endBackgroundColor = Color.Red
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "SOS",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "구조",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
