# Ojo Zoom: notatki i wytyczne dla Claude

## Projekt
- Fork Ojo (penguin86/ojo, przeglądarka kamer RTSP na Androida, Java, libVLC). Rozwijamy go o brakujące funkcje.
- Właściciel: krzsiek. Rozmowa po polsku; kod, komentarze, commity i README po angielsku (jak w oryginale).
- Funkcje forka są opisane w README.md (sekcja "What's different from Ojo") i na ekranie Info w aplikacji.
  Każda nowa funkcja: zaktualizuj oba miejsca (stringi w values, values-it, values-ru).

## Budowanie
- Tylko przez GitHub Actions (`.github/workflows/build-apk.yml`); w kontenerze Claude nie ma Android SDK.
- Każdy push buduje `assembleRelease`, APK jako artefakt `ojo-zoom-apk`.
- Gałąź domyślna to `stable` (nie ma master). Push na `stable` z nowym `versionName` sam publikuje release `v<versionName>` z APK (tag tworzy workflow). Opis z `RELEASE_NOTES.md` (zaktualizuj przed pushem).
- Proxy kontenera zrywa `git push` tagów; dlatego tag robi workflow, nie Claude. Push gałęzi działa.
- Przed releasem podbij `versionCode` i `versionName` w `app/build.gradle` i dodaj `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- Podpis: sekrety repo `OJO_KEYSTORE_BASE64`, `OJO_KEYSTORE_PASSWORD`, `OJO_KEY_ALIAS`, `OJO_KEY_PASSWORD`. Bez nich APK ma klucz debug z runnera i nie zaktualizuje zainstalowanej wersji.
- Stan CI można odczytać bez tokena: `curl https://api.github.com/repos/krzsiek/ojo-zoom/actions/runs`.

## Ustalenia techniczne
- applicationId `it.danieleverducci.ojo.zoom` (instaluje się obok oryginału); namespace kodu zostaje `it.danieleverducci.ojo`.
- Tylko ARM (`arm64-v8a`, `armeabi-v7a`) w jednym APK, minSdk 21. Bez x86.
  armeabi-v7a zostaje, bo wiele boksów Android TV ma 32-bitowy system mimo 64-bitowego procesora.
- libVLC 3.7.7 z Maven Central (`org.videolan.android:libvlc-all`, od v0.3; wcześniej 2.1.12 bez sout, nie umiało nagrywać). AAR deklaruje minCompileSdk 36, więc w `app/build.gradle` wyłączony jest task `check*AarMetadata` (zostajemy na AGP 7.4 / compileSdk 33). Kotlin z libvlc wykluczony (Java API go nie używa).
- Wideo renderowane na `TextureView` (SurfaceView nie obsługuje skalowania). VLC podpinany dopiero w `onSurfaceTextureAvailable`. Proporcje obrazu trzyma `TextureView.setTransform` z rozmiaru z `OnNewVideoLayoutListener`.
- Układ kamer: `CameraGridLayout` (siatka w poziomie, lista 16:9 w pionie w ScrollView, fullscreen = jedna kamera, reszta 1x1 px). Widoki nie zmieniają rodzica, więc obrót nie restartuje strumieni.
- Snapshot: `TextureView.getBitmap(w, h)` w rozdzielczości wideo. Nagrywanie: `MediaPlayer.record(dir)` do katalogu aplikacji, po zakończeniu `MediaSaver` przenosi plik do Movies/OjoZoom (MediaStore na 10+, plik + skaner na 5-9).
- Przyciski (ustawienia, snapshot, nagrywanie) ukrywane w `MainActivity`; zoom, gesty i przesuwanie kamer w `SurveillanceFragment.CameraView`.
