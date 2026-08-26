# SanHaengii-Wear

**산행이(SanHaengii)** 프로젝트의 **스마트워치 앱** 레포지토리입니다.

Wear OS · Kotlin(Jetpack Compose) 기반 앱으로, 등산 중 심박수·산소포화도 등 생체데이터를 측정하고 이상징후를 감지하며, 모바일 앱과 연동해 잔여시간·거리 및 긴급신고를 처리합니다.

## 주요 기능

- **생체데이터 실시간 측정** — Health Services 기반 심박수, Samsung Health Sensor SDK 기반 산소포화도(SpO₂), 걸음 수 측정
- **모바일 연동** — 측정한 생체데이터를 Wear Data Layer로 모바일 앱에 실시간 전송, 잔여 거리·시간 등 산행 정보 수신
- **이상징후 감지 & 긴급신고(SOS)** — 이상 수치 감지 시 워치에서 카운트다운 알림을 띄우고, 확인이 없으면 자동으로 긴급신고를 전송
- **연결 끊김 대비 폴백** — 폰과의 연결이 끊기면 워치에서 직접 `/api/emergency`로 HTTPS 긴급신고 전송
- **보안 인증 저장** — 모바일에서 발급받은 JWT·사용자 ID를 Android Keystore(AES-GCM)로 암호화 저장

## 조직 내 레포지토리 구성

| 레포지토리 | 역할 |
| --- | --- |
| [SanHaengii-App](https://github.com/SanHaengii/SanHaengii-App) | 모바일 앱 — React Native / Expo |
| [SanHaengii-backend](https://github.com/SanHaengii/SanHaengii-backend) | 백엔드 API — FastAPI / Flask / Supabase |
| **SanHaengii-Wear** (본 레포) | 스마트워치 앱 — Wear OS / Kotlin |

## 현재 구조

- `ComposeMainActivity`: 화면, 권한 요청, 모바일 Data Layer 수신, SOS 흐름 조정
- `HealthTrackingService`: foreground service로 Health Services·걸음·Samsung SpO₂ 수집 및 `/health/live`, `/anomaly` 전송
- `EmergencyRepository`: 폰 연결 실패에 대비한 `/api/emergency` HTTPS 폴백
- `EmergencyStateMachine`: 카운트다운 → 전송 → 성공/실패 전이를 한 곳에서 관리
- `SecureCredentialStore`: 모바일 `/auth`로 받은 JWT와 사용자 ID를 Android Keystore AES-GCM으로 암호화 저장

체온 센서 구현은 아직 없으므로 `bodyTemp`는 `null`로 유지합니다. SpO₂도 Samsung Health Sensor SDK의 실제 측정값만 사용하며, 최근 15분을 넘긴 값은 폐기합니다. 모바일과의 기존 5필드 wire 형식에서는 미측정 값을 `0.0` sentinel로 전달하지만 앱 내부 이상징후 판정에는 사용하지 않습니다.

## 빌드

1. `local.properties.example`을 참고해 필요하면 `local.properties`에 `HEALTH_API_BASE_URL`을 설정합니다.
2. Android Studio에서 Gradle Sync 후 Wear OS 기기를 선택해 실행합니다.
3. 검증 명령은 `./gradlew testDebugUnitTest lintDebug assembleDebug`입니다.

모바일 앱과 Wear Data Layer를 실제 연동하려면 두 앱의 package name과 서명이 같아야 합니다. `~/sanhaengii-shared-debug.keystore`가 있으면 자동으로 공유 디버그 키를 사용하고, 없으면 일반 디버그 키로 빌드만 가능하며 모바일 메시지는 연결되지 않을 수 있습니다.

릴리스 빌드는 cleartext HTTP를 차단합니다. 디버그 빌드만 로컬 개발을 위해 cleartext와 사용자 설치 인증서를 허용합니다. JWT·사용자 ID는 `local.properties`나 `BuildConfig`에 넣지 않습니다.
