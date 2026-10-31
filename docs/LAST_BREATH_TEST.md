# Last Breath phone test

When a phone with an active SOS drops to 5% battery, it sends one final SOS. Nearby phones keep carrying it after that phone shuts down. **It hasn't run on a phone yet.** The decision logic passed unit tests, but the battery signal has never been tried.

This branch is built on `member1/keep-alive`, so merge that first.

## Setup

1. Install the build on all 3 phones, with the A-B-C topology from the Debug tab.
2. Tap **Start discovery** on each phone.
3. Connect phone C by USB, because these commands fake its battery level.

## Tests

| # | Steps | Pass |
| --- | --- | --- |
| 1 | Send an SOS from C | A and B show it |
| 2 | Run the 2 commands below on C | A and B show **Last message from this phone**, with C's earlier words |
| 3 | Tap **Stop** on A, then repeat tests 1 and 2 | B holds the final SOS |
| 4 | Switch C off, then tap **Start discovery** on A | A receives C's final SOS through B, with C still off |
| 5 | Set the level to 3, then to 2 | No second final SOS appears |
| 6 | Tap **End SOS scanning** on C, then set the level to 4 | Nothing is sent, because C isn't asking for help |

Fake the battery with these commands:

```bash
adb -s <serial> shell dumpsys battery unplug
```

```bash
adb -s <serial> shell dumpsys battery set level 4
```

Always reset the phone afterwards, or it keeps showing the fake level:

```bash
adb -s <serial> shell dumpsys battery reset
```

## Known limits

- **It needs a connection in time.** With no phone nearby, the final SOS waits in the queue and dies with the phone.
- **It needs the mesh running.** The app must still be alive at 5%, so a phone that kills background apps in battery saver may never send it.
- **Sudden shutdowns:** an old battery can die before it reports 5%.
- **Location:** it reuses the last location from this phone's own SOS, which may be out of date. It shows the time that location was recorded.
- **Not the best-charged neighbor:** it sends to every connected phone, because phones don't share their battery levels.

## After it passes

1. Save the results in `docs/evidence/last-breath.md`.
2. Add it to `README.md`, `docs/DEMO_SCRIPT.md` and `docs/MENTOR_PREP.md`.
3. Ask M2 to merge the branch.
