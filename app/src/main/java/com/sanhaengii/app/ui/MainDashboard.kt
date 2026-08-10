package com.sanhaengii.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.text.style.TextAlign
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

// 2. 이상 징후 감지 시 띄워줄 알림 화면 (30초 카운트다운 + 즉시신고/취소 버튼)
@Composable
fun AlertScreen(
    message: String,
    isWarning: Boolean,
    countdown: Int? = null,
    cancelLabel: String = "취소",
    emergencySendState: String = "idle", // "idle" / "sending" / "success" / "failed"
    onConfirm: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    // "전송 중" 깜빡임 애니메이션
    val infiniteTransition = rememberInfiniteTransition(label = "sendAnim")
    val sendingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "sendingAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (emergencySendState) {
            // ── 전송 중 ──────────────────────────────────────────
            "sending" -> {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Sending",
                    tint = Color.Yellow.copy(alpha = sendingAlpha),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "구조 신고\n전송 중…",
                    color = Color.Yellow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            // ── 전송 완료 ─────────────────────────────────────────
            "success" -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✓ 신고 전송 완료",
                    color = Color(0xFF4CAF50),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "구조대가 출동합니다",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                )
            }
            // ── 전송 실패 ─────────────────────────────────────────
            "failed" -> {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Failed",
                    tint = Color.Red,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "전송 실패",
                    color = Color.Red,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "119에 직접 전화하세요",
                    color = Color.Yellow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (onConfirm != null) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("재시도", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // ── 기본 이상징후 알림 (idle) ─────────────────────────
            else -> {
                val tint = if (isWarning) Color.Red else Color.White
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Alert",
                    tint = tint,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    color = tint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (countdown != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${countdown}초 후 자동 신고",
                        color = Color.Yellow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (onConfirm != null) {
                            Button(
                                onClick = onConfirm,
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("신고", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onCancel != null) {
                            Button(
                                onClick = onCancel,
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(cancelLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// 3. SOS 구조 조작 화면 (위아래 반반 분할 및 크기 조정)
@Composable
fun SosScreen(
    isHikingActive: Boolean,
    isPaused: Boolean,
    isSosReporting: Boolean,
    onHikingToggle: () -> Unit,
    onAbort: () -> Unit,
    onSosClick: () -> Unit
) {
    // 진행 중(=산행 활성 && 일시정지 아님)일 때만 '정지' 표시, 그 외엔 시작/재개
    val isRunning = isHikingActive && !isPaused
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
            // 상단: [중단하기(왼쪽)] [시작/정지(오른쪽)] — 가로로 절반씩 배치
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 중단하기: 산행 완전 종료(기록 저장). 산행 중일 때만 활성
                Button(
                    onClick = onAbort,
                    enabled = isHikingActive,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color.DarkGray
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Abort hiking",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "중단",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 시작 / 정지(일시정지) / 재개
                Button(
                    onClick = onHikingToggle,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isRunning) Color.DarkGray else Color(0xFF2196F3)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isRunning) "Pause hiking" else "Start or resume hiking",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isRunning) "정지" else "재개",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
