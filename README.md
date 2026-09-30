# ota-client

Over-the-air JavaScript bundle updates for React Native on **Android**.

A device ships one JS bundle inside the APK. `ota-client` lets that bundle be
replaced at runtime: the app asks your server whether a newer bundle exists,
downloads it, verifies it, swaps it in and restarts into it — no store release,
no new APK. A bundle that fails to boot reverts itself, so a bad update cannot
brick an installed app.

This repository holds two things:

| Path | What it is |
| --- | --- |
| `package/` | The `ota-client` library — the publishable npm package. Native Kotlin engine, typed JS API, React components and the `ota-client` CLI. |
| *(repository root)* | A host app used to develop and exercise the library against a real device. |

The library's own documentation, including the full API and CLI reference, lives
in [`package/README.md`](package/README.md). This README covers the repository,
the development loop and the update server contract.

## Requirements

| | |
| --- | --- |
| React Native | 0.71+ (developed against 0.85, New Architecture / Fabric enabled) |
| Android | minSdk 24, compileSdk 36 |
| Kotlin | 2.1.20 (inherited from the host project) |
| Node | 18+ for the CLI, 22.11+ for the app |

The engine is an Android-only Kotlin module. On iOS every API call rejects with
`OtaUnsupportedPlatformError` and the components render the app untouched.

## Getting started

```sh
npm install
npm start            # Metro
npm run android      # build and run on a device or emulator
```

`App.tsx` is a placeholder that prints the app and bundle versions — it exists
so a downloaded bundle is visibly different from the embedded one.

## The update loop

An OTA update is only allowed to change JavaScript. When a release also changes
native code the app must be shipped as a new APK instead, which is what
`runtimeVersion` is for.

| Field | Where | Meaning |
| --- | --- | --- |
| `version` | `package.json` | The bundle version. Bump it for every OTA release. |
| `runtimeVersion` | `package.json` | The native runtime key. Must equal the APK `versionName` in `android/app/build.gradle`. |

A bundle can only call native APIs that already exist in the APK, so the engine
refuses any bundle whose `runtimeVersion` differs from the running one.

### 1. Publish a bundle

```sh
npm version patch                # bumps `version` only
npm run build:release            # release/{name}-{runtimeVersion}-{version}.tar.gz
```

The archive contains exactly two files at its root — the Hermes bundle and
`manifest.json` (`version`, `runtimeVersion`, `bundle`, `checksum`, `size`,
`createdAt`). Upload it to your server and publish it as the current bundle.

`npm run build:release` here is a bare build script: it does not check
`runtimeVersion` against the APK, so a mismatched archive is produced silently
and then rejected on every device. The `ota-client release` CLI does validate,
and fails unless you pass `--force`.

### 2. Ship a new APK

```sh
npm version minor                 # bumps `version`
# then edit runtimeVersion in package.json to the new versionName
npm run build:apk                 # rebuilds the embedded bundle, then assembles
```

`npm version` only touches `version`, so `runtimeVersion` is a manual edit — and
it has to match the new `versionName` in `android/app/build.gradle`.

`build:apk` first runs `build:android`, which writes a fresh bundle plus its
assets into `android/app/src/main/{assets,res}`. Those files are what the APK
ships with as the fallback bundle, and they are generated — never hand-edit
them.

> Release builds are signed with the **debug keystore** (see
> `android/app/build.gradle`). Generate a real keystore before distributing.

### 3. Use the CLI in your own app

Inside a host project the same work is available as `ota-client <command>`:

```sh
npx ota-client android    # bundle the JS into android/app/src/main/{assets,res}
npx ota-client release    # build the OTA archive into release/
npx ota-client apk        # assemble the release APK
npx ota-client doctor     # verify the host is wired up correctly
```

`doctor` is the one to run first — it checks the `runtimeVersion` /
`versionName` match, the `getJSBundleFile()` override and the manifest.

## Using the library in your own app

```sh
npm install ota-client
npx react-native config | grep ota-client     # confirm autolinking picked it up
```

There is exactly one required host change. React Native asks the host which JS
file to load; the engine answers that question:

```kotlin
// android/app/src/main/java/com/myapp/MainApplication.kt
import com.otaclient.ota.OtaBundleProvider

override fun getJSBundleFile(): String? = OtaBundleProvider.getJSBundleFile(applicationContext)
```

Returning `null` — the default when no OTA bundle is active, in debug builds, or
after a rollback — hands control back to React Native, which serves the bundle
inside the APK or connects to Metro.

Then wrap the app root and you are done:

```tsx
import { OTAProvider } from 'ota-client';

export default function App() {
  return (
    <OTAProvider config={{ apiBaseUrl: 'https://updates.example.com', apiKey: API_KEY }}>
      <RootNavigator />
    </OTAProvider>
  );
}
```

Server coordinates come from `AndroidManifest.xml` meta-data so they can be
committed as safe defaults, and from `OtaClient.configure()` for per-build or
per-user overrides. Runtime values win.

See [`package/README.md`](package/README.md) for the full API, the component
list, every meta-data key and the optional native-first flow.

## Update server contract

The engine talks to `{apiBaseUrl}/ota-client/{apiVersion}`. All responses use the
envelope `{ success, message, data }`.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Reachability check. |
| `POST /app/info` | Update check. Form fields: `package`, `version` (APK `versionName`), `bundle` (active manifest version). |
| `GET /app/update/bundle/{id}` | Downloads a `release/*.tar.gz`. Authenticated with `Authorization: Bearer {session}`. |
| `GET /app/update/version/{id}?did={androidId}&token={session}` | Returns the APK download URL for a full app update. |

Every request carries `API-KEY` and an `X-Device-Info` header
(`did=…;mf=…;br=…;mdl=…;av=…;sdv=…`). Send `Content-Length` on the bundle
download if you want a determinate progress bar; without it the UI shows an
indeterminate one.

## Repository map

```
App.tsx                  placeholder UI for the host app
index.js                 RN entry point
package/                 the ota-client library  -> package/README.md
  src/                   TypeScript API, hooks and components
  android/               the Kotlin library module (com.otaclient.*)
  cli/                   the ota-client CLI
scripts/                 build scripts for the host app
release/                 generated OTA archives and APKs (gitignored)
.agents/context/         per-layer reference documentation
```

Deeper documentation, kept current as the code changes:

- [`.agents/context/package/`](.agents/context/package/README.md) — the library: layout, integration contract, rollback
- [`.agents/context/native-android/`](.agents/context/native-android/README.md) — startup flow, download and swap, storage, server contract
- [`.agents/context/react-native/`](.agents/context/react-native/README.md) — app shell, build scripts, release artifact format

## Current status

The library in `package/` is complete and verified. **The host app has not been
migrated to it yet** — it still carries its own in-app copy of the engine:

```
android/app/src/main/java/com/otaclient/{utils,data}
android/app/src/main/java/com/otaclient/{SplashActivity,ConfigureActivity}.kt
```

The library ships the same fully qualified class names, so the app cannot
declare the dependency until those are deleted. Until then the app exercises the
older code path, which is also why its `MainApplication` resolves
`AppStorage.RUNNING_BUNDLE_DIR` by hand rather than calling
`OtaBundleProvider.getJSBundleFile()`.

Known gaps:

- `ConfigureActivity` is a non-functional placeholder.
- A full app update hands the APK URL to the browser via `ACTION_VIEW` instead
  of downloading and installing it locally.
- The engine stands down in debuggable builds by default, so the real update
  path only runs against a release APK. Set `ota_client_allow_in_debug` to
  `true` to test it in debug.

## License

MIT
