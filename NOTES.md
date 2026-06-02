# Dev notes

## Auto-pause Bluetooth when another source takes over — ON HOLD (2026-06-01)

Goal: when the user switches to another audio source (radio, local music app), pause the
phone's Bluetooth playback so the two don't fight; when they come back to our app, Bluetooth
takes over again.

### Where it stands
- Implemented in `NowPlayingController` via **competing MediaSession monitoring**: we watch every
  other app's `MediaSession` and pause the phone when one transitions to PLAYING, but only while
  our app is **backgrounded** (foreground = user wants BT) and only on a genuine
  not-playing→playing **transition** (a playing app emits a position callback ~1/sec; reacting to
  each was re-pausing the phone every second).
- **Works** for apps that expose a MediaSession: Spotify-on-unit, Symphony (intermittently).
- **Does NOT work** for:
  - The **FYT stock radio** (`com.syu.carradio`) — no MediaSession, audio runs through the MCU /
    analog path, invisible to Android.
  - **NavRadio** (`com.navimods.radio_free`) in testing — it has a session and we *did* detect it
    once, but opening it didn't reliably pause BT (probably it was already playing, so no fresh
    transition while backgrounded).

### The real fix (not yet done)
The only fully reliable signal is the **MCU's active-source value (`appid`)**, which the `sound`
service logs (`Qin: UI Change AppId to N`). Reading it requires SYU's private IPC
(`com.syu.ms` framework — `ShareProvider` is permission-locked; would need the IPC client classes
and the source command id). If we read `appid`, we could pause BT exactly when the source leaves
Bluetooth and resume when it returns — clean, covers radio too. That's a dedicated
reverse-engineering effort, deferred.

### What DOES work (shipped)
- Audio routes to Bluetooth via the SYU `widgetPlayPause` source switch (`SyuBridge`).
- Opening the app while the phone is playing takes over the audio source from the radio.
- The app never auto-plays; a manual pause sticks.
