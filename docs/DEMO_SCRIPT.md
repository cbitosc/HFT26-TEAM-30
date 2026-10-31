# Demo script

Use this for the 2–3 minute video and live demo. Only use the final APK and show features that passed on that exact build. The Samsung is the safest locked relay; keep the OnePlus screen on.

## Before recording

- [ ] The same final APK and commit are on phones A, B and C
- [ ] Contacts are enrolled, fingerprints verified and the same group code is set
- [ ] Forced topology is A-B-C, with A-C blocked
- [ ] Airplane mode is on, Bluetooth is on and Wi-Fi is off for the core offline demo
- [ ] Each phone prints `Network is unreachable`
- [ ] Samsung is B if the locked-background relay is shown
- [ ] OnePlus stays unlocked because its OS froze the app in testing
- [ ] The status timeline and SOS group are ready
- [ ] Dashboard and laptop features are off during the core offline demo
- [ ] A local backup of the video exists

Allow up to 30 seconds for Bluetooth-only connections.

## Core 2–3 minute script

| Time | Show | Say |
| --- | --- | --- |
| 0:00–0:20 | Three phones in airplane mode and the failed network check | “These phones have no internet or cellular path.” |
| 0:20–0:40 | A-B-C connections, with A-C blocked | “A and C cannot connect directly, so B is their only path.” |
| 0:40–1:05 | Send A to C; open its timeline after delivery | “The message crosses B encrypted. The four times come from real acknowledgements.” |
| 1:05–1:20 | C’s message showing 1 hop, then B’s database or debug evidence | “C received it through one relay, while B stored no readable message text.” |
| 1:20–1:45 | Send SOS and show Continuous scanning | “SOS goes first and switches discovery to Continuous while active.” |
| 1:45–2:05 | Tap **End SOS scanning** and show Balanced | “Ending the active SOS scan returns to the lower-power balanced mode.” |
| 2:05–2:25 | Lock Samsung B and send through it, if the final APK recheck passed | “Background relay works on this Samsung. Android brand battery controls still matter.” |
| 2:25–2:45 | Test matrix and limitations | “These are the exact real-phone results and the limits we did not hide.” |

## Optional extras clip

Use only what survives the final APK recheck and available video time:

- Rescue Call-Out: the SOS phone rings and flashes, then restores volume and torch state.
- Talk-Back: a rescuer asks a question and the SOS phone speaks, listens and replies; describe recognition as rough.
- Last Breath: say the battery level was simulated with Android debugging tools.
- No-app SOS: say the Bluetooth-name flow was simulated using a phone that still had the app installed.
- Dashboard upload: say the tested phone-to-laptop path used USB, not real Wi-Fi.
- Laptop node: say it carried encrypted packets over the USB-backed path and could not read tested message text.
- AI dashboard: show **AI off** or sample data only. No real model or accuracy was tested.

## Limits to say aloud

- Delivery requires a nearby path now or later.
- EmergencyMesh does not replace official emergency services.
- Background relay depends on the Android brand and battery settings; OnePlus failed the locked test.
- Anyone holding the SOS group code can read group SOS alerts.
- The current app is Android-only and requires Google Play services.
- Battery use and range have no claimed number until M4’s evidence is committed.
- Laptop and dashboard phone connectivity was tested through USB; real Wi-Fi remains untested.
- The dashboard’s real AI model and accuracy were not tested.

## Likely judge questions

| Question | Accurate answer |
| --- | --- |
| What if nobody is nearby? | The message remains Pending until a useful phone comes in range. |
| Does it work with the screen locked? | It passed on Samsung, passed on vivo after battery setup, and failed on the tested OnePlus. |
| Where is the AI? | The optional dashboard and safe fallback are built and tested against a fake server. No real model key or accuracy result was available. |
| Does the laptop extend the mesh? | It did in the USB-backed phone test. Real phone-to-laptop Wi-Fi is not yet proven. |
| What range did you measure? | Use M4’s committed result; otherwise say it remains unmeasured. |
| How much battery does it save? | No battery comparison has been completed, so we do not quote a number. |
| Can B read A’s message to C? | No in the tested case; B’s database and WAL contained none of the test text. |
| Is it fully secure? | It uses Tink and Android Keystore and passed the listed tests, but has no independent security review or forward secrecy. |

## If the final build misbehaves

1. Wait 30 seconds and check each phone’s connection count.
2. Keep OnePlus unlocked; verify Samsung’s foreground notification if it is the locked relay.
3. Check the debug topology and group code.
4. Use the backup recording and evidence files if the live radio remains unstable.
5. Do not make a new code change after the 1:40 PM freeze.
