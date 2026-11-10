# HC → Google Sheets sync (prywatna apka Android dla Grok Bota)

## Context
Samsung Health (Galaxy S25 Ultra) zapisuje dane do Health Connect. Bot-trener (Grok Bot) potrzebuje ich w Google Sheets. Apka raz dziennie czyta HC, liczy sumy dzienne i wysyła je do Apps Script Web App, który robi upsert do arkusza. Apka jest sideloadowana, tylko na jeden telefon, a repo jest publiczne bez sekretów i danych zdrowotnych. Katalog projektu jest pusty (greenfield).

## Ustalone decyzje
- **Agregacja hybrydowa.** Sumy (kcal/makro, kalorie total i aktywne, kroki, średnie tętno spoczynkowe) liczy `aggregateGroupByPeriod(Period.ofDays(1))` na czasie lokalnym: HC deduplikuje źródła i dzieli rekordy przechodzące przez północ. Treningi, waga, tkanka tłuszczowa i sen pochodzą z surowych rekordów.
- **Tryb diagnostyczny.** Dla wybranego dnia ekran pokazuje sumę z agregacji HC, sumę surowych rekordów i listę `dataOrigin` (pakietów źródłowych).
- **Arkusz.** Zakładka `daily` (upsert po `date`) oraz zakładka `workouts` (upsert po ID sesji z HC). Dla wysłanych dat Apps Script usuwa treningi, których już nie ma w HC.
- **Dodatkowe dane:** sen, tętno spoczynkowe, kalorie aktywne.
- **GitHub:** lokalnie `git init` z commitami, na koniec (po potwierdzeniu) `gh repo create --public` i push.
- **Poprawki względem promptu Groka:**
  - secret idzie w body, bo `doPost` nie widzi nagłówków;
  - jeden POST zawiera tablicę dni;
  - brak danych zapisuję jako `null`, nie 0, bo „nie zalogowano posiłków” to nie to samo co „0 kcal”;
  - redirect 302 po POST obsługuje OkHttp (podąża za nim i czyta odpowiedź JSON).

## Reguły danych (per dzień, strefa `ZoneId.systemDefault()`, czyli Europe/Warsaw)
| Pole | Źródło | Reguła |
|---|---|---|
| kcal_in, protein_g, fat_g, carbs_g | NutritionRecord, aggregate | suma dnia |
| kcal_burned_total | TotalCaloriesBurnedRecord, aggregate | suma; README opisuje, że to nie jest pełne TDEE |
| kcal_active | ActiveCaloriesBurnedRecord, aggregate | suma |
| steps | StepsRecord, aggregate | suma |
| resting_hr | RestingHeartRateRecord, aggregate | BPM_AVG |
| weight_kg, body_fat_pct | surowe | **najwcześniejszy** pomiar dnia (poranny) |
| sleep_min | SleepSessionRecord, surowe | sesje **kończące się** danego dnia; czas sesji minus etapy AWAKE, jeśli są |
| workouts[] | ExerciseSessionRecord, surowe | przypisanie po dacie **startu**; `{id, type, title, start, end, min}`; typ z własnej mapy int→nazwa, a nieznane jako `type_<n>` |

## Payload
```json
{ "secret": "...", "schema": 1, "app_version": "...",
  "days": [ { "date": "2026-11-10", "kcal_in": 2350, "protein_g": 160, "fat_g": 70, "carbs_g": 250,
      "kcal_burned_total": 900, "kcal_active": 450, "steps": 9500, "resting_hr": 54,
      "weight_kg": 82.4, "body_fat_pct": 17.1, "sleep_min": 412,
      "workouts": [ { "id": "...", "type": "strength_training", "title": "...", "start": "ISO", "end": "ISO", "min": 62 } ] } ] }
```
W `daily` workouts są spłaszczane do kolumn `workouts_count`, `workouts_min` i `workouts_summary` (np. „strength_training 62min; walking 30min”). Wiersz dostaje też kolumnę `updated_at`.

## Sync
- `SyncWorker` (CoroutineWorker): `PeriodicWorkRequest` co 24h z constraintem `NetworkType.CONNECTED`, `enqueueUniquePeriodicWork(KEEP)` przy starcie apki. Przycisk „Sync teraz” odpala `OneTimeWorkRequest` (expedited, jeśli się da).
- Okno wysyłki: ostatnie 7 dni z dzisiejszym włącznie. Dzisiejszy dzień jest niepełny i zostanie nadpisany przy kolejnym syncu.
- Pierwszy run, czyli brak flagi `backfillDone` w DataStore: 90 dni, jeśli jest `READ_HEALTH_DATA_HISTORY`, w przeciwnym razie 30. Wysyłka partiami po 30 dni.
- Przed odczytem w tle sprawdzam `HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` i nadane uprawnienia. Jeśli czegoś brakuje, wpis trafia do logu i worker kończy z `failure`, bez retry.
- Błąd sieci lub odpowiedź 5xx: `Result.retry()` z backoffem. Zły secret lub 4xx: `failure` plus wpis w logu.

## Stack
Kotlin 2.x, AGP (aktualny stable), Compose BOM + Material3, `androidx.health.connect:connect-client` (aktualny stable), WorkManager, DataStore Preferences, OkHttp, kotlinx.serialization. Bez Hilta: ręczny `AppContainer`. minSdk 28, compile/targetSdk = najnowsze stabilne API (doinstaluję platformę przez `sdkmanager`, lokalnie jest tylko android-35). Build przez JBR z Android Studio (flatpak), bo systemowy JDK 25 może nie współpracować z Gradle/AGP. Gradle wrapper w repo.

