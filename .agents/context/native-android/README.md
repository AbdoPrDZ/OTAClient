# Native Android OTA engine

## Overview

A Kotlin native layer that performs OTA updates of the JS bundle without rebuilding the APK. On app launch it talks to an update server, downloads new bundles, and swaps the bundle React Native loads. All code lives under `android/app/src/main/java/com/otaclient/`.

Dependencies (`android/app/build.gradle`): Ktor client (CIO engine, content negotiation, Gson serializer) for HTTP, `gson` for JSON, `commons-compress` for tar.gz extraction, AndroidX appcompat/activity/constraintlayout/material.

## Startup flow

1. `MainApplication.onCreate()` → `AppStorage.init(this)` loads SharedPreferences, then `loadReactNative(this)` initializes the React host.
2. `SplashActivity` is the launcher (`AndroidManifest.xml`, `noHistory="true"`). On create:
   - Loads the base manifest from bundled assets (`AppStorage.getBaseManifest`).
   - Runs `OTAClient.instance.healthCheck()` on `Dispatchers.IO`.
   - Healthy → `checkUpdate()` then navigate to `MainActivity`.
   - Unhealthy → navigate to `ConfigureActivity` (server/settings setup screen).
3. `MainActivity` (ReactActivity) renders the React component; the JS bundle comes from `MainApplication.getJSBundleFile()`.

## Activities

### SplashActivity

Update orchestration:

