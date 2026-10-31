# Final build evidence

**Result: passed on three real phones, offline.** This closes the status gap from the earlier run. Battery is still unmeasured, and background operation isn't built.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 05:45–05:55 IST |
| Build | `feature/prototype` at `d39be7a` plus `member3/engine` at `89815c0`, merged as `d0f2b0f`, with Tink |
| Unit tests | 119 of 119 passed on debug and release |
| Phones | vivo V2404, Samsung SM-S721B, OnePlus CPH2569 |
| Setup | Airplane mode on, Bluetooth on, Wi-Fi off, A-C link blocked |

The Samsung's first install failed, so it ran the previous engine build for the first relay and SOS checks. Its retry succeeded at 05:53. At 05:59, with all 3 phones on this build, `handover1` went from A to C through B, showing 1 hop, and was Delivered at 2.6 seconds. B's database held none of its text.

## Results

| Check | Result |
| --- | --- |
| No internet | All 3 phones printed `Network is unreachable` before and after |
| Database upgrade | Installed over existing data on the vivo and OnePlus, with no crash |
| A to C through B | `merged71` Delivered at 2.6 seconds, showing 1 hop |
| C to A through B | `merged82` Delivered at 2.6 seconds, showing 1 hop |
| Relay privacy | B's database and write-ahead log held neither message |
| SOS scanning | Balanced before SOS, Continuous during SOS, Balanced after **End SOS scanning** |
| Contact key warning | `Contact keys changed` appeared when a known device sent keys that differ from those stored |
| Crashes | 0 crash log lines for the app on all 3 phones |

## Status timeline

Tapping the status on `merged71` showed all 4 stages, with times recorded on the sender's phone.

| Stage | Time |
| --- | --- |
| Pending | 05:48:53 |
| Sent | 05:48:54 |
| Relayed | 05:48:54 |
| Delivered | 05:48:54 |

Messages sent before the timeline existed show `No recorded time` for missing stages.

## How the key warning was tested

The stored key for phone C was changed on a copy of phone A's database, so C's real card looked like a changed identity. A's original database was restored afterwards, with the correct key length confirmed.

## Known limits

- **Queue size:** the phones still held over 200 queued packets, mostly from before the final queue fix.
- **Background:** the app stops its radio when it leaves the screen, so relay phones must stay unlocked with the app open.
- **Battery and range:** not measured yet.
