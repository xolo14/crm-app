# Syncpedia CRM Android App

Kotlin dialler-style CRM client for **https://crm.syncpedia.in**.

## Open in Android Studio

1. Open Android Studio → **File → Open** → select this folder
2. Let Gradle sync (JitPack is required for MPAndroidChart)
3. Run on a physical device (call log / phone permissions need a real device)

## Package

- Application ID: `com.syncpedia.crm`
- Min SDK 26 / Target SDK 34
- BASE_URL: `https://crm.syncpedia.in/api/`

## Features

- Login → 5 tabs: Calls, Leads, Analysis, Contacts, More
- Auto-sync: `CallReceiver` schedules `SyncWorker` 2s after every call ends
- Dark theme (`#0D0D0D`) matching Diallogs-style UI