- `checkUpdate()` — calls `OTAClient.checkUpdate()`.
  - If a **bundle update** exists → shows a confirmation dialog (`confirmUpdate()`), and on accept: shows the download progress UI, `downloadBundle(this, onProgress = ...)`, stores `RUNNING_BUNDLE_DIR` and `MANIFEST` via `AppStorage`, then `restartApp()` (relaunches the launch intent with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`, then `exitProcess(0)`).
  - If an **app/version update** exists (`haveVersionUpdate`) → shows a confirmation dialog, and on accept: calls `downloadApp(this)` which returns a download URL string, then launches it via `ACTION_VIEW` with `Uri.parse(url)` (the browser/package installer handles the download+install). The Android 8+ unknown-apps permission request and local APK download/FileProvider flow are **commented out**.
  - Declining either dialog navigates to `MainActivity`.
- `confirmUpdate(title, message): Boolean` — suspend helper that shows an `AlertDialog` (Install / Not now, non-cancellable) and suspends until the user answers.
- Download progress UI: a circular `loadingBar` spinner is always visible under the title; during bundle download the horizontal `progressBar` + `progressText` (both `gone` by default) are shown. When `Content-Length` is known the bar is determinate (`max=total`, updated to `downloaded`) and the text shows `"Downloading new update... N%"`; without the header the bar stays indeterminate and only the message shows.

### ConfigureActivity

Placeholder screen shown when the server is unreachable. Intended for entering server config (`API_BASE_URL`, `API_VERSION`, `API_KEY`). **Not yet implemented** — no input fields, no handlers.

### MainActivity

Standard `ReactActivity`; `getMainComponentName()` returns `"OTAClient"`; New Architecture enabled via `DefaultReactActivityDelegate` with `fabricEnabled`.

### MainApplication

`ReactApplication`. `AppStorage.init(this)` runs in `onCreate`. The custom `DefaultReactNativeHost` (`rnHost`) is passed to `getDefaultReactHost`:

- `getJSMainModuleName()` returns `"index"`.
- `getJSBundleFile()` — returns the downloaded bundle path only if it exists **and** `BuildConfig.VERSION_NAME == AppStorage.MANIFEST.runtimeVersion` (runtime compat check); otherwise `null` (falls back to embedded `assets/index.android.bundle`).

## OTAClient (com.otaclient.utils)

Singleton (`companion object.instance`, `@Volatile` + `synchronized`). Holds a shared Ktor `HttpClient(CIO)` with Gson `ContentNegotiation` and `HttpTimeout` (request/socket 5 min, connect 30 s).

### API contract

- Base URL: `{AppStorage.API_BASE_URL}` (default `http://172.16.40.112`), API root: `{API_BASE_URL}/ota-client/{API_VERSION}`.
- Endpoints:
  - `GET /health` — health check (headers: `Accept: application/json`, `API-KEY`, `X-Device-Info`).
  - `POST /app/info` — update check (form params: `package`, `version`, `bundle`). Headers: `Accept`, `API-KEY`, `X-Device-Info`.
  - `GET /app/update/bundle/{id}` — downloads the `release/*.tar.gz` bundle artifact. Headers: `API-KEY`, `X-Device-Info`, `Authorization: Bearer {session}`.
  - `GET /app/update/version/{id}?did={androidId}&token={session}` — returns the APK download URL for an app/version update (auth via query params `did` + `token`).
- `X-Device-Info` format: `did={androidId};mf={manufacturer};br={brand};mdl={model};av={androidVersion};sdv={sdkVersion}`.
- The bundle download endpoint uses `check.appInfo.session` as a bearer token.
- For progress UI, the server should send a `Content-Length` header on the bundle download (e.g. Laravel: `response()->file($path, ['Content-Length' => Storage::disk('public')->size($file->path)])`); when absent the client shows an indeterminate bar.
- Responses use a common envelope: `APIResponse<T>` `{ success, message, data }`.

### Methods

- `healthCheck(): Boolean` — `GET /health`, true on 2xx.
- `appInfo(): AppInfo?` — `POST .../app/info` (form), parses `data` into `AppInfo`.
- `checkUpdate(): CheckUpdate` — queries `appInfo()`; returns `{ haveUpdate, haveVersionUpdate, haveBundleUpdate, appInfo }` based on whether `availableUpdates.version` / `availableUpdates.bundle` are non-null.
- `downloadBundle(context, check, onProgress): DownloadBundle` —
  1. Creates `filesDir/new-update-{timestamp}/`, streams the tar.gz download to `archive.tar.gz` via `downloadFile()` (passing `onProgress` through).
  2. Extracts with `extractTarGz()` into `extracted/`.
  3. Requires `manifest.json`, then the bundle file named in it.
  4. Copies both into the final dir `filesDir/{manifest.runtimeVersion}-{manifest.version}/`.
  5. Returns `DownloadBundle(downloadDir, manifest, bundleFile, manifestFile)`. On any failure deletes the temp dir and throws.
- `downloadApp(context, check): String` — **non-suspend**; returns the APK download URL `$API_URL/app/update/version/{id}?did={androidId}&token={session}` (no local download; the caller opens it via `ACTION_VIEW`).

### Helpers

- `HttpClient.downloadFile(url, file, block, onProgress)` — streaming download using `bodyAsChannel()`. Reads `Content-Length` for the total; loops on a `ByteBuffer` (`clear()` before each `readAvailable`, exit on `-1` EOF) writing to the file and invoking `onProgress(downloaded, total)` (total is `null` when the header is missing).
- `extractTarGz(archiveFile, outputDir)` — `TarArchiveInputStream(GzipCompressorInputStream(...))`; handles directories and files (creates parents).

## AppStorage (com.otaclient.utils)

Object wrapping SharedPreferences (`"OTACenter"`, `commit = true` writes).

- `API_BASE_URL` — default `http://172.16.40.112`
- `API_VERSION` — default `v1`
- `API_KEY` — default `v1.xxxxxxxxxxxxxx`
- `RUNNING_BUNDLE_DIR` — `File?`; path of the currently running bundle dir (validated to exist & be a dir).
- `MANIFEST` — current `Manifest`, persisted as JSON; getter falls back to `BASE_MANIFEST` (from bundled `assets/manifest.json`, loaded lazily by `getBaseManifest(context)`).

## Data models (com.otaclient.data)

- `Manifest` — `version`, `runtimeVersion`, `bundle`, `createdAt` (note: `checksum`/`size` are written by the build scripts but not deserialized here).
- `UpdateInfo` — `id`, `name`.
- `AppInfoAvailableUpdates` — `version: UpdateInfo?`, `bundle: UpdateInfo?`.
- `AppInfo` — `appId`, `versionId`, `bundleId`, `availableUpdates`, `session`.
- `APIResponse<T>` — `success`, `message`, `data`.
- `CheckUpdate` — `haveUpdate`, `haveVersionUpdate`, `haveBundleUpdate`, `appInfo`.
- `DownloadBundle` — `downloadDir`, `manifest`, `bundleFile`, `manifestFile`.
- `DownloadApp` — `appFile`, `appFileUri` (FileProvider URI). **Defined but unused** — the current `downloadApp()` returns a URL string instead.
- `DeviceInfo` — `androidId`, `manufacturer`, `brand`, `model`, `androidVersion`, `sdkVersion`, `supportedAbis`.

## Android manifest & res

- `AndroidManifest.xml` — `INTERNET` + `REQUEST_INSTALL_PACKAGES` permissions; `usesCleartextTraffic` from gradle property (debug allows HTTP to the dev server); Splash is the launcher, Configure is private, Main is `singleTask`; a `FileProvider` (`androidx.core.content.FileProvider`, authority `${applicationId}.fileprovider`, grantUriPermissions) is registered (currently unused — app install goes through a URL).
- `layout/activity_splash.xml` — "OTAClient" title; a circular indeterminate `loadingBar` (always visible); a horizontal determinate `progressBar` + `progressText` (both `gone` by default, shown during bundle download).
- `layout/activity_configure.xml` — placeholder "Configure" title.
- `res/raw/keep.xml` — keeps assets.
- `res/xml/file_paths.xml` — FileProvider paths (`<files-path name="files" path="."/>`).
- `assets/` — bundled `index.android.bundle` + `manifest.json` (base bundle, produced by `npm run build:android`).

## Known issues / TODOs

- `ConfigureActivity` is a non-functional placeholder.
- `downloadApp()` returns a URL instead of downloading locally — relies on an external browser/downloader to fetch and install the APK; the FileProvider-based local install is commented out.
- `checksum`/`size` in the manifest are not verified client-side after download.