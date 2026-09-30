# AGENTS.md

Project: **ota-client** — a React Native OTA (Over-The-Air) update client.

## Overview

The app boots from a JS bundle that can be updated at runtime without rebuilding the APK. A native Android layer checks an OTA server on launch, downloads new JS bundles, and swaps the running bundle. The React Native side is a thin shell; most logic lives in native Kotlin and in Node.js build scripts.

## Tech stack

- **React Native 0.85** (React 19, TypeScript, New Architecture / Fabric enabled)
- **Android native** — Kotlin, Ktor (HTTP), Gson, commons-compress (tar.gz), SharedPreferences
- **Node build tooling** — `tsx`, `dotenv`, `fs-extra`, `tar`; JS bundles built via `react-native bundle`

## Commands

| Command | Purpose |
| --- | --- |
| `npm start` | Start Metro dev server |
| `npm run android` / `npm run ios` | Run the app |
| `npm run build:release` | Build release JS bundle + `manifest.json` → `release/*.tar.gz` (OTA artifact) |
| `npm run build:android` | Build bundle + assets into `android/app/src/main/{assets,res}` (base APK) |
| `npm run lint` / `npm test` | Lint / Jest |

## Repository layout

- `App.tsx`, `index.js` — RN app entry (currently a version placeholder)
- `scripts/` — Node build scripts that produce the OTA bundle artifacts
- `android/app/src/main/java/com/otaclient/` — native OTA engine (Splash/Configure/Main activities, `OTAClient`, `AppStorage`, data models)
- `release/` — generated `.tar.gz` release artifacts served by the OTA server
- `.agents/context/` — detailed reference docs (see below)

## Detailed context

Complete per-layer documentation lives under `.agents/context/`:

- `.agents/context/react-native/README.md` — RN app shell, build scripts, release artifact format, versions
- `.agents/context/native-android/README.md` — native OTA engine: startup flow, update check, bundle download/swap, storage, API contract
- `.agents/context/package/README.md` — the `ota-client` npm package in `package/`: TS API, Kotlin library module, CLI, host integration contract

Read the relevant context file before making changes to that layer.

## The app is now a consumer of `package/`

The engine lives in the `ota-client` package (`package/`) rather than in
`android/app`. The app still contains the older in-app copy of the Kotlin classes
for reference; when migrating, delete `android/app/src/main/java/com/otaclient/{utils,data,ota}`
and the app-local `SplashActivity`/`ConfigureActivity` first — the package ships
the same fully qualified class names, and two copies of one class break the build.