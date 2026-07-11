# Simple Radio

A media app that streams Korean live radio and a 24/7 K-POP channel to your phone and your car (Android Auto / Android Automotive OS), with real program schedules, real-time song metadata, and album art on the Now Playing screen.

Made by [1319.space](https://1319.space). Internally the codebase is still named ARMS (Automobile Radio & Music Streaming) — you'll see that name in package/class names and the repo history.

[한국어 README](README.ko.md)

## Features

- **Live radio**: KBS Cool FM (89.1 MHz) and SBS Power FM (107.7 MHz), pulled from each broadcaster's own public streaming endpoints.
- **24/7 K-POP channel**: continuous K-POP streaming via [LISTEN.moe](https://listen.moe/), with real-time song title/artist and album art.
- **Real program info, not placeholders**: the Now Playing screen shows the actual on-air program name and profile image (KBS/SBS) or the actual currently-playing song and cover art (K-POP), refreshed automatically every 30 seconds so it stays correct as programs/songs change — it doesn't just show whatever was on air when you hit play.
- **Favorites**: mark stations as favorites for quick access from a dedicated tab.
- **Instant play & auto-resume**: tapping a station starts playback immediately; the app also remembers the last station you played and resumes it automatically — including when Android Auto reconnects after the car is turned off and back on, no need to tap play again.
- **Previous/Next buttons cycle channels**: since live radio has no "next track," the Now Playing screen's skip buttons instead switch to the previous/next station in your channel list.
- **Per-channel loudness correction**: if one station's stream is noticeably quieter than the others at the source, its output is boosted (not the others attenuated) so volume feels consistent across channels.
- **Spotify-inspired phone UI**: a Compose-based Now Playing screen with ambient blurred artwork backgrounds.
- **Android Auto / Android Automotive OS support**: implemented as a Media3 `MediaLibraryService`, so it shows up as a media source in the car, with the same real artwork and metadata as the phone app.

## How it works

This app doesn't use any official, documented public API for KBS or SBS — there isn't one. The stream URLs and program-schedule endpoints were found by inspecting each broadcaster's own website network traffic, so they:

- Require re-fetching a fresh signed URL before every playback attempt (KBS URLs expire in ~48h, SBS in ~12h — cached URLs will eventually 403).
- **Can change or break at any time** without notice, since they're not a stable public contract. If a station stops working, check whether the broadcaster's own web player still works and inspect its network requests to find the new endpoint.

K-POP metadata comes from LISTEN.moe's public SSE metadata stream and GraphQL API (`https://listen.moe/graphql`), which are documented and intended for third-party use.

## Requirements

- Android Studio (Ladybug or newer) or a command-line Gradle/JDK setup
- JDK 17
- Android SDK with `compileSdk 35` / `minSdk 26` platform installed
- A physical Android phone for real Android Auto testing — **Android Auto (phone projection) refuses to run on emulators** (Google marks it incompatible). For UI-only testing without a phone, use an Android Automotive OS (AAOS) emulator profile instead (see below).

## Getting started

```bash
git clone https://github.com/rootbox/arms.git
cd arms
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or just open the project root in Android Studio and run the `app` configuration on a connected device.

There's also a plain-JVM CLI smoke test for the network layer (`testapp:cli`) that lets you sanity-check the KBS/SBS/K-POP fetch logic without building the full Android app:

```bash
./gradlew :testapp:cli:run
```

## Running it in Android Auto

Since this app isn't distributed through Google Play, Android Auto hides it from the media source list by default — this is intentional behavior on Google's part, not a bug in this app. To allow it:

1. Open the **Android Auto** app on your phone (car connection not required for this step).
2. Open the hamburger menu (☰) → **Settings**.
3. Scroll to the bottom and tap the **version number** about 10 times, until you see a "developer settings enabled" toast.
4. Open the new **Developer settings** entry.
5. Enable **Unknown sources**.
6. Connect to your car (USB or wireless Android Auto) — Simple Radio should now appear as a media source.

If a channel plays but you hear nothing on first connect, request audio focus by playing anything from another media app once — this is a known Android Auto/Bluetooth quirk where the audio route to the car doesn't open until some app requests focus. (This app already requests audio focus itself, but on some head units the very first connection of a session can still need this nudge.)

### Previewing the car UI without a real Android Auto connection

Android Auto phone projection can't run on an emulator at all, but you can preview this app's car media UI on an **Android Automotive OS (AAOS)** emulator instead, since it uses the same Media3 `MediaLibraryService` contract:

1. In Android Studio's Device Manager, create a virtual device using an **Automotive** system image (e.g. `Automotive with Google APIs`).
2. Install the debug APK on it like any other emulator/device.
3. The app will appear in the car media launcher (`android.car.intent.action.MEDIA_TEMPLATE` / `androidx.car.app.launchable` are already declared in the manifest).

## Updating without a cable

The app checks GitHub Releases on launch and shows a banner with a one-tap download when a newer version is available — no adb/cable needed after the first install. Android still requires you to confirm the system install dialog each time (this can't be automated for a non-Play-Store app), but there's no more manual APK copying.

To publish a new version as a maintainer:

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`.
2. Commit, then tag and push: `git tag v<versionCode> && git push origin v<versionCode>` (e.g. `v3`).
3. The `.github/workflows/release.yml` workflow builds the APK and publishes it as a GitHub Release automatically — the app's update checker relies on the `v<versionCode>` tag format and finds the `.apk` asset on that release.

This only works while the repo is public, since the update checker calls the GitHub API without any embedded credentials (by design — a token baked into the APK would be extractable).

## Project structure

```
app/                    Phone UI (Jetpack Compose) + ARMSMediaLibraryService (car/Android Auto)
core/model/             Shared data models (Station, etc.)
core/network/           KBS/SBS/K-POP stream & metadata fetching (RadioApi)
core/data/              StationRepository, Room database, last-played-station persistence
core/media/             Shared ExoPlayer wrapper used by the phone UI
core/radio/, core/streaming/   Reserved modules (currently unused placeholders)
testapp/cli/            Plain-JVM CLI for exercising core:network without the Android app
```

## Contributing

Issues and pull requests are welcome. If you're adding a new station, please make sure its stream/metadata source is a real, currently-working endpoint (either official or one you've verified yourself) — no placeholder or fabricated URLs.

## Disclaimer

This is an unofficial, community project and is not affiliated with or endorsed by KBS, SBS, or LISTEN.moe. Station names, logos, and program data belong to their respective owners and are used here only to display what's actually on air/playing.

## License

[MIT](LICENSE)
