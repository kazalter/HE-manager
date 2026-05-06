# HE Manager Android App

This is a small native Android client for the HE Manager backend.

## What it does

- Connects to `http://YOUR_PC_LAN_IP:8010`
- Creates the first admin account or logs in
- Lists media from the local library
- Plays videos with Android `VideoView`
- Reads manga folders page by page
- Opens single images

## Build

Open this `android-app` folder in Android Studio, let it sync Gradle, then run the `app` configuration on a phone or emulator.

The current Codex environment does not have Java, Gradle, or Android SDK installed, so the project was scaffolded but not compiled here.

## Server

Start the backend service on the PC:

```bat
run_server.bat
```

Find the PC LAN IP, then enter it in the app as:

```text
http://192.168.x.x:8010
```
