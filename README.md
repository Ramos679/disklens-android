# DiskLens for Android

An independent, native Android storage analyzer inspired by the storage treemap and file-size exploration in [WinDirStat](https://github.com/windirstat/windirstat). All app code and artwork in this project are original; this is not an official WinDirStat port and does not contain WinDirStat source code.

DiskLens was developed with AI assistance. Build results and device-test coverage are documented in [VALIDATION.txt](VALIDATION.txt); automated authorship-detector scores are not evidence that software is correct or secure.

## Install

Download the [signed APK](downloads/DiskLens-1.0.0.apk?raw=true) for direct installation.

1. Copy `DiskLens-1.0.0.apk` to an Android phone running **Android 8.0 or later**.
2. Open the APK with the phone's file manager. If prompted, allow that app to install unknown apps, then install DiskLens.
3. Open DiskLens and tap **Scan device storage**. Select a storage volume and enable the system's **Allow access to manage all files** switch for DiskLens, then return to the app.
4. Alternatively, use **Choose a folder** and grant access only to a folder you select.

This APK is a signed release build for direct installation. It is not published on Google Play. It contains no native libraries and works on ARM, ARM64, x86, and x86_64 Android devices.

## Features

- Interactive, area-proportional treemap with folder drill-down and parent navigation.
- Internal shared storage and mounted removable volumes (on Android 11+), plus Android's system folder picker.
- Largest files, folder browser, name search, and paginated results.
- Category totals and the 100 largest filename extensions.
- Duplicate detection: group by byte size, then read and compare complete SHA-256 hashes. Empty files are excluded; no automatic deletion.
- Open a file in another app using a temporary read-only URI grant.
- Individually confirmed permanent file deletion. Folder deletion and batch deletion are intentionally unavailable.
- CSV export through Android's save picker, with proper quoting and spreadsheet formula neutralization.
- Background scanning and duplicate comparison with progress, cancellation, and retention across screen rotation.
- Offline app: no INTERNET permission, ads, analytics, accounts, uploads, or third-party runtime libraries.

## Scope and limitations

Android does not permit ordinary apps to inspect other apps' private storage or system files. All-files access does not remove these restrictions. The app reports scanned logical file sizes; these will not equal the device's total used space. Filesystem overhead, private app data, system files, and inaccessible paths are outside the scanned totals. The free-space indicator comes separately from Android's filesystem statistics.

Folder-picker access is subject to the selected provider's restrictions. Android 11+ restricts selecting storage roots, the Download root, and Android/data or Android/obb with the folder picker. Use the device-storage scan for broader shared-storage access. A cloud-backed provider may fetch file contents during duplicate comparison; DiskLens itself has no network permission.

Unreadable/skipped locations, unknown sizes, stopped scans, and scan limits are shown explicitly. Symbolic links are skipped to avoid cycles and double counting. A scan is limited to 250,000 entries and 128 levels; the map groups small items when a folder has more than 180 entries, and opens the complete folder list when that group is selected. Duplicate results display the 50 largest groups. Scans are kept in memory rather than persisted. Keep the app open during long scans; Android can terminate a background app process. Deletion is permanent, with no recycle bin. Rescan after external file changes.

Sizes use powers of 1024, displayed as KB, MB, and GB. Category classification uses filename extensions, not content inspection. Duplicate identity is based on complete SHA-256 hashes, and reflects files as read during the check.

## Build

Requirements: JDK 17, Android SDK platform 36, SDK Build Tools 35.0.0. Android Gradle Plugin 8.11.1 and Gradle 8.13 are pinned. Android Studio can open the project directory directly.

Set `ANDROID_HOME` to your SDK directory, or create a local (untracked) `local.properties` containing `sdk.dir=...`.

On Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
.\gradlew.bat coreTest
.\gradlew.bat assembleDebugAndroidTest
```

On macOS/Linux, use `./gradlew` for the same tasks. The debug APK is in `app/build/outputs/apk/debug/`.

For a signed release, create your own keystore with alias `disklens`, set `DISKLENS_STORE_FILE` to its absolute path and `DISKLENS_STORE_PASSWORD` to its password, then run `assembleRelease`. Without those variables the release output is unsigned. Keep release signing material private; it is excluded from the source archive.

## Device tests

With an emulator or phone connected:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.disklens.test/app.disklens.StorageInstrumentation
```

The instrumentation creates and removes only its own app-cache fixture. It tests recursive file scans, aggregation, ordering, full-file duplicate detection (including a same-size nonduplicate), CSV escaping, category classification, treemap bounds/proportions/non-overlap, and cancellation.

`coreTest` runs the randomized treemap checks without a device. On a fresh emulator, `python tools/run-device-tests.py` installs the debug APKs and verifies the instrumentation's success result. The script fails on Android test failures even if `adb` itself exits successfully. It does not uninstall existing apps; use a dedicated test emulator to avoid signing conflicts with a release installation.

GitHub Actions builds the app, runs Android lint and `coreTest`, and runs the device checks on an Android 15 emulator. CI artifacts are debug builds; the signed APK is distributed separately through GitHub Releases. Java sources use Google Java Format 1.22.0.

## Project structure

- `MainActivity.java`: native UI, permissions, folder picker, file actions, CSV destination picker.
- `ScanEngine.java`: direct filesystem and document-provider scans, hashing, CSV generation.
- `ScanSession.java`: worker executor, progress, and Activity recreation retention.
- `StorageNode.java`: file hierarchy, type classification, size formatting.
- `TreemapLayout.java` / `TreemapView.java`: balanced area partitioning and accessible companion list.
- `ReadOnlyFileProvider.java`: narrowly granted read-only file opening.

## References

- [WinDirStat inspiration](https://github.com/windirstat/windirstat)
- [Android all-files access and restrictions](https://developer.android.com/training/data-storage/manage-all-files)
- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)

Original app code is licensed under MIT. Android and Gradle build tools retain their respective licenses.
