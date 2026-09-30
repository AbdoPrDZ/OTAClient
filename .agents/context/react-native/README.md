# React Native layer

## Overview

The React Native side is a thin shell. Its only real job is to provide the JS bundle that the native engine downloads and swaps at runtime. There is currently no business logic; `App.tsx` renders a version placeholder.

## Entry points

- `index.js` — registers the `OTAClient` component (name from `app.json`) with `AppRegistry`.
- `App.tsx` — renders a centered `Text` showing the app + bundle versions (currently `Version 1.0.0, Bundle 0.0.2`). Wrapped in `SafeAreaProvider` from `react-native-safe-area-context`; handles dark/light status bar via `useColorScheme`.

## Versions

Both live in `package.json`:

- `version` — the **app/build version** (currently `0.0.2`). Increment when a new build/OTA release is published.
- `runtimeVersion` — the **native runtime compatibility** (currently `0.0.1`). Keep aligned with the Android native capabilities; a runtime change requires a new APK.

Bundle/archive names embed both: `{appName}-{runtimeVersion}-{version}`.

## Config files

- `metro.config.js` — default Metro config from `@react-native/metro-config` (no customization).
- `tsconfig.json` — extends `@react-native/typescript-config`, types `["jest"]`.
- `babel.config.js` — `@react-native/babel-preset`.
- `jest.config.js` / `__tests__/App.test.tsx` — Jest snapshot test for the App component.
- `.eslintrc.js` / `.prettierrc.js` — `@react-native/eslint-config`, standard Prettier.

## Build scripts (`scripts/`)

Node scripts run with `tsx`. Both compute a `manifest.json` with `version`, `runtimeVersion`, `bundle` (file name), `checksum` (MD5 of the bundle), `size`, and `createdAt`.

### `build-release.ts` (`npm run build:release`)

Creates the OTA artifact served by the update server.

1. Reads `package.json` for `name`, `version`, `runtimeVersion`; `buildName = {name}-{runtimeVersion}-{version}`.
2. Builds the JS bundle into a temp dir via `npx react-native bundle --platform android --dev false --entry-file index.js` (no `--assets-dest` — release bundles have no extra resources).
3. Writes `manifest.json` next to the bundle.
4. Archives both at the tar root into `release/{buildName}.tar.gz` (`tar.create` with gzip).
5. Cleans the temp dir; `release/` ends up containing only `.tar.gz` files.

Bundle filename is configurable via env: `BUILD_BUNDLE_NAME` (default `@version.android.bundle`, with `@version` → `{runtimeVersion}-{version}`, `@date` → ISO timestamp). Temp dir via `BUILD_TMP_DIR` (default `tmp/`), output dir via `RELEASE_DIR` (default `release/`).

### `build-android.ts` (`npm run build:android`)

Builds the bundle embedded in the base APK.

- Runs `react-native bundle` with `--assets-dest` so drawable assets are written into `android/app/src/main/res`.
- Writes bundle into `android/app/src/main/assets/` (env `ANDROID_ASSETS_DIR`) and `manifest.json` next to it.
- This is what the APK ships with as the fallback/initial bundle.

### `build-apk.ts` (`npm run build:apk`)

Builds a release APK and stores it in `release/`.

- `npm run build:apk` first runs `build:android` (fresh embedded bundle + assets), then `build-apk.ts`.
- Reads `package.json` for `name` and `version`; `apkName = {name}-{version}.apk`.
- Runs `gradlew assembleRelease` in `android/` (env `ANDROID_DIR`, default `android/`), then copies `android/app/build/outputs/apk/release/app-release.apk` into `release/` (env `RELEASE_DIR`).
- Note: release builds are currently signed with the **debug keystore** (see `android/app/build.gradle`); a real keystore is needed for production distribution.

### `generate-rundom-uid.ts`

Prints a `randomUUID()` to stdout (used for IDs/tokens).

## Release artifact format

A `release/*.tar.gz` contains exactly two files at the archive root:

- `{bundleName}` — the Hermes/JS bundle
- `manifest.json` — `{ version, runtimeVersion, bundle, checksum, size, createdAt }`

The native engine extracts this archive, verifies `manifest.json` + the bundle file, and stages them under `filesDir/{runtimeVersion}-{version}`.

## Build commands (root)

- `build-bundle.cmd` — ad-hoc: bundles JS into `android/app/src/main/assets` + `res` directly (legacy of `build-android.ts`).
- `npm run build:android` — preferred command for the base APK bundle.
- `npm run build:release` — preferred command for the OTA artifact.
- `npm run build:apk` — builds the release APK (`release/{name}-{version}.apk`), running `build:android` first.