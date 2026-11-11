# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A private, sideloaded Android app (Kotlin, Compose, WorkManager, minSdk 28, target/compile API 37) that reads Health Connect data (Samsung Health is the source), computes one summary per day, and POSTs it to a Google Apps Script web app that upserts rows into a Google Sheet. A trainer bot then reads the sheet. User-facing docs (README, STATUS, PLAN, UI strings, log messages) are written in **Polish**. Keep that convention.

- `PLAN.md`: design decisions and data rules
- `STATUS.md`: what has been verified and what's next (field test on a Galaxy S25)
- `README.md`: setup and the column/rule table for the sheet

## Commands

Build with JDK 17+. On this machine, use Android Studio's bundled JBR:
`export JAVA_HOME=/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr`

```bash
./gradlew testDebugUnitTest                                   # all JVM unit tests
./gradlew testDebugUnitTest --tests '*DailyAggregatorTest*'   # one class
./gradlew testDebugUnitTest --tests '*DailyAggregatorTest.someMethod'  # one test
./gradlew lintDebug
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
node apps-script/Code.test.js                                 # offline test of Code.gs (mocked SpreadsheetApp)
```

Local e2e against a mock receiver. The debug build only allows cleartext HTTP to `localhost`/`127.0.0.1` (`app/src/debug/res/xml/network_security_config.xml`):

```bash
adb reverse tcp:8765 tcp:8765
SYNC_URL=http://127.0.0.1:8765/exec SYNC_SECRET=test ./gradlew assembleDebug
```

Rebuild without those env vars afterwards, or the app keeps syncing to the mock.

## Configuration

`SYNC_URL` and `SYNC_SECRET` are compiled into `BuildConfig` (see `app/build.gradle.kts`). Environment variables take precedence over `local.properties`, which is gitignored (template: `local.properties.example`). The repo must never contain a real URL, secret, or health data.

## Architecture

Data flow: `SyncWorker` / "Sync teraz" button → `SyncRepository.sync()` → `HealthDataSource.read()` → `DailyAggregator.build()` → `SheetsClient.upload()` → Apps Script `doPost` → sheet tabs `daily` + `workouts`.

- **DI** is manual, in `AppContainer` (no Hilt).
- **`health/`**: `HealthDataSource` is the interface and `HealthConnectDataSource` is the only real implementation. It returns a `HealthSnapshot` (in `domain/Models.kt`) that holds HC **aggregates** for sums (HC deduplicates across apps and splits records at midnight) plus **raw records** for weight, body fat, sleep, workouts, and calorie-burn presence. It also implements `diagnose()`, which compares the aggregate with a naive raw sum per source app for the UI's Diagnostics section.
- **`domain/DailyAggregator`** is pure logic and the main unit-test target. Day-assignment rules: weight/body fat = earliest measurement of the day; sleep = sessions *ending* that day, minus awake stages; workouts = sessions *starting* that day. A record's own zone offset decides its day, falling back to the phone zone. `null` means "no data", never zero.
- **`sync/SyncRepository`**: each run sends the last 7 days (upsert makes late edits and missed runs self-heal). The first run is a backfill: 90 days with the history permission, otherwise 30. Uploads go in 30-day chunks. Error mapping drives WorkManager behavior: `IOException`/`RemoteException`/`IllegalStateException` → Retry, `PermanentSyncException`/`SecurityException` → Failure. Periodic runs need the background-read permission.
- **`sync/SyncScheduler`**: a 24h periodic work request with its initial delay aimed at about 05:30, plus a unique one-time manual work request.
- **`net/`**: `Payload.kt` holds the JSON DTOs (`PAYLOAD_SCHEMA`) and `SheetsClient` uses OkHttp. Apps Script answers a POST with a 302 to googleusercontent, and OkHttp follows it as a GET. Non-JSON or `ok:false` responses become `PermanentSyncException`.
- **`data/StatusStore`**: a DataStore holding sync status, the backfill flag, and a 50-entry log. Never store health data there.

### Cross-file contract: adding or changing a daily field

A field must be changed in several places together: `Models.kt` (`DailySummary`), `DailyAggregator`, `Payload.kt` (`DayDto` + mapping), `apps-script/Code.gs`, both test suites, and the README column table. In `Code.gs`, fields added after the first release go in `EXTRA_FIELDS`, which is **appended at the end** of `DAILY_HEADERS`, so rows already in users' sheets keep their column positions. Don't insert new columns into `DAILY_FIELDS`. `Code.test.js` asserts column indices by position. After changing `Code.gs`, the user has to redeploy it as a new version of the existing deployment, which keeps the URL.

### Known data caveat

HC's `TotalCaloriesBurned` aggregate includes HC's own BMR estimate when no records exist (about 1565 kcal/day on an empty emulator). `kcal_burned_bmr_only` flags those days. Its value is `null` when total is missing or neither calorie permission is granted.
