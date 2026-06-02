# FYT head-unit findings — how this hardware actually works

Hard-won, on-device reverse-engineering notes for the **WDFL-Car FYT head unit** (bought on
AliExpress) that FytBt targets. This is the "why" behind the code — especially the parts that took
a lot of trial and error to figure out, because almost none of it is documented anywhere.

> Everything here was determined empirically over wireless `adb` on the actual unit, by reading
> stock-app logcat, decompiling the vendor APKs (`jadx`), and watching what changed when we poked
> things. Where a claim was verified on-device, it says so.

---

## 0. The device

| | |
|---|---|
| Vendor UI brand | **FYT** (Feiyiteng); SYU stock apps (`com.syu.*`) |
| SoC | UNISOC **UMS9620** / platform marketing name **UIS7870SC**, FYT platform **6318** |
| Board / build | `uis7870sc_2h10`, fingerprint `UNISOC/uis7870sc_2h10_nosec/...:13/TP1A.220624.014/...`, built 2026-01-26 |
| OS | Android **13** (API 33), AOSP, **non-rooted**, `secure_boot.state=0` (`_nosec` build) |
| Screen | Portrait **768 × 1024** ("Tesla-style" vertical), finger-operated |
| Debugging | **Wireless adb only** — no usable USB-data port. The adb port **rolls on every reboot** (`adb connect 192.168.158.192:<port>`). `adb input tap` works. |

The Android Bluetooth/media/telephony stacks are essentially stock AOSP and *work*. What's broken
is the vendor's **`com.syu.bt`** UI (can't set device name, can't pair, shows nothing connected).
FytBt sidesteps the broken UI by driving the working stack directly — no root, runs alongside the
stock apps, can't uninstall them.

---

## 1. The single most important concept: the MCU audio-source model

This unit has a **microcontroller (MCU)** sitting underneath Android that owns the actual audio
routing to the speakers. At any moment **exactly one source is "active"**, identified by an integer
the SYU framework calls **`APP_ID`**. Whatever has the active `APP_ID` is what you hear; everything
else is muted at the hardware mux.

**The crucial, non-obvious part:** switching sources is **NOT** done through Android audio focus.

- The **FM/AM radio** and the unit's **own players** change the source by talking to the MCU
  directly (vendor IPC). They do **not** request Android `AUDIOFOCUS_GAIN`, so an Android app
  watching audio focus is *blind* to them.
- Conversely, holding/losing Android audio focus does **not** move the MCU source.

This one fact invalidated three earlier auto-pause designs (see §6). The only signal that reflects
**every** source — radio, AUX, the unit's Spotify, the Bluetooth phone — is the MCU's `APP_ID`.

### APP_ID values (from `com.syu.ipc.data.FinalMain`)

| APP_ID | Source | APP_ID | Source |
|---:|---|---:|---|
| 0 | NULL (none) | 8 | AUDIO_PLAYER (on-unit) |
| 1 | RADIO | 9 | VIDEO_PLAYER |
| 2 | BTPHONE (HFP call) | 10 | THIRD_PLAYER (e.g. **Spotify-on-unit**) |
| **3** | **BTAV (Bluetooth A2DP — the phone)** | 11 | CAR_RADIO |
| 4 | DVD | 12 | CAR_BTPHONE |
| 5 | AUX (e.g. `com.syu.av`) | 13 | CAR_USB |
| 6 | TV | 14 | DVR |
| 7 | IPOD | -1 | LAST |

`APP_ID == 3` means **Bluetooth (the phone) is the active source**. Anything else means something
else took over.

> **Niche detail:** `APP_ID` reflects which *input is selected*, not whether it's playing. It stays
> `3` while Bluetooth is the chosen source even if the phone is paused/stopped. It only changes when
> a **different** source grabs the MCU. (This is why launching the radio's *UI* alone doesn't change
> it — the radio has to actually become the active source.)

---

## 2. Reading the MCU source live — the SYU vendor IPC

