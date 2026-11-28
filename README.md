# SportCamHUD na Androida

Port windowsowej aplikacji (PySide6) nakładającej telemetrię na nagrania z kamer sportowych.
Kotlin + Jetpack Compose, minSdk 26 (Android 8.0), eksport przez Media3 Transformer.

## Jak zbudować

1. Otwórz katalog `android-app` w Android Studio (Narwhal lub nowszym) i poczekaj na synchronizację Gradle.
2. Uruchom konfigurację `app` na telefonie albo zbuduj APK: `./gradlew :app:assembleDebug`
   (wynik: `app/build/outputs/apk/debug/app-debug.apk`).
3. Testy logiki (bez Androida): `./gradlew :core:test`. Test `RenderPreviewTest` zapisuje podgląd HUD
   do `core/build/hud-preview/`.

Wymagane: JDK 17+, Android SDK 36.

## Co działa (przeniesione z wersji Windows)

- **Wskaźniki**: zegary (gauge), kompas (igła / róża), paski (linijka pozioma, pionowa dla nachylenia,
  segmenty baterii), wartości tekstowe, data/godzina z ikoną zegara, wykresy (cała aktywność / film / okno),
  mapa trasy (kafelki CARTO/Esri, opcja „track up” i okrągła maska). Domyślny układ to port `default_layout`;
  pozycje w % kadru, skalowanie jak w oryginale (`s()`, `min_dim`, obrys tekstu).
- **Edytor wskaźników**: włączanie/wyłączanie, przeciąganie po podglądzie, edycja parametrów,
  wiele układów, import/eksport JSON (zgodny formatem z wersją Windows).
- **Pliki FIT**: oficjalne Garmin FIT SDK. Prędkość, dystans, wysokość, tętno, kadencja, moc, temperatura,
  bateria Garmina, kurs i nachylenie wyliczane z GPS, czas aktywny (pauzy), dodatkowe pola rekordu jako
  `fit_<nazwa>` i pola deweloperskie jako `fit_dev_<nazwa>`.
- **Synchronizacja czasu**: SmartSync jak w oryginale (bezpośrednio → korekta strefy czasowej →
  wyrównanie startu) plus ręczna korekta ± sekundy.
- **Garmin Connect**: logowanie przez stronę Garmin SSO w WebView (hasło nigdy nie trafia do aplikacji),
  wymiana biletu na tokeny DI OAuth2, odświeżanie tokenów, tokeny szyfrowane w magazynie kluczy Androida.
  Automatyczne dopasowanie aktywności do filmu (ten sam algorytm punktacji co w Windows), wybór z listy
  przy niejednoznaczności, pamięć podręczna pobranych FIT.
- **Telemetria GoPro (GPMF)**: czytana prosto ze ścieżki `gpmd` w pliku MP4 (bez FFmpeg). GPS (GPS9 i starszy
  GPS5+GPSU), ISO, czas naświetlania, temperatura kamery oraz przechył liczony z akcelerometru i żyroskopu
  (filtr komplementarny jak w TeleM, z osią, kalibracją, odwróceniem, mnożnikiem i wygładzaniem). Czas startu filmu
  bierze się z GPS kamery, a SmartSync najpierw dopasowuje ślad GPS z FIT do śladu GPS kamery. Bez pliku FIT
  prędkość, dystans, wysokość, kurs i mapa idą z GPS GoPro.
- **Eksport**: film z nałożonym HUD do `Filmy/SportCamHUD`, wybór fragmentu, postęp,
  chwilowe i średnie FPS (klatki przekazane do nakładki Media3), licznik klatek HUD, czas od startu
  i szacowany czas do końca (na podstawie postępu Media3). Przy finalizacji ETA jest orientacyjne.
  Udostępnianie działa z systemowej galerii.
  Aplikację można też otworzyć przez „Udostępnij” z galerii.

## Korekta położenia HUD (Android 1.2.1)

- `track_map` z Windows `def_layout.json` wykorzystuje `map_anchor: "topleft"`:
  współrzędne `x` i `y` oznaczają lewy górny róg mapy. Poprzedni Android
  błędnie traktował je jako środek, przez co mapa była obcinana przez krawędź filmu.
  Starsze układy bez tej flagi przeliczane są kompatybilnie z Windows.
- W zakładce **Wskaźniki** dotknij element na podglądzie lub wybierz go z listy.
  Zaznaczenie pokazuje obrys i uchwyt; przeciągnij palcem na filmie, by zmienić
  pozycję. Puszczenie palca zapisuje współrzędne w JSON. Przycisk **Właściwości**
  otwiera dodatkowe opcje i pola X/Y w procentach. Wybranie pozycji z listy nie
  blokuje już podglądu oknem dialogowym.
- Tekst pionowych linijek przy krawędziach jest kierowany do wnętrza kadru.
  Zaawansowane style Windows nie są jeszcze w pełni identyczne na Androidzie.

## Wspólny preset z Windows i GPMF

- Android zawiera `app/src/main/assets/def_layout.json`, skopiowany z gałęzi `main`
  `malcerz/SportCamHUD`. Na świeżej instalacji lub po `Resetuj układ` używa właśnie tego presetu.
- Import i eksport JSON nie domiesza automatycznie wskaźników Androida: nazwy kluczy,
  pola `source`, style, układ, `global`, `integrations`, `smoothing` i nieznane opcje
  zachowują się podczas cyklu import → eksport. Jest to **zgodność pliku i odwzorowania danych**;
  nie wszystkie zaawansowane efekty graficzne Windows mają jeszcze identyczny renderer Android.
- Odczyt GPMF odbywa się ze ścieżki `gpmd` pliku GoPro przez deskryptor z losowym dostępem.
  W Projekt/Eksport aplikacja informuje o GPS, ISO, migawce, temperaturze i IMU albo zgłasza
  brak metadanych/błąd odczytu. Przy obecności FIT brakujące strumienie GPS są uzupełniane z GPMF.
  Odczyt jest zależny od dostępności ścieżki `gpmd` i możliwości seek dostawcy pliku.

## Czego nie przeniesiono

- Telemetrii DJI i łączenia wielu plików z jednej jazdy (multifile).
- Plików GPX.
- Sprzętowych potoków FFmpeg (zastąpione przez Media3, który i tak używa sprzętowego kodeka telefonu).

## Do sprawdzenia na urządzeniu

Moduł `core` jest przetestowany (21 testów, w tym dekodowanie FIT, GPMF na syntetycznym nagraniu GoPro, synchronizacja, dopasowanie aktywności,
klient Garmin na atrapie serwera i render HUD). Modułu `app` nie dało się skompilować w środowisku, w którym
powstał (brak dostępu do repozytorium Google Maven), dlatego przy pierwszym uruchomieniu warto sprawdzić:
- orientację nakładki dla filmów nagranych pionowo / z rotacją w metadanych,
- czy przy eksporcie przyciętego fragmentu wskaźniki zgadzają się z obrazem,
- logowanie do Garmin Connect (Garmin zmienia od czasu do czasu identyfikatory klienta; lista zapasowych
  jest w `GarminAuthClient`).

## Struktura

```
core/   czysty Kotlin/JVM: telemetry/, fit/, garmin/, layout/, render/ (rysowanie przez interfejs HudCanvas)
app/    Android: UI w Compose (ui/), eksport Media3 (export/), logowanie Garmin (garmin/),
        HudCanvas na android.graphics (render/), HudViewModel
```
