# Keep-alive phone test

Use this to prove that the mesh keeps working with the screen off. **None of this has run on a phone yet.** Until it passes, the handover build stays the demo build, and the docs keep saying the app must stay open.

Don't merge `member1/keep-alive` until tests 1–6 pass.

## Setup

1. Install this build over the existing app on all 3 phones. It uses the same signing key, so contacts, messages and the group code stay in place:

```bash
adb -s <serial> install -r builds/emergencymesh-keepalive.apk
```

2. Keep airplane mode on, Bluetooth on and Wi-Fi off.
3. Keep the A-B-C topology from the Debug tab, so A and C can only reach each other through B.
4. Unplug the phones for tests 2–5 if you can. A phone on USB may behave differently from one in a pocket.

## Tests

| # | Steps | Pass |
| --- | --- | --- |
| 1 | Tap **Start discovery** on each phone and allow notifications | The notification **EmergencyMesh is running** appears, and Nearby says **Mesh on** |
| 2 | Lock B. Send A to C | Delivered, showing 1 hop. B's screen stayed off |
| 3 | Lock all 3 phones for 5 minutes, then unlock A and send to C | Delivered without tapping Start again |
| 4 | Lock C. Send an SOS from A | C alerts on the lock screen with **SOS from** and the sender |
| 5 | Lock C. Send a direct message from A | C alerts with **Message from** and the sender. The message text isn't shown |
| 6 | Tap **Stop** in the notification on B | The notification goes, Nearby says **Mesh off**, and A loses B |
| 7 | Rotate A with the mesh on | Peers stay connected and Start stays disabled |
| 8 | Remove the app from recent apps on B, then send A to C | Delivered if the phone keeps it running. Record which phones don't |
| 9 | Run `adb -s <serial> shell dumpsys activity services com.slackers.emergencymesh` on a running phone | Shows `MeshForegroundService` with `isForeground=true` |

Record the time to Delivered for tests 2 and 3. Check for crashes after each run:

```bash
adb -s <serial> logcat -d -b crash
```

## If a phone stops relaying when locked

Tap **Allow background relay** on the Nearby tab first. Then check these manufacturer settings, because Android doesn't control them:

| Phone | Setting |
| --- | --- |
| vivo | Battery, then allow high background power use for EmergencyMesh |
| OnePlus | Battery usage for the app, then allow background activity |
| Samsung | Battery, then Background usage limits, then add EmergencyMesh to never sleeping apps |

The exact menu names vary by Android version.

## Known limits

- **Battery:** the service holds the CPU awake while the mesh is on, because the discovery and retry timers stop when the CPU sleeps. Measure this cost before quoting any battery figure.
- **Long idle:** deep Doze hasn't been tested. The 5-minute lock in test 3 won't reach it.
- **Android 10 and 11:** Nearby scanning may stop in the background without background location. The team's phones run Android 15 and 16, so this is untested.
- **Pairing:** a new phone can't be approved while the screen is locked, so the notification asks the owner to open the app.
- **Restart:** Android may restart the service after killing the app. That can be refused unless **Allow background relay** was granted.

## After it passes

1. Save the results in `docs/evidence/keep-alive.md`.
2. Update the background notes in `README.md`, `docs/MENTOR_PREP.md`, `docs/DEMO_SCRIPT.md` and `docs/SLIDES.md`.
3. Ask M2 to merge the branch.