To react to source changes we need to read `APP_ID` in real time. There's no public API; we reverse
-engineered the vendor binder framework `com.syu.ipc.*` from the panel-button app
**`com.fyt.screenbutton`** (which lights up the active-source button, so it must read `APP_ID`).

Implemented in [`app/.../media/SyuLink.kt`](app/src/main/kotlin/com/fytbt/media/SyuLink.kt).

### The recipe (raw AIDL over `Binder.transact`, no vendor SDK)

1. **Bind** the toolkit service:
   `Intent("com.syu.ms.toolkit").setPackage("com.syu.ms")` → binds the exported
   `com.syu.ms/app.ToolkitService`, which returns an `IRemoteToolkit`.
2. **Get the MAIN module:** `IRemoteToolkit.getRemoteModule(MODULE_MAIN = 0)` → returns an
   `IRemoteModule` binder. (Binder transaction code **1**.)
3. **Register a callback:** `IRemoteModule.register(callback, U_APP_ID = 0, enable = 1)` where
   `callback` is our own `Binder` posing as an `IModuleCallback`. (Transaction code **3**.) `enable=1`
   makes it push the current value immediately *and* on every change.
4. **Receive updates:** the MCU calls back into our binder's `onTransact` with code **1**
   (`IModuleCallback.update`). We read it as `(int updateCode, int[] ints, float[] flts, String[]
   strs)`; when `updateCode == U_APP_ID`, **`ints[0]` is the current `APP_ID`**.

### Constants that matter

```
descriptors:  "com.syu.ipc.IRemoteToolkit" / ".IRemoteModule" / ".IModuleCallback"
IRemoteToolkit.getRemoteModule = txn 1     IRemoteModule.cmd     = txn 1
IModuleCallback.update         = txn 1     IRemoteModule.get     = txn 2
INTERFACE_TRANSACTION          = 1598968902 IRemoteModule.register= txn 3
                                            IRemoteModule.unregister = txn 4

MODULE codes (FinalMainServer): MAIN=0, RADIO=1, BT=2, DVD=3, SOUND=4
MAIN module update index:  C_APP_ID / U_APP_ID = 0    (the active-source field)
```

### Intricacies learned doing this

- **`getRemoteModule` uses transact flag `0`** (it returns a binder, so it's a normal two-way call
  and we read `reply.readStrongBinder()`); **`register` uses flag `1`**, matching how screenbutton
  calls it.
- **Binder transactions block** — they must run off the main thread. `SyuLink` does the bind-time
  registration on a worker `Thread`. The incoming `update` callbacks arrive on a binder thread; we
  just publish to a `StateFlow<Int?>`.
- The whole thing is wrapped in `runCatching` and degrades to "never emits" if the stock app is
  missing or the firmware differs — the app then simply doesn't auto-pause rather than crashing.

### Verified on-device (logcat tag `FytBt`)

```
SyuLink bind -> true
SyuLink registered for MAIN/APP_ID updates
MCU APP_ID -> 10          # Spotify-on-unit was the active source at launch
MCU APP_ID -> 3           # after we switched to Bluetooth
```

`C_APP_ID` is a *status* field (read-only from our side). We never write it; the radio/players set
it when they grab the source. (Switching the source *to* Bluetooth is done a different way — §5.)

---

## 3. The auto-pause / resume mechanism (what the user actually wanted)

Goal: **when you switch to the radio (or any other source), pause the phone — and the unit's own
music/Spotify — automatically; when you switch back, resume.** And never fight a manual pause.

Implemented in
[`app/.../media/SourceCoordinatorService.kt`](app/src/main/kotlin/com/fytbt/media/SourceCoordinatorService.kt).

### Design

- A **foreground service** owns a `SyuLink` and watches `APP_ID`. On every change it reconciles
  playback so that **only the active source is playing**:
  - `APP_ID == 3` (BTAV) → the phone is the source. **Resume** it *iff we paused it*.
  - `APP_ID ∈ {8,9,10}` → an on-unit player is the source; that session keeps playing.
  - anything else (radio/AUX/call/none) → no media session is the source.
  - **Every other media session that is still PLAYING gets paused** (it's muted by the MCU but would
    otherwise keep advancing), and remembered so it can be resumed when its source returns.
