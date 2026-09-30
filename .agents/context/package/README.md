# ota-client package (`package/`)

The OTA engine, packaged as an installable React Native library. `package/` is the
publishable npm package; the app at the repository root is a plain consumer of it.

## Layout

| Path | Contents |
| --- | --- |
| `package.json` | Library manifest. `main`/`types` point at `src/index.ts` (Metro compiles it). `bin.ota-client` → `cli/index.js`. |
| `src/` | TypeScript API: `OtaClient` facade, `useOTA`, context, components. No build step. |
| `android/` | The Kotlin library module (`com.android.library`, namespace `com.otaclient`). |
| `cli/` | Plain CommonJS CLI: `android`, `release`, `apk`, `doctor`. |
| `react-native.config.js` | Autolinking descriptor: Android linked, `ios: null`. |

## Kotlin packages

- `com.otaclient` — `OtaClientModule` (the `OtaClient` native module, legacy
  `BaseReactPackage` so it works on both architectures) and `OtaClientPackage`.
- `com.otaclient.ota` — `OtaBundleProvider` (bundle swap + crash rollback),
  `OtaConfig` (manifest meta-data + restart), `OtaHost` (host app metadata),
  optional `OtaSplashActivity` / `OtaConfigureActivity`.
- `com.otaclient.utils` — `OTAClient` (Ktor HTTP), `AppStorage` (SharedPreferences),
  `Http.kt` (streaming download, safe tar extraction, MD5).
- `com.otaclient.data` — `Manifest`, `AppInfo`, `CheckUpdate`, `DownloadBundle`,
  `BundleState`, `DeviceInfo`, `APIResponse`.

## Integration contract

1. `MainApplication.getJSBundleFile()` must return
   `OtaBundleProvider.getJSBundleFile(applicationContext)`. This is the only
   required host change; `ota-client doctor` verifies it.
2. `package.json` needs `runtimeVersion`, and it must equal the APK `versionName`
   in `android/app/build.gradle`. `ota-client release` fails otherwise.
3. Server coordinates come from `AndroidManifest.xml` meta-data
   (`ota_client_api_base_url` / `_api_version` / `_api_key`) and/or from
   `OtaClient.configure()`. Runtime values win.

## Crash rollback

`OtaBundleProvider` treats every activated bundle as provisional: the PID of the
process that started it is stored in `AppStorage.PENDING_PID` and only cleared by
`markLaunchSucceeded()`, which `OTAProvider` calls `confirmDelayMs` (default 2 s)
after mount. If the next process start sees a pending PID from a different
process, the previous bundle is restored (or the embedded one).

## Verified

- `npx tsc --noEmit -p package/tsconfig.json` — clean.
- `npx eslint package/src` — 0 errors.
- Gradle `:ota:assembleRelease` against React Native 0.87 / AGP 8.12 / Kotlin 2.1.20
  — succeeds with no warnings (AAR produced).
- `npx react-native config` picks the module up with the expected
  `packageImportPath` / `packageInstance`.
- `node package/cli/index.js release` produces a valid `release/*.tar.gz` with the
  bundle and `manifest.json` at the archive root; `doctor` runs.
