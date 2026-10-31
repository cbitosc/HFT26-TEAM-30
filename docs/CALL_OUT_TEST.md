# Rescue Call-Out phone test

A rescuer taps one button, and a phone in SOS mode rings and flashes its torch so people can find it, with no cell network. **None of this has run on a phone yet.** The relay logic and timing passed unit tests, but the sound, torch and vibration have never been tried.

This branch is built on `member1/keep-alive`, so merge that first.

## Setup

1. Install the build on all 3 phones, with the A-B-C topology from the Debug tab.
2. Tap **Start discovery** on each phone.
3. A is the rescuer. C is the phone being found.

## Tests

| # | Steps | Pass |
| --- | --- | --- |
| 1 | Send an SOS from C, lock it, and hide it | A shows the SOS |
| 2 | On A, open **SOS** and tap **Ring this phone** on C's alert | C rings and flashes for 10 seconds, and A shows that C is ringing |
| 3 | Check B during test 2 | B stayed silent, because A didn't call it |
| 4 | Turn on silent mode on C, then repeat test 2 | C still rings |
| 5 | Ring C, then tap **Stop ringing** in its notification | Sound, torch and vibration stop at once |
| 6 | Check C's alarm volume afterwards | It's back to the level it was before |
| 7 | Tap **End SOS scanning** on C, then ring it again | C stays silent, and A shows no confirmation |
| 8 | Send an SOS from B and C, then tap **Ring SOS phones one at a time** on A | They ring in turn, 12 seconds apart, without overlapping |
| 9 | Use a phone covered by a case or a bag | Note how far away you can still hear it |

Record the time from the tap to the first sound. Check for crashes after each run:

```bash
adb -s <serial> logcat -d -b crash
```

## Safety rules

- **A phone only rings during its own SOS.** Ending SOS scanning turns this off, because ringing gives away where a person is.
- **Only a verified contact can ring a phone.** The signature on every call is checked at each hop.
- **A call expires quickly.** Relays drop it after 2 minutes, and a phone ignores any call over 5 minutes old, so it can't go off much later.
- **A ring stops by itself** after 10 seconds, and the person holding the phone can stop it sooner.

## Known limits

- **The mesh must be on.** A called phone needs keep-alive running, so it can't ring after its app has been killed.
- **Clocks:** a phone whose clock is over 5 minutes out ignores calls.
- **Volume:** this borrows the alarm volume for 10 seconds. If the app dies during a ring, the alarm volume stays at maximum.
- **Do Not Disturb:** a setting that blocks alarms may mute the ring. Vibration and the torch should still work.
- **Torch:** it won't flash if another app is using the camera.
- **Repeats:** a rescuer can keep ringing a phone during its SOS, which uses its battery.

## After it passes

1. Save the results in `docs/evidence/call-out.md`.
2. Add it to `README.md`, `docs/DEMO_SCRIPT.md` and `docs/MENTOR_PREP.md`.
3. Ask M2 to merge the branch.