- Control goes through `MediaSessionManager.getActiveSessions(...)`: the
  `com.android.bluetooth` AVRCP-controller session is the phone; any other non-self session is a
  local player (e.g. `com.spotify.music` installed on the unit).

### Resuming Bluetooth must re-route through the STOCK path, not a bare play()

The subtle one. After the coordinator pauses the phone for another source, just calling `play()` on
the BT session when the source returns leaves it **silent** — the phone shows "playing" (and the MCU
source is back to APP_ID 3) but **no audio comes out for ~10 s**, until a manual pause/play toggle on
the stereo kicks it. Two things are going on:

1. **Routing needs the stock BtMusic component to re-grab focus and re-assert the sink.** That's what
   a manual toggle does, and it's the `widgetPlayPause` lever (§5). A bare AVRCP `play()` from us
   doesn't trigger it. (First wrong guess: have *our* app grab `AUDIOFOCUS_GAIN` and `play()` — that
   put focus on `com.fytbt`, not stock BtMusic, and stayed silent. The working state has **`com.syu.bt`
   holding focus**, not us.)
2. **A focus-holding local player blocks the sink.** A local app that uses audio focus (e.g.
   **Symphony**, `io.github.zyrouge.symphony`, `USAGE_UNKNOWN`) keeps **holding** focus after we pause
   it, suppressing the sink output until evicted. Seen in `dumpsys audio`:
   ```
   09:28:31  requestAudioFocus from io.github.zyrouge.symphony   # local music grabs focus
      … ~13 s of silence …
   09:28:44  requestAudioFocus from com.fytbt                    # something finally requests focus
   09:28:44  abandonAudioFocus from io.github.zyrouge.symphony   # Symphony evicted → audio returns
   ```

**Fix:** on a BT resume, [`SourceCoordinatorService`] does what a manual toggle does — grab focus
(evicts the stale local holder) **then fire the `widgetPlayPause` lever** ([`SyuBridge`]) and assert
`play()` a beat later. We never *react* to focus changes — the A2DP sink toggles focus during normal
playback, so reacting would kill our own audio (§6). Radio/Spotify never showed this (radio bypasses
focus; Spotify releases it promptly) — only a focus-holding local player like Symphony exposes it.

**Verified objectively** with a mic pointed at the unit's output (`ffmpeg -af volumedetect`,
max_volume): BT playing ≈ **−18 dB**, BT paused ≈ **−43 dB**. After auto-resume: **−17.9 dB**; after
claim-on-open: **−21.4 dB** — i.e. real audio, not the silent floor.

### Claiming Bluetooth on app-open (predictable, like the stock app)

Opening the app **claims Bluetooth as the active source** — it fires the same `widgetPlayPause` lever,
which kills the radio and (via the coordinator pausing non-source sessions) pauses on-unit players
like Symphony, then plays the phone. It only does this when BT **isn't already** the active source
(`SourceCoordinatorService.currentAppId != 3`), so simply re-opening the app while already on a
*paused* BT phone won't force playback. The UI's claim and the coordinator's resulting auto-resume
both fire the lever, so [`SyuBridge`] **self-debounces** (1.5 s) to guarantee a single toggle —
verified: the second call logs `switchSourceToBluetooth: debounced (46ms since last)`.

### The golden rule (learned through painful regressions)

**We only ever resume a source we ourselves auto-paused.** If a session wasn't playing when the
source switched away, it isn't in our paused-set, so it is *never* auto-resumed. This is what keeps
a deliberate manual pause from being overridden. Earlier designs that force-played on resume, or
reacted to every playback callback, caused: "as soon as I go home our app pauses… played for a
second then paused again." All gone now.

### Why a *foreground* service (not the Activity)

