# Ojo Zoom: notatki i wytyczne dla Claude

## Projekt
- Fork Ojo (penguin86/ojo, przeglądarka kamer RTSP na Androida, Java, libVLC). Rozwijamy go o brakujące funkcje.
- Właściciel: krzsiek. Rozmowa po polsku; kod, komentarze, commity i README po angielsku (jak w oryginale).
- Funkcje forka są opisane w README.md (sekcja "What's different from Ojo") i na ekranie Info w aplikacji.
  Każda nowa funkcja: zaktualizuj oba miejsca (stringi w values, values-it, values-ru).

## Budowanie
- Tylko przez GitHub Actions (`.github/workflows/build-apk.yml`); w kontenerze Claude nie ma Android SDK.
- Każdy push buduje `assembleRelease`, APK jako artefakt `ojo-zoom-apk`.
- Tag `vX.Y.Z` publikuje release z APK; opis release bierze z `RELEASE_NOTES.md` (zaktualizuj przed tagiem).
- Przed releasem podbij `versionCode` i `versionName` w `app/build.gradle` i dodaj `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- Podpis: sekrety repo `OJO_KEYSTORE_BASE64`, `OJO_KEYSTORE_PASSWORD`, `OJO_KEY_ALIAS`, `OJO_KEY_PASSWORD`. Bez nich APK ma klucz debug z runnera i nie zaktualizuje zainstalowanej wersji.
- Stan CI można odczytać bez tokena: `curl https://api.github.com/repos/krzsiek/ojo-zoom/actions/runs`.

## Ustalenia techniczne
- applicationId `it.danieleverducci.ojo.zoom` (instaluje się obok oryginału); namespace kodu zostaje `it.danieleverducci.ojo`.
- Tylko ARM (`arm64-v8a`, `armeabi-v7a`) w jednym APK, minSdk 21. Bez x86.
  armeabi-v7a zostaje, bo wiele boksów Android TV ma 32-bitowy system mimo 64-bitowego procesora.
- libVLC 2.1.12 jest w module `libvlc` (z mrmaffen/vlc-android-sdk @769f365, `android.support.annotation` zamienione na `androidx.annotation`), bo JCenter nie działa.
- Wideo renderowane na `TextureView` (SurfaceView nie obsługuje skalowania). VLC podpinany dopiero w `onSurfaceTextureAvailable`, inaczej libVLC 2.x się wywraca.
- Przycisk ustawień (FAB) ukrywany w `MainActivity`; zoom i gesty w `SurveillanceFragment.CameraView`.
