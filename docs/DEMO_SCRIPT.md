# Demo script

Use this for the 2–3 minute video and the live demo. Only show steps that passed on the final APK. Remove any step that hasn't, and say so in the limitations.

## Before recording

- [ ] The same APK and commit are on phones A, B and C
- [ ] Phones are charged, unlocked, with a 10-minute screen timeout
- [ ] Notifications are silenced
- [ ] All 3 phones are enrolled with each other and fingerprints are verified
- [ ] The group code is set on all 3 phones
- [ ] Forced topology is set: A-B and B-C, with A-C blocked
- [ ] Airplane mode is on, Bluetooth is on, and Wi-Fi is off
- [ ] `adb shell ping -c 1 -W 2 8.8.8.8` prints `Network is unreachable` on each phone
- [ ] A local backup of the video exists

Allow 20–30 seconds for phones to connect over Bluetooth.

## Script

| Time | Show | Say |
| --- | --- | --- |
| 0:00–0:20 | The three phones in airplane mode | When towers and internet fail, people still need to reach each other. These phones have no network at all. |
| 0:20–0:45 | Connected peers on each phone | Phones connect directly. A and C can't reach each other, so B has to carry their messages. |
| 0:45–1:15 | Send A to C, with the status changing | A's message goes through B, and shows Delivered only when C acknowledges it. |
| 1:15–1:35 | B's debug log | B forwards the packet, but can't read it, because only C holds the key. |
| 1:35–2:00 | Queue regular messages, then press SOS | SOS goes ahead of queued messages. Without location permission, it still sends, with no made-up coordinates. |
| 2:00–2:25 | Isolate a phone, restart it, reconnect | With no path, a message stays Pending. It survives a restart and delivers when a phone comes back in range. |
| 2:25–2:50 | Test matrix | This is a prototype. Here's what we proved, and what we didn't. |

## Limits to say out loud

- Delivery needs a chain of nearby phones running the app, immediately or later on.
- This doesn't replace official emergency services.
- The app must be open, because background delivery isn't built.
- Anyone with the group code can read SOS.
- Android only, with Google Play services.

## Likely judge questions

| Question | Answer to check before saying |
| --- | --- |
| How is this different from Bitchat or Bridgefy? | Use the priority rules, honest delivery states and evidence, and only claim what passed |
| What if nobody is nearby? | The message stays Pending, and nothing can deliver it until a phone comes in range |
| Does it work on iPhone? | No, this build is Android-only, and the statement doesn't require iPhone |
| Who can read an SOS? | Phones holding the group code |
| What range did you measure? | Measure this first, because no range test has been run |
| How much battery does it save? | Use the measured comparison, and say if the sample is small |
| Where is the AI? | The optional AI dashboard was cut so the core could be finished |
