# Simple Radio

한국 실시간 라디오와 24/7 K-POP 채널을 폰과 차량(Android Auto / Android Automotive OS)에서 들을 수 있는 미디어 앱입니다. Now Playing 화면에는 실제 편성 정보, 실시간 곡 정보, 앨범 아트가 그대로 표시됩니다.

[1319.space](https://1319.space)에서 만들었습니다. 코드베이스 내부적으로는 여전히 ARMS(Automobile Radio & Music Streaming)라는 이름을 쓰고 있어서, 패키지/클래스 이름과 커밋 히스토리에서 이 이름을 보실 수 있습니다.

[English README](README.md)

## 주요 기능

- **실시간 라디오**: KBS Cool FM (89.1 MHz), SBS 파워FM (107.7 MHz) — 각 방송사 자체 스트리밍 엔드포인트에서 직접 수신.
- **24/7 K-POP 채널**: [LISTEN.moe](https://listen.moe/) 기반 논스톱 K-POP 스트리밍, 실시간 곡 제목/아티스트/앨범 아트 표시.
- **가짜 정보 없는 실제 편성 정보**: Now Playing 화면에는 실제 방송 중인 프로그램명과 프로필 이미지(KBS/SBS), 또는 실제로 재생 중인 곡과 커버 아트(K-POP)가 표시됩니다. 30초마다 자동으로 다시 확인해서, 재생 시작 시점의 정보가 아니라 방송/곡이 바뀌어도 항상 최신 정보를 유지합니다.
- **즐겨찾기**: 자주 듣는 채널을 즐겨찾기 탭에 등록해 빠르게 접근.
- **즉시 재생 & 마지막 채널 자동 재개**: 채널을 탭하면 바로 재생되고, 마지막으로 들었던 채널을 기억했다가 앱을 다시 켜거나 심지어 **차량 시동을 껐다 켜서 Android Auto가 재연결될 때도** 자동으로 이어서 재생합니다 — 다시 눌러줄 필요 없음.
- **이전/다음 버튼으로 채널 전환**: 실시간 라디오에는 "다음 트랙" 개념이 없기 때문에, Now Playing 화면의 이전/다음 버튼은 채널 목록상 이전/다음 방송국으로 전환하는 용도로 동작합니다.
- **채널별 음량 보정**: 원본 스트림 자체의 라우드니스가 낮아 상대적으로 조용한 채널이 있으면, 다른 채널을 줄이는 대신 해당 채널만 게인을 올려 전체적인 체감 볼륨을 맞춥니다.
- **Spotify 감성의 폰 UI**: Jetpack Compose 기반 Now Playing 화면, 앨범 아트에서 추출한 블러 배경.
- **Android Auto / Android Automotive OS 지원**: Media3 `MediaLibraryService`로 구현되어 있어 차량에서 미디어 소스로 인식되며, 폰 앱과 동일한 실제 아트워크/메타데이터를 그대로 보여줍니다.

## 동작 원리

이 앱은 KBS/SBS의 공식적으로 문서화된 공개 API를 사용하지 않습니다 — 애초에 그런 게 존재하지 않기 때문입니다. 스트림 URL과 편성표 엔드포인트는 각 방송사 자체 웹사이트의 네트워크 트래픽을 분석해서 찾아낸 것이라:

- 재생 직전에 매번 새로 서명된 URL을 받아와야 합니다 (KBS는 약 48시간, SBS는 약 12시간 후 만료되며, 캐시된 URL은 결국 403 오류가 납니다).
- **공식적으로 안정성이 보장된 게 아니므로 언제든 예고 없이 바뀌거나 끊길 수 있습니다.** 특정 방송국이 안 나온다면, 해당 방송사의 자체 웹 플레이어가 여전히 정상 동작하는지 확인하고 그 네트워크 요청을 다시 분석해서 새 엔드포인트를 찾아야 합니다.

K-POP 메타데이터는 LISTEN.moe의 공개 SSE 메타데이터 스트림과 GraphQL API(`https://listen.moe/graphql`)를 사용하며, 이는 서드파티 사용을 위해 공식적으로 문서화되어 제공되는 것입니다.

## 요구 사항

- Android Studio (Ladybug 이상) 또는 커맨드라인 Gradle/JDK 환경
- JDK 17
- `compileSdk 35` / `minSdk 26` 플랫폼이 설치된 Android SDK
- 실제 Android Auto 테스트를 위한 실물 Android 폰 — **Android Auto(폰 프로젝션)는 에뮬레이터에서 아예 실행되지 않습니다** (구글이 호환되지 않는 기기로 표시). 폰 없이 UI만 미리 보고 싶다면 아래의 Android Automotive OS(AAOS) 에뮬레이터를 사용하세요.

## 시작하기

```bash
git clone https://github.com/rootbox/arms.git
cd arms
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

또는 프로젝트 루트를 Android Studio로 열고, 연결된 기기에서 `app` 실행 구성을 그대로 실행하면 됩니다.

전체 Android 앱을 빌드하지 않고 KBS/SBS/K-POP 조회 로직만 빠르게 확인하고 싶다면, 순수 JVM 기반 CLI 스모크 테스트(`testapp:cli`)도 있습니다:

```bash
./gradlew :testapp:cli:run
```

## Android Auto에서 실행하기

이 앱은 Google Play를 통해 배포되지 않았기 때문에, Android Auto는 기본적으로 미디어 소스 목록에서 이 앱을 숨깁니다 — 이건 이 앱의 버그가 아니라 구글 쪽의 의도된 동작입니다. 허용하려면:

1. 폰에서 **Android Auto** 앱 실행 (이 단계는 차량 연결 없이도 가능).
2. 좌측 상단 ☰ 메뉴 → **설정**.
3. 맨 아래로 스크롤해서 **버전 정보**를 10번 정도 연속 탭 → "개발자 설정이 활성화되었습니다" 토스트 확인.
4. 새로 생긴 **개발자 설정** 진입.
5. **"알 수 없는 소스" (Unknown sources)** 켜기.
6. 차량에 연결(USB 또는 무선 Android Auto) — 이제 Simple Radio가 미디어 소스로 나타납니다.

채널은 재생되는데 처음 연결 시 소리가 안 들린다면, 다른 미디어 앱에서 한 번 재생을 시도해서 오디오 포커스를 요청해보세요 — 차량으로의 오디오 경로가 어떤 앱이든 포커스를 요청하기 전까지는 열리지 않는, 알려진 Android Auto/블루투스 특성입니다 (이 앱 자체는 이미 오디오 포커스를 요청하도록 구현돼 있지만, 일부 헤드유닛에서는 세션의 첫 연결 시 이 과정이 한 번 더 필요할 수 있습니다).

### 실제 Android Auto 연결 없이 차량 UI 미리보기

Android Auto 폰 프로젝션 자체는 에뮬레이터에서 절대 실행되지 않지만, 이 앱의 차량 미디어 UI는 **Android Automotive OS(AAOS)** 에뮬레이터로 대신 미리 볼 수 있습니다 — 동일한 Media3 `MediaLibraryService` 규격을 사용하기 때문입니다:

1. Android Studio의 Device Manager에서 **Automotive** 시스템 이미지(예: `Automotive with Google APIs`)로 가상 기기 생성.
2. 다른 에뮬레이터/기기와 동일하게 디버그 APK 설치.
3. 매니페스트에 이미 선언된 `android.car.intent.action.MEDIA_TEMPLATE` / `androidx.car.app.launchable` 덕분에 차량 미디어 런처에 앱이 나타납니다.

## 프로젝트 구조

```
app/                    폰 UI (Jetpack Compose) + ARMSMediaLibraryService (차량/Android Auto)
core/model/             공용 데이터 모델 (Station 등)
core/network/           KBS/SBS/K-POP 스트림 및 메타데이터 조회 (RadioApi)
core/data/              StationRepository, Room 데이터베이스, 마지막 재생 채널 저장
core/media/             폰 UI에서 사용하는 공용 ExoPlayer 래퍼
core/radio/, core/streaming/   예약된 모듈 (현재는 미사용 placeholder)
testapp/cli/            Android 앱 없이 core:network 로직만 테스트하는 순수 JVM CLI
```

## 기여하기

이슈와 PR을 환영합니다. 새 방송국을 추가할 경우, 스트림/메타데이터 소스가 실제로 현재 동작하는 엔드포인트인지(공식이든 직접 검증한 것이든) 반드시 확인해주세요 — 가짜로 지어낸 URL은 받지 않습니다.

## 안내

이 프로젝트는 비공식 커뮤니티 프로젝트이며, KBS·SBS·LISTEN.moe와는 아무런 관련이 없고 이들의 승인을 받지 않았습니다. 방송국 이름, 로고, 편성 데이터의 권리는 각 소유자에게 있으며, 여기서는 실제로 방송/재생 중인 내용을 보여주기 위한 목적으로만 사용됩니다.

## 라이선스

[MIT](LICENSE)