Auto-pause has to work **while our UI is off-screen** (you're looking at the radio, not us). An
Activity-owned monitor dies when Android tears the stopped Activity down under memory pressure. A
foreground service is the only no-root way to stay resident. Cost: a low-importance ongoing
notification ("Coordinating audio sources"). Started from `MainActivity.onCreate`, `START_STICKY`.

### Verified on-device (the whole point)

Setup: phone playing over Bluetooth (`APP_ID 3`). Switch source away to `com.syu.av` (AUX, `APP_ID
5`), then back to Bluetooth:

```
MCU APP_ID -> 5
source -> appId=5: pause phone (Bluetooth)     # ✅ phone paused when source left BT
MCU APP_ID -> 3
source -> Bluetooth: resume phone              # ✅ phone resumed when source returned
```

Golden-rule check — phone **manually** paused first, then the same `3→5→3` churn:

```
MCU APP_ID -> 5
MCU APP_ID -> 3
# (no pause/resume lines at all — a manual pause is left alone) ✅
```

Survival check — **finish the Activity**, process + foreground service stay alive, repeat the round
trip → pause/resume **still fire** with no UI present. ✅

> The Bluetooth-phone path is fully validated on-device. The on-unit-player path (Spotify-on-unit
> etc.) uses the identical `getActiveSessions` + `transportControls` mechanism; it couldn't be
> exercised headlessly only because Spotify-on-unit wouldn't start playing from `adb` media keys.

---

## 4. How to drive a source switch from `adb` (test harness)

Triggering real source changes for testing is fiddly, because most sources only grab the MCU when
they *actually play*:

- **Launching the radio UI does nothing** to `APP_ID` — and its launch component is
  `com.syu.carradio/.ActivityLauncher`, **not** `.MainActivity` (a wrong guess silently no-ops).
- **`adb shell monkey -p com.syu.av 1`** (the stock AV/AUX app) **does** flip `APP_ID → 5` reliably
  — this is the handiest "switch away from Bluetooth" lever for testing.
- **`adb shell cmd media_session dispatch play|pause`** dispatches to the highest-priority session
  (usually the Bluetooth one when it's playing) — good for getting the phone "playing" first.
- Switch the source **to Bluetooth** with the `widgetPlayPause` service (§5).

Inspect state: `adb shell dumpsys media_session | grep -E 'package=|state=PlaybackState'`
(`state=3` PLAYING, `state=2` PAUSED, `state=1` STOPPED). Watch ours: `adb logcat FytBt:V '*:S'`.

---

## 5. Getting Bluetooth audio to actually come out (the "silent audio" saga)

For a long time, phone audio over A2DP was **silent until you opened the stock Bluetooth app** — even
though the phone was connected and the AVRCP session was live. This took many wrong turns.

- The unit is the **A2DP _sink_**; the BT stack even grabs/releases Android audio focus by itself.
  But the **MCU source stayed on radio/local-player**, so the sink stream was muted at the hardware
  mux. *Audio focus was a red herring.*
- The real lever is a **stock-app service** that makes the MCU switch the source to Bluetooth:
  `startService(com.syu.bt/.broadcast.BtavPlayPauseService, action="com.syu.bt.byav.widgetPlayPause")`.
  This makes the stock BtMusic component request the source, and the MCU logs
  `Qin: UI Change AppId to 3`, `volBt=36`, local player muted → **sound**.
- **Dead end:** `com.syu.bt.byav.force` → `MyService` is *received but does not switch the source*
  on its own. An earlier "it worked!" was misattributed — it only worked because the stock app had
  just been opened. (We kept `byav.force` in `SyuBridge` alongside `widgetPlayPause`; the latter is
  the one that matters.)
- **Gotcha:** `widgetPlayPause` is a play/**pause toggle**, so it flips playback as a side effect of
  switching the source. After firing it, assert AVRCP `play` ~700 ms later. Firing it twice quickly
  cancels out (we debounce ~1.5 s).

Diagnosing this meant watching the vendor **`sound`/`Qin`** MCU log tags (they print `audioType`,
`volBt`, `mutePlayer`, `appid`) while toggling things — that's where `appid:3` first showed up and
pointed us at the whole MCU-source model.

---

## 6. Auto-pause approaches that DIDN'T work (and why)

A record of the dead-ends so nobody re-treads them:

1. **Pause on `AUDIOFOCUS_LOSS`.** Wrong: this is an A2DP *sink*. The BT stack's own
   `A2dpSinkStreamHandler` grabs/releases focus as part of *normal playback*, so a LOSS usually
   means "the sink just started," not "another source took over." Reacting by pausing killed the
   audio we'd just started. **The focus listener is now a deliberate no-op.**
2. **Force-play on resume.** Fought manual pauses ("played for a second then paused again").
   Removed. → golden rule (§3).
3. **React to every `PlaybackState` callback.** A playing app emits a position-update callback
   ~1×/sec; reacting to each re-paused the phone every second. Fixed by tracking only
   not-playing→playing *transitions*.
4. **`AudioPlaybackCallback` + `content==MUSIC` + reflected `getPlayerState`.** Missed apps that use
   `content==UNKNOWN` (e.g. the Symphony player).
5. **Competing-MediaSession monitoring (v2).** Register a `MediaController.Callback` on every active
   session that isn't us/Bluetooth; pause the phone when one starts. Catches anything *with a
   MediaSession* (NavRadio `com.navimods.radio_free`, Spotify, Symphony) — but **NOT the stock FM
   radio `com.syu.carradio`**, which has no MediaSession (it's MCU/analog). That blind spot is
   exactly why we went to the MCU `APP_ID` (§2/§3), which sees *everything*. v2 is fully superseded.

---

## 7. Album art over Bluetooth — phone- AND app-dependent

Art arrives over **AVRCP cover art (BIP)** as a **`content://` URI from `AvrcpCoverArtProvider`**,
not an inline bitmap — load it via `ContentResolver`.

- **Sends art:** NewPipe on a stock Pixel.
- **Doesn't:** **Spotify** (confirmed `mCoverArtHandle=null` *and* no track duration — Spotify is
  stingy with AVRCP metadata), NewPipe on GrapheneOS, and **iOS** (text metadata only, never the
  image — an Apple limitation).
- **Fix — online fallback:** when the phone sends no BT art, look it up by title+artist via Apple's
  **iTunes Search API** (free, no key, 600×600). Verified for Spotify. A token-overlap matcher
  (weighted title 0.65 / artist 0.35) rejects weak matches.
  ([`OnlineArt.kt`](app/src/main/kotlin/com/fytbt/media/OnlineArt.kt))
- **Disk-cached** ([`ArtCache.kt`](app/src/main/kotlin/com/fytbt/media/ArtCache.kt),
  `filesDir/artcache_v2`, JPEG keyed by hash of `artist|title`): cache is checked **first** (works
  offline), network only on a miss. A track fetched once on WiFi shows art offline forever after —
  important because the unit is usually offline while driving.
- **Gap:** brand-new tracks while fully offline get no art. The only fully-offline answer is a
  companion phone app pushing the phone's MediaSession art over a BT RFCOMM socket (not built).
- Colors for the UI accent come from `androidx.palette` over the art; the player background is the
  art itself, zoomed + blurred (`graphicsLayer scale 1.3`, `Modifier.blur(45.dp)`, 45 % black scrim).

---

## 8. Bluetooth / MediaSession facts

- The unit is the **A2DP sink** (`A2dpSinkService`); the phone is the source. The BT *adapter name*
  (settable via `adapter.name`, what the discoverable banner shows) and pairing all work over the
  standard APIs even though the stock UI can't do them.
- **Bind the AVRCP session via `MediaBrowser` to
  `com.android.bluetooth/.avrcpcontroller.BluetoothMediaBrowserService`** — *not* `getActiveSessions`.
  When the phone pauses, the unit's own Spotify session **floats up** in `getActiveSessions`, so
  picking "the first active session" for the *display* would wrongly show on-unit Spotify. (For
  *coordination* we still use `getActiveSessions`, but we filter by package, so ordering is moot.)
- **AVRCP seek is not honored** — the source advertises `ACTION_SEEK_TO` but dragging snapped back.
  The playhead is therefore **display-only**.
- Steering-wheel media keys already work through the system MediaSession/AVRCP layer — no app
  handling needed.
- Other exported `com.syu.bt` bits: `BtavNextService` / `BtavPrevService` (UI-less transport),
  `ActBtAvStart` (action `com.syu.btav`, opens the stock UI — avoid).

---

## 9. Contacts & recent calls (PBAP)

Contacts/recents over Bluetooth come via **PBAP**, and the killer detail:

> The FYT PBAP client only syncs into the standard Android providers after a **fresh re-pair with
> "Contact sharing" enabled.** Toggling sharing on an already-paired device did nothing; re-pairing
> populated `content://call_log/calls` (136 rows) and `content://com.android.contacts/data/phones`
> (32).

So [`PhoneData.kt`](app/src/main/kotlin/com/fytbt/phone/PhoneData.kt) reads `CallLog.Calls` +
`ContactsContract.CommonDataKinds.Phone` directly. Recents are **grouped** (consecutive same-number
→ one row with a count) and **name-resolved against contacts** (PBAP call-log rows have empty
`CACHED_NAME`, so we match by the last 10 digits). Reads up to 500 raw, groups, resolves, takes 60.

Dialing routes to the stock app: `dial()` → `ACTION_CALL` (if `CALL_PHONE` granted) else
`ACTION_DIAL`, `setPackage("com.syu.bt")` → its `PhoneActivity` over HFP.

---

## 10. The microphone wedge (out of scope, documented for sanity)

**A Bluetooth HFP call kills the unit's built-in microphone globally until reboot.** Not just the
call uplink — *all* local capture reads digital silence (−91 dB) afterward, proven with a recorder.
It's a vendor UNISOC/SPRD audio-DSP (AGDSP) capture wedge below the userspace HAL; on hangup the
route looks healthy but the ADC/AGDSP capture chain stays stuck. **No no-root recovery exists** —
every software lever (re-open AudioRecord, BT off/on, HAL restart, framework restart, DSP-reset
props) fails; **only a reboot** restores it (verified causal).

- **Practical workaround (phone-side):** on the *phone's* Bluetooth settings for the unit, turn off
  **"Phone audio"/"Call audio"**, keep **"Media audio"** on. Calls stay on the phone (HFP never
  engages the unit codec, so the wedge can't fire); music keeps streaming over A2DP.
- FytBt **cannot** fix or avoid this itself: disabling the unit's HFP profile or dropping the call
  needs `BLUETOOTH_PRIVILEGED` (system/signature), which a sideloaded app can't hold.
- The real fix is **root** (no bootloader unlock needed on this unit — fastboot boot-image patch);
  a call-end hook could cold-reload the AGDSP. See the project memory note for the full teardown.

---

## 11. Quick reference — diagnostic commands

```bash
# connect (port rolls every reboot — read it from Settings → Wireless debugging)
adb connect 192.168.158.192:<port>

# build / install / launch
cd ~/FytBt && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fytbt/.MainActivity

# watch our coordination + IPC
adb logcat FytBt:V '*:S'

# is the foreground coordinator alive?
adb shell dumpsys activity services com.fytbt | grep -E 'SourceCoordinator|isForeground'

# current media sessions + playback states (3=playing,2=paused,1=stopped)
adb shell dumpsys media_session | grep -E 'package=|state=PlaybackState'

# drive a source switch for testing
adb shell monkey -p com.syu.av 1            # → APP_ID 5 (AUX): "switch away from BT"
adb shell am startservice -n com.syu.bt/com.syu.broadcast.BtavPlayPauseService \
  -a com.syu.bt.byav.widgetPlayPause        # → APP_ID 3 (BTAV): "switch to BT"

# vendor MCU audio log (shows appid/volBt/mutePlayer)
adb logcat -s sound Sound Qin
```

Decompiled stock-app reference lives at `/tmp/fyt_re/sb_src` (jadx of `com.fyt.screenbutton`); the
IPC truth is in `sources/com/syu/ipc/{IRemoteToolkit,IRemoteModule,IModuleCallback,FinalMainServer}.java`
and `sources/com/syu/ipc/data/FinalMain.java`.