## Struktura
```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle/wrapper/*
.gitignore (local.properties, build/, .idea/, *.keystore, *.jks)
local.properties.example        # SYNC_URL=, SYNC_SECRET=
README.md                       # setup: arkusz, Apps Script, deploy, local.properties, build, adb install, zgody
apps-script/Code.gs             # doPost: secret z Script Properties, LockService, upsert daily + workouts
app/build.gradle.kts            # buildConfigField z local.properties (brak = pusty string, apka pokaże błąd konfiguracji)
app/src/main/AndroidManifest.xml
  - uses-permission: health.READ_{NUTRITION,TOTAL_CALORIES_BURNED,ACTIVE_CALORIES_BURNED,EXERCISE,WEIGHT,BODY_FAT,STEPS,RESTING_HEART_RATE,SLEEP}, READ_HEALTH_DATA_IN_BACKGROUND, READ_HEALTH_DATA_HISTORY, INTERNET
  - <queries> com.google.android.apps.healthdata
  - PermissionsRationaleActivity (intent-filter androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE)
  - activity-alias ViewPermissionUsageActivity (VIEW_PERMISSION_USAGE + HEALTH_PERMISSIONS category) → rationale
app/src/main/java/.../
  App.kt, AppContainer.kt
  health/HealthDataSource.kt        # interfejs (żeby dało się testować)
  health/HealthConnectDataSource.kt # implementacja: aggregate + odczyt surowych rekordów (paginacja pageToken)
  health/Permissions.kt             # zestaw uprawnień, sprawdzanie dostępności SDK i featurów
  health/ExerciseTypes.kt           # mapa int → nazwa
  domain/DailySummary.kt, DailyAggregator.kt  # czysta logika: przypisanie do dni, wybór wagi, sen, workouts, merge z wynikami aggregate
  net/SheetsClient.kt, Payload.kt
  sync/SyncWorker.kt, SyncScheduler.kt, SyncRepository.kt (orkiestracja: zakres dni → aggregator → client)
  data/StatusStore.kt               # DataStore: lastSyncAt, lastResult, backfillDone, log (ring buffer 50 wpisów)
  ui/MainActivity.kt, MainScreen.kt, MainViewModel.kt, DiagnosticsSection.kt, PermissionsRationaleActivity.kt
app/src/test/java/.../domain/DailyAggregatorTest.kt, net/PayloadTest.kt
```

## UI (jeden ekran)
Status Health Connect (dostępny / wymaga aktualizacji) oraz status zgód, z przyciskiem „Nadaj zgody” (`PermissionController.createRequestPermissionResultContract()`) i osobno uprawnieniami background/history. Dalej: ostatni sync (czas i wynik), przycisk „Sync teraz”, sekcja „Diagnostyka” z wyborem dnia (HC aggregate vs suma surowa + źródła) i log błędów (ostatnie 50 wpisów, przycisk „wyczyść”).

## Apps Script (`apps-script/Code.gs`)
- `doPost(e)`: `JSON.parse(e.postData.contents)`, porównanie `secret` z `PropertiesService.getScriptProperties().getProperty('SECRET')`, `LockService.getScriptLock()`.
- Zakładki i nagłówki tworzone są automatycznie, jeśli ich brakuje. Upsert robię przez mapę `date → row` i zapis zakresami, a nie komórka po komórce.
- `workouts`: upsert po `id`; dla dat obecnych w payloadzie usuwa wiersze z ID, których nie ma w payloadzie.
- Odpowiedź: `ContentService` JSON `{ok, days, workouts}` albo `{ok:false, error}`.
- README: Deploy → Web app, *Execute as: Me*, *Who has access: Anyone*. Jak ustawić Script Property `SECRET`. Że URL i secret lądują w APK, co jest OK przy prywatnym sideloadzie.

## Testy (unit, JVM)
`DailyAggregatorTest`:
- przypisanie treningu przechodzącego przez północ do dnia startu;
- najwcześniejszy pomiar wagi;
- sen kończący się danego dnia, z odjęciem AWAKE;
- dni zmiany czasu (2026-03-29 i 2026-10-25, Europe/Warsaw);
- brak danych daje `null`;
- poprawne złożenie wyników aggregate z danymi surowymi w okno 7 dni.

`PayloadTest`: kształt JSON i nulle.

## Kolejność prac
1. Szkielet Gradle, wrapper, manifest, `.gitignore`, `local.properties.example`. `git init`, pierwszy commit.
2. Domain + testy (TDD na agregatorze).
3. Warstwa Health Connect, uprawnienia, rationale activity.
4. Klient HTTP + Apps Script.
5. WorkManager + StatusStore.
6. UI + diagnostyka.
7. README. Sprawdzenie, że nic wrażliwego nie jest śledzone (`git ls-files`, grep po URL/secret). `gh repo create --public` dopiero po potwierdzeniu.

## Weryfikacja
- `./gradlew testDebugUnitTest` (zielone) oraz `./gradlew assembleDebug`.
- Apps Script: curl z błędnym secretem → `{ok:false}`; curl z przykładowym payloadem (fikcyjne dane) → wiersze w `daily` i `workouts`; drugi identyczny curl nie duplikuje wierszy.
- Telefon: `adb install -r app-debug.apk`, nadanie zgód (sprawdzam, że okno HC się pojawia), „Sync teraz”, sprawdzenie arkusza. Diagnostyka dla dnia z treningiem porównana z Samsung Health. `adb shell cmd jobscheduler run -f <pkg> <jobId>` albo test WorkManagera, żeby sprawdzić run w tle.
- Sprawdzenie na urządzeniu, czy HC dopełnia `kcal_burned_total` BMR-em; wynik opisuję w README.
