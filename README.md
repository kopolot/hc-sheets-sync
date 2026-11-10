# HC Sheets Sync

Prywatna apka na Androida, która raz dziennie czyta dane z **Health Connect** (u mnie źródłem jest Samsung Health) i wysyła sumy dzienne do **Google Sheets** przez Google Apps Script. Arkusz czyta potem bot-trener.

- Kotlin, Jetpack Compose, WorkManager, minSdk 28, target API 37
- bez Play Store: sideload na własny telefon
- w repo nie ma URL-a, sekretu ani żadnych danych zdrowotnych

## Co jest wysyłane

Jeden wiersz na dzień (strefa czasowa telefonu) w zakładce `daily`:

| kolumna | skąd | reguła |
|---|---|---|
| `kcal_in`, `protein_g`, `fat_g`, `carbs_g` | NutritionRecord | suma dnia (agregat HC) |
| `kcal_burned_total` | TotalCaloriesBurnedRecord | suma dnia (agregat HC), zobacz uwagi |
| `kcal_active` | ActiveCaloriesBurnedRecord | suma dnia (agregat HC) |
| `steps` | StepsRecord | suma dnia (agregat HC, z deduplikacją) |
| `resting_hr` | RestingHeartRateRecord | średnia dnia |
| `weight_kg`, `body_fat_pct` | Weight/BodyFatRecord | **najwcześniejszy** pomiar dnia |
| `sleep_min` | SleepSessionRecord | sen **kończący się** danego dnia, bez etapów „awake” |
| `workouts_count`, `workouts_min`, `workouts_summary` | ExerciseSessionRecord | treningi **zaczynające się** danego dnia |
| `updated_at` | | czas zapisu |

Pusta komórka oznacza brak danych, a nie zero. Brak zalogowanych posiłków to nie jest „0 kcal”.

Zakładka `workouts` ma jeden wiersz na trening: `id, date, type, title, start, end, min, updated_at`. Upsert idzie po ID sesji z Health Connect. Trening usunięty w Samsung Health znika z arkusza przy najbliższym syncu, który obejmuje jego dzień.

### Jak działa sync

- codziennie około 05:30 (WorkManager, wymaga sieci) plus przycisk **Sync teraz**;
- za każdym razem wysyłane jest **ostatnie 7 dni**, a upsert idzie po dacie. Przegapione runy i posiłki dopisane później same się nadrabiają;
- pierwszy sync to backfill: 90 dni ze zgodą na historię, inaczej 30;
- sumy liczy API agregujące Health Connect, które deduplikuje dane między aplikacjami i dzieli rekordy na granicy północy. Sekcja **Diagnostyka** w apce porównuje agregat z prostą sumą surowych rekordów i pokazuje, które aplikacje zapisały dane.

## Setup

### 1. Arkusz i Apps Script

1. Utwórz nowy Google Sheet.
2. **Rozszerzenia → Apps Script**, wklej zawartość [`apps-script/Code.gs`](apps-script/Code.gs) i zapisz.
3. **Ustawienia projektu → Właściwości skryptu**: dodaj `SECRET` z długim losowym ciągiem (litery i cyfry), np. `openssl rand -hex 24`.
4. **Wdróż → Nowe wdrożenie → Aplikacja internetowa**:
   - *Wykonaj jako:* **Ja**
   - *Kto ma dostęp:* **Każdy** (inaczej Google zwróci stronę logowania zamiast JSON-a)
5. Skopiuj URL kończący się na `/exec`. Po otwarciu go w przeglądarce powinno się pokazać `{"ok":true,"service":"hc-sheets-sync"}`.

Zakładki `daily` i `workouts` tworzą się same przy pierwszym zapisie.

> Po zmianie kodu skryptu: **Wdróż → Zarządzaj wdrożeniami → edytuj → Nowa wersja**. Wtedy URL się nie zmienia.

Test z terminala (fikcyjne dane):

```bash
curl -L -H 'Content-Type: application/json' \
  -d '{"secret":"TWOJ_SECRET","schema":1,"app_version":"curl","days":[{"date":"2000-01-01","kcal_in":1,"protein_g":null,"fat_g":null,"carbs_g":null,"kcal_burned_total":null,"kcal_active":null,"steps":null,"resting_hr":null,"weight_kg":null,"body_fat_pct":null,"sleep_min":null,"workouts":[]}]}' \
  'https://script.google.com/macros/s/XXXX/exec'
```

Logikę skryptu można też sprawdzić offline: `node apps-script/Code.test.js`.

Do testów z emulatorem wersja debug pozwala na zwykłe HTTP wyłącznie do `10.0.2.2` (host emulatora), np. `SYNC_URL=http://10.0.2.2:8765/exec SYNC_SECRET=test ./gradlew assembleDebug` z lokalnym mockiem. Wersja release tego nie ma.

### 2. Konfiguracja apki

```bash
cp local.properties.example local.properties
```

Uzupełnij `sdk.dir`, `SYNC_URL` (URL `/exec`) i `SYNC_SECRET` (ta sama wartość co `SECRET`). Plik `local.properties` jest w `.gitignore`. Wartości trafiają do `BuildConfig`, czyli do APK. Przy prywatnym sideloadzie to w porządku, ale nie udostępniaj nikomu zbudowanego APK.

### 3. Build i instalacja

W Android Studio wystarczy otworzyć projekt i uruchomić. Z terminala (JDK 17+):

```bash
./gradlew testDebugUnitTest   # unit testy
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Na telefonie

1. Otwórz apkę, kliknij **Nadaj zgody** i zaznacz wszystko.
2. Kliknij **Tło + historia**. Bez zgody na odczyt w tle automatyczny sync nie zadziała.
3. **Sync teraz** i sprawdź arkusz.
4. **Samsung:** Ustawienia → Aplikacje → HC Sheets Sync → Bateria → **Bez ograniczeń**. Inaczej One UI potrafi uśpić WorkManagera.
5. W Samsung Health sprawdź, czy synchronizacja z Health Connect jest włączona (Ustawienia → Health Connect) dla wszystkich typów danych.

## Uwagi

- **`kcal_burned_total` zawiera szacunek BMR liczony przez Health Connect.** Na czystym emulatorze, bez żadnych rekordów, agregat HC zwraca ~1565 kcal na każdy dzień. HC sam dolicza przemianę spoczynkową (z wagi/wzrostu albo domyślnych wartości). Samsung nie zapisuje do HC kalorii aktywności spoza treningów, więc ta liczba to w praktyce „BMR + to, co dotarło do HC”, a nie pełne TDEE. Dla bota pewniejsze są `kcal_active` i treningi. Sekcja Diagnostyka pokazuje, ile z tej liczby pochodzi z prawdziwych rekordów.
- **Strefa czasowa:** dni liczone są w strefie telefonu. Jeśli rekord ma własny offset strefy (np. pomiar w podróży), dzień liczy się według niego.
- **Dzisiejszy dzień** jest niepełny i zostanie nadpisany przy kolejnym syncu.
- **Powtórz backfill:** przycisk w sekcji Sync (np. po nadaniu zgody na historię).

## Struktura

```
app/src/main/java/io/github/kopolot/hcsync/
  domain/   DailyAggregator – czysta logika dni (unit testy)
  health/   Health Connect: zgody, odczyt, agregaty, diagnostyka
  net/      payload JSON + klient Apps Script
  sync/     WorkManager + orkiestracja
  data/     DataStore: status, log (bez danych zdrowotnych)
  ui/       jeden ekran Compose + ekran rationale dla HC
apps-script/Code.gs        odbiornik w Apps Script
apps-script/Code.test.js   offline test skryptu (Node)
```
