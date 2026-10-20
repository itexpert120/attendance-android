# Attendance Manager (Android)

A native Android app for a class teacher's daily work: attendance registers, fees, tests and reports, homework
diaries and students' records. It replaces the [attendance web app](https://github.com/itexpert120/attendance-pwa).

## Install

Download **[attendance.apk from the latest release](https://github.com/itexpert120/attendance-android/releases/latest/download/attendance.apk)**
and open it on your phone (allow "Install unknown apps" for your browser or file manager when asked).
Android 7.0 or newer is required.

## Moving from the web app

1. In the web app, open **Settings → Back up data** and save the backup (`.json` file).
2. Install the Android app and open **Settings → Import from web app backup**, then pick that file.

Students, classes, registers, fees, tests, subjects and homework all come across.

## Backups

The app backs up automatically every day to `Download/Attendance backups` and keeps the 5 most recent copies
(turn this off in Settings). **Settings → Back up** saves a `.zip` anywhere you choose; **Restore** reads it back.

## Building

Kotlin, Jetpack Compose (Material 3 Expressive), Room 3, WorkManager. Open in Android Studio or run
`./gradlew :app:assembleDebug`. Signed release builds read their keystore from `~/.gradle/gradle.properties`:

```properties
ATTENDANCE_RELEASE_STORE_FILE=C:/path/to/attendance-release.jks
ATTENDANCE_RELEASE_STORE_PASSWORD=...
ATTENDANCE_RELEASE_KEY_ALIAS=attendance
ATTENDANCE_RELEASE_KEY_PASSWORD=...
```
