# Status projektu

_Ostatnia aktualizacja: 2026-11-10_

## Gdzie jesteśmy

**Kod gotowy, przetestowany na emulatorze. Czeka na field test na Galaxy S25 Ultra.**

Repo: https://github.com/kopolot/hc-sheets-sync (publiczne, gałąź `main`). Plan i decyzje projektowe: [PLAN.md](PLAN.md). Setup: [README.md](README.md).

## Zrobione

- Apka Android (Kotlin, Compose, minSdk 28, target/compile API 37, AGP 9.5.1, Gradle 9.9.1, HC client 1.1.0)
  - odczyt z Health Connect: posiłki/makro, kalorie total i aktywne, kroki, tętno spoczynkowe (agregaty HC), waga, tkanka tłuszczowa, sen, treningi (surowe rekordy)
  - zgody: dane + osobno tło i historia; rationale activity i alias VIEW_PERMISSION_USAGE
  - WorkManager: codziennie ok. 05:30 + przycisk „Sync teraz”; wysyłka ostatnich 7 dni, pierwszy run to backfill 90 dni (30 bez zgody na historię), paczki po 30 dni
  - UI: status zgód, ostatni sync, wynik, log (50 wpisów), diagnostyka (agregat HC vs suma surowa + źródła)
- Apps Script (`apps-script/Code.gs`): upsert zakładek `daily` (po dacie) i `workouts` (po ID sesji), usuwanie treningów skasowanych w HC, secret w body, `doGet` jako health check
- Testy: 23 unit testy (agregacja dni, DST, waga, sen, treningi, payload, klient HTTP z 302) + offline test Apps Script (`node apps-script/Code.test.js`); lint czysty

## Zweryfikowane na emulatorze (Pixel 9, Android 15, z lokalnym mockiem Apps Script)

- okno zgód HC pokazuje się (dane, potem tło + historia)
- backfill: 90 dni w 3 paczkach; kolejny sync: 7 dni
- przekierowanie 302 obsłużone
- sync w tle (apka schowana, worker odpalony po powrocie sieci) zakończony sukcesem
- diagnostyka działa

**Odkrycie:** `kcal_burned_total` z agregatu HC zawiera szacunek BMR liczony przez Health Connect. Na pustym emulatorze, bez żadnych rekordów, wychodzi ~1565 kcal/dzień. Opisane w README. Dla bota pewniejsze są `kcal_active` i treningi.

## Niezweryfikowane

- działanie na S25 z prawdziwymi danymi z Samsung Health
- prawdziwy Apps Script i arkusz (testowany tylko mock + test offline)
- czy One UI nie usypia codziennego workera
- automatyczny periodic run o 05:30 (na emulatorze wymuszony tylko one-time run w tle; zegara nie dało się przestawić bez roota)

## Następny krok: field test

**Dzień 0:**
1. Apps Script: wklej `Code.gs`, ustaw Script Property `SECRET`, wdróż jako Web app (Ja / Każdy). Sprawdź, czy `/exec` w przeglądarce zwraca `{"ok":true,...}`.
2. `local.properties`: `SYNC_URL`, `SYNC_SECRET`, potem `./gradlew assembleDebug` i `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Samsung Health → Ustawienia → Health Connect: włączony zapis wszystkich typów.
4. W apce: nadaj zgody (oba przyciski), „Sync teraz”, sprawdź arkusz. Bateria apki: „Bez ograniczeń”.

**Dzień 1–7: codziennie rano porównaj wczorajszy wiersz z SH:**
- `kcal_in` i makro: identyczne (±1)
- `steps`: blisko
- `weight_kg`: poranny pomiar
- `sleep_min`: blisko
- `workouts`: te same treningi
- `kcal_burned_total`: sprawdzić „Diagnostyką”

**Scenariusze do sprawdzenia w trakcie tygodnia:**
- posiłek dopisany po fakcie poprawia wiersz przy następnym syncu
- usunięty trening znika z zakładki `workouts`
- tryb samolotowy na noc: sync wykonuje się po powrocie sieci
- codziennie „Ostatni udany” / `updated_at` po 05:30

**Kryterium sukcesu:** 7 dni z rzędu automatyczny sync bez dotykania apki i wartości zgodne z SH.

**Przy problemach:**
- zrzut „Diagnostyki” dla danego dnia
- zrzut „Logu”
- przy podłączonym telefonie:
  ```
  adb shell dumpsys jobscheduler | grep -A3 hcsync
  adb logcat -d | grep -E "WM-WorkerWrapper.*hcsync"
  ```

## Otwarte sprawy

- [ ] Field test (powyżej)
- [ ] Podłączenie Grok Bota do arkusza (sposób odczytu: udostępnienie / publikacja CSV / API, do ustalenia)
- [ ] Opcjonalnie: przepisać autora commitów na adres GitHub `noreply` (teraz w publicznych commitach jest prywatny e-mail z `git config`)
- [ ] Ewentualne poprawki po field teście (np. reguła wagi/snu, mapowanie typów treningów Samsunga)

## Notatki środowiskowe (komputer deweloperski)

- build przez JBR z Android Studio (flatpak): `JAVA_HOME=/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr`
- doinstalowane: SDK platform `android-37.0`, build-tools 37.0.0, cmdline-tools (`~/Android/Sdk/cmdline-tools/latest`)
- emulator `Pixel_9` (API 35, obraz Play Store, bez roota) ma zainstalowaną apkę z nadanymi zgodami
- debug build pozwala na zwykłe HTTP wyłącznie do `localhost`/`127.0.0.1` (przez `adb reverse tcp:8765 tcp:8765`), do testów z lokalnym mockiem
