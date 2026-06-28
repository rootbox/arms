# Development Plan

## Guiding Approach

각 마일스톤은 실행 가능하거나 검증 가능한 결과물을 생성하는 수직적 슬라이스 방식으로 개발을 진행합니다. 클라이언트는 Mock 데이터를 사용하여 개발을 시작하고, 핵심 기능이 안정화된 후 실제 라디오/스트리밍 데이터 연동을 진행합니다.

## Proposed Milestones

### Milestone 0: Repository Foundation (완료)

목표: 저장소가 이해하기 쉽고 구현 준비가 완료되도록 합니다.

*   회복된 프로젝트 컨텍스트를 문서화하여 추적합니다.
*   제품 개요, 개발 계획, 테스트 계획, 핸드오프 프로토콜을 정의합니다.
*   초기 소스 레이아웃 및 툴체인을 결정합니다.
*   주요 기술 결정 사항에 대해 ADR(Architecture Decision Records)을 추가합니다.

### Milestone 1: Android App Skeleton & Core Modules

목표: 빌드 가능한 Android 프로젝트와 핵심 모듈 구조를 생성합니다.

*   Kotlin Android 앱을 API 34+ 대상으로 스캐폴딩합니다.
*   Gradle 구성, Lint, 테스트 작업 및 기본 CI 준비 명령을 추가합니다.
*   패키지/모듈 구조를 설정합니다 (`app`, `core:radio`, `core:streaming`, `core:media`, `core:data`, `testapp:cli`).
*   디버그 및 설정을 위한 최소한의 전화 UI를 추가합니다.

종료 조건:

*   `./gradlew test` 및 `./gradlew assembleDebug`가 로컬에서 실행됩니다. (현재 환경 제약으로 실제 실행은 어려움)
*   에뮬레이터/기기에서 디버그 앱이 실행됩니다.

### Milestone 2: Radio & Streaming Integration

목표: 대한민국 주요 라디오 및 음악 스트리밍 방송 수신 기능을 구현합니다.

*   `core:radio` 모듈에 라디오 방송 수신 로직을 구현합니다.
*   `core:streaming` 모듈에 음악 스트리밍 방송 수신 로직을 구현합니다.
*   라디오 및 스트리밍 방송 목록을 가져오는 API/데이터 소스를 정의합니다.

종료 조건:

*   앱이 주요 라디오 및 스트리밍 방송 목록을 표시합니다.
*   로컬 테스트 환경에서 방송 수신 기능이 검증됩니다.

### Milestone 3: Media Playback Core & Android Auto Integration

목표: 타임라인 기반 오디오 재생과 Android Auto 연동을 구현합니다.

*   `core:media` 모듈에 Media3/ExoPlayer 재생 서비스를 추가합니다.
*   로컬 타임라인 모델 (블록, 트랜스크립트, 구문 메타데이터, 일시 중지 창)을 정의합니다.
*   mock 오디오 시퀀스를 안정적인 진행 상황 추적과 함께 재생합니다.
*   `app` 모듈에 MediaSession 및 브라우저/카탈로그 구조를 추가합니다.
*   Android Auto Desktop Head Unit으로 검증합니다.
*   지원되는 미디어 버튼을 재생 및 학습 작업에 매핑합니다.
*   모든 UI를 Android Auto 미디어 앱 제한 내에서 유지합니다.

종료 조건:

*   로컬 샘플 타임라인이 MVP 테스트에 충분할 정도로 끊김 없이 재생됩니다.
*   Android Auto에서 mock 세션을 찾아 선택하고 재생할 수 있습니다.
*   하드웨어/미디어 제어가 예상된 작업을 트리거합니다.

### Milestone 4: Local Data Storage & Favorites

목표: 세션을 영구적으로 저장하고 즐겨찾기 기능을 구현합니다.

*   `core:data` 모듈에 사용자를 위한 Room 엔티티, 세션, 오디오 블록, 구문 및 학습 이벤트를 추가합니다.
*   즐겨찾기 방송을 저장하고 관리하는 로직을 구현합니다.
*   즐겨찾기 목록을 UI에 표시하고 빠르게 접근할 수 있도록 합니다.

종료 조건:

*   즐겨찾기 방송이 앱을 다시 시작해도 유지됩니다.
*   즐겨찾기 목록을 통해 방송을 선택하고 재생할 수 있습니다.

### Milestone 5: Current Broadcast Details

목표: 현재 청취 중인 방송의 상세 정보를 표시합니다.

*   현재 재생 중인 라디오/스트리밍 방송의 편성 이름, 음악 제목, 방송국 데이터 등 상세 정보를 가져와 표시하는 기능을 구현합니다.
*   필요한 경우, 외부 API 연동을 통해 실시간 방송 정보를 업데이트합니다.

종료 조건:

*   현재 청취 중인 방송의 상세 정보가 UI에 정확하게 표시됩니다.

### Milestone 6: Refinement & Testing

목표: 앱의 안정성과 사용자 경험을 개선하고, 종합적인 테스트를 수행합니다.

*   실제 기기 및 Android Auto Desktop Head Unit에서 종합적인 회귀 테스트를 수행합니다.
*   성능 최적화 및 버그 수정을 진행합니다.
*   베타 배포를 위한 체크리스트를 준비합니다.

종료 조건:

*   주요 기능이 실제 환경에서 안정적으로 작동합니다.
*   알려진 제한 사항이 베타 전에 문서화됩니다.
