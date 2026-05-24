# Wear Health Sender

SanHaengii의 실제 센서 연동 전 검증을 위한 Wear OS 테스트 앱입니다. 이 브랜치는 Wear OS의 Health Services를 사용해 에뮬레이터 또는 워치에서 운동 데이터를 받고, `POST /health/data`로 백엔드에 전송합니다.

## 구성

- Native Android / Wear OS 프로젝트
- Kotlin
- Compose 미사용, 기본 Android View UI
- Wear OS Health Services SDK 사용
- 외부 HTTP 라이브러리 미사용, `HttpURLConnection`으로 POST 전송
- 기본 서버: `https://web-production-94f63.up.railway.app`
- endpoint: `POST /health/data`

전송 JSON 형태:

```json
{
  "measured_at": "2026-05-23T21:00:00+09:00",
  "heart_rate": 120,
  "steps": 800,
  "calories": 12.4,
  "spo2": null,
  "body_temp": null,
  "blood_pressure_systolic": null,
  "blood_pressure_diastolic": null
}
```

Health Services 에뮬레이터 synthetic data는 심박수와 걸음 관련 값 중심으로 테스트할 수 있습니다. SpO2, 체온, 혈압은 일반 Wear OS Health Services 운동 데이터에서 안정적으로 제공되는 값이 아니라 현재 앱에서는 `null`로 보냅니다.

## Android Studio에서 실행

1. Android Studio에서 이 폴더를 엽니다.
2. Gradle sync가 끝날 때까지 기다립니다.
3. Wear OS 에뮬레이터를 선택합니다.
4. Run 버튼을 눌러 `app`을 실행합니다.

터미널에서 빌드만 확인하려면:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Wear OS 에뮬레이터 만들기

1. Android Studio 오른쪽 위 Device Manager를 엽니다.
2. `+` 버튼을 누르고 Wear OS 기기를 선택합니다. 예: Pixel Watch 계열
3. Wear OS 시스템 이미지를 선택합니다. 가능하면 Wear OS 4 이상 이미지를 권장합니다.
4. AVD 이름을 정하고 Finish를 누릅니다.
5. 생성된 Wear OS 에뮬레이터를 실행한 뒤 Android Studio의 Run 대상에서 선택합니다.

## 앱 사용법

앱 화면에서 다음을 확인하거나 입력할 수 있습니다.

- Backend URL: 전송할 백엔드 base URL
- JWT token: `Authorization: Bearer <token>`으로 보낼 JWT
- Health Services에서 받은 데이터 미리보기
- HTTP status code와 response body

버튼:

- Start HS exercise: Health Services walking exercise 시작
- Stop HS exercise: exercise 종료
- Send to backend: 현재 화면의 데이터를 백엔드로 전송
- Start & send next: exercise를 시작하고 다음 Health Services update가 들어오면 바로 전송
- Auto-send updates: ON/OFF 버튼. ON이면 Health Services update를 받을 때마다 백엔드로 자동 전송

처음 시작할 때 `ACTIVITY_RECOGNITION`, `BODY_SENSORS` 또는 `READ_HEART_RATE` 권한 요청이 뜰 수 있습니다. 허용해야 Health Services 데이터를 받을 수 있습니다.

`measured_at`은 한국 시간대인 KST(`Asia/Seoul`, `+09:00`) 오프셋이 포함된 ISO 문자열로 전송됩니다.

## 에뮬레이터 synthetic data 테스트

앱을 실행한 뒤 `Start HS exercise`를 누릅니다. 그 다음 PC 터미널에서 아래 명령을 실행해 synthetic walking 데이터를 발생시킬 수 있습니다.

Wear OS 4 이상에서는 Health Services synthetic data가 Health Services lifecycle과 통합되어 있습니다. 필요하면 아래 walking broadcast를 함께 사용합니다.

```powershell
adb shell am broadcast -a "whs.synthetic.user.START_WALKING" com.google.android.wearable.healthservices
```

Wear OS 3 에뮬레이터는 synthetic provider를 먼저 켜야 할 수 있습니다.

```powershell
adb shell am broadcast -a "whs.USE_SYNTHETIC_PROVIDERS" com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.synthetic.user.START_WALKING" com.google.android.wearable.healthservices
```

커스텀 심박수로 테스트하려면:

```powershell
adb shell am broadcast -a "whs.synthetic.user.START_EXERCISE" --ei exercise_options_heart_rate 90 --ef exercise_options_average_speed 1.2 --ez exercise_options_use_location true com.google.android.wearable.healthservices
```

synthetic exercise를 멈추려면:

```powershell
adb shell am broadcast -a "whs.synthetic.user.STOP_EXERCISE" com.google.android.wearable.healthservices
```

Wear OS 3에서 실제 센서 provider로 되돌리려면:

```powershell
adb shell am broadcast -a "whs.USE_SENSOR_PROVIDERS" com.google.android.wearable.healthservices
```

## JWT 토큰 설정

토큰은 두 가지 방식으로 넣을 수 있습니다.

### 1. 앱 화면에서 직접 입력

에뮬레이터에서 앱을 실행한 뒤 `JWT token` 입력란에 토큰을 붙여넣으면 됩니다. 이 값은 화면에서만 사용됩니다.

### 2. local.properties로 BuildConfig 주입

`local.properties.example`을 참고해서 프로젝트 루트에 `local.properties`를 만듭니다.

```properties
HEALTH_API_BASE_URL=https://web-production-94f63.up.railway.app
HEALTH_API_TOKEN=your.jwt.token.here
```

Gradle sync 또는 rebuild 후 앱을 실행하면 `BuildConfig.HEALTH_API_TOKEN` 값이 화면 입력란의 기본값으로 들어갑니다.

`local.properties`는 `.gitignore`에 포함되어 있으므로 실제 토큰을 커밋하지 않아도 됩니다.

## 백엔드 주소 설정

### Railway 기본 주소

기본값은 아래 주소입니다.

```properties
HEALTH_API_BASE_URL=https://web-production-94f63.up.railway.app
```

앱 화면의 Backend URL 입력란에서 바로 바꿀 수도 있습니다.

### 로컬 백엔드 주소

Android/Wear OS 에뮬레이터에서 PC의 localhost로 접근할 때는 `localhost` 대신 `10.0.2.2`를 사용합니다.

예를 들어 PC에서 백엔드가 `http://localhost:8080`으로 실행 중이면 앱에는 아래처럼 넣습니다.

```properties
HEALTH_API_BASE_URL=http://10.0.2.2:8080
```

물리 워치나 같은 Wi-Fi의 실제 기기에서 테스트한다면 PC의 LAN IP를 사용합니다.

```properties
HEALTH_API_BASE_URL=http://192.168.0.10:8080
```

이 앱은 테스트 용도라 `AndroidManifest.xml`에 `usesCleartextTraffic="true"`를 켜두었습니다. 운영 앱에서는 HTTPS 사용을 권장합니다.
