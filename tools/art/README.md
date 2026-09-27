# 채널 아트 생성기

`ChannelArt.java`는 라디오 채널 5개의 커버 아트(600×600 PNG)를 Java2D로만 그린다. 외부 의존성 없음.

| 파일 | 채널 |
|---|---|
| `art_ch_kbs.png` | 1 KBS Cool FM |
| `art_ch_sbs.png` | 2 SBS 파워FM |
| `art_ch_newhit.png` | 3 KPOP NEW HIT |
| `art_ch_ballad.png` | 4 KPOP BALLAD |
| `art_ch_8090.png` | 5 KPOP 8090 HIT |

같은 이름의 PNG가 두 곳에 들어간다(내용 동일):

- `core/data/src/main/res/drawable/` — 안드로이드(`StationRepository.defaultArtworkUri`)
- `desktop/src/main/resources/` — 데스크톱(`AppState.defaultArt`, `res:/` URL)

## 실행

```sh
cd tools/art
javac -d build ChannelArt.java
java -cp build ChannelArt \
  ../../core/data/src/main/res/drawable \
  ../../desktop/src/main/resources \
  -sheet build/contact_sheet.png
```

- 출력 디렉터리는 여러 개를 줄 수 있다(각각에 5장 저장).
- `-sheet <png>`: 다섯 장을 한 줄로 늘어놓은 검수용 컨택트 시트.
- `-font <파일.ttf>`: 워드마크 글꼴 지정(예: Roboto-Bold.ttf). 지정하지 않으면 시스템에 Roboto가 있을 때 Roboto,
  없으면 Helvetica Neue → Helvetica → Arial → 기본 산세리프 순으로 고른다. macOS 기본 환경에서는 Helvetica Neue Bold.
- `build/`는 산출물이므로 커밋하지 않는다.

## 디자인 규칙

앱 팔레트(`app/src/main/java/com/arms/androidauto/ui/theme/Color.kt`)를 그대로 쓴다.

- 타일 `#212121` 위에 `#282828 → #0F0F0F` 대각 그라데이션, 우상단 옅은 하이라이트. 발라드만 대비를 낮춘 부드러운 그라데이션.
- 모서리 반지름 72px(12%). 모서리 바깥은 앱 배경 `#0F0F0F`로 불투명하게 채운다(알파 없음 — 서비스가 JPEG로 축소해 차량/블루투스에 보내므로).
- 그래픽은 흰색, 강조색 `#FF0033`은 타일당 한 번(점·번개·스파크·하트·테이프), 캡션은 `#AAAAAA`.
- 그리드: 모티프 상단 우측, 워드마크 좌하단(기준선 y=466, 최대 128px, 폭 초과 시 축소), 캡션 그 아래(y=524).
- 방송사 로고/상표는 그리지 않는다. 타이포 + 단순 도형만.
