# A-to-C relay evidence

**Result: passed.** Encrypted messages travelled between two phones with no direct link, through a third phone, in both directions, in the real EmergencyMesh app with no internet.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 04:13–04:19 IST |
| Phone A | vivo V2404, Android 16 |
| Phone B, the relay | Samsung SM-S721B, Android 16 |
| Phone C | OnePlus CPH2569, Android 15 |
| Setup | Airplane mode on, Bluetooth on, Wi-Fi off |
| Build | Private trial of `feature/prototype` at `bdf3155`, `member1/transport` at `4d7c238` and `member3/engine` at `1fafbd4`, with Tink restored |
| Unit tests on this build | 115 of 115 passed |

## Results

| Check | Result |
| --- | --- |
| No internet | All 3 phones printed `Network is unreachable` before and after |
| All phones connected | All 3 connected to each other 28 seconds after starting |
| Fingerprints | Each phone's view of the others matched before it was marked verified |
| Forced topology | Set in the app's debug screen: A saw only B, C saw only B, and B saw both |
| A to C | `relay71` arrived on C showing 1 hop, and A showed Delivered at 2.5 seconds |
| C to A | `relay82` arrived on A showing 1 hop, and C showed Delivered at 2.5 seconds |
| Topology after sending | Unchanged: A 1 connection, B 2, C 1 |
| Crashes | 0 crash log lines for the app on all 3 phones |

## The relay couldn't read the messages

I copied the database from each phone after the test and searched it for the message text.

| Phone | `relay71` readable | `relay82` readable | Message rows for them |
| --- | --- | --- | --- |
| B, the relay | No | No | 0 |
| C, an endpoint | Yes | Yes | 1 each |

The search covered B's database file and its write-ahead log, so the text wasn't stored in the clear anywhere in them. C reading its own messages shows the same search works.

## Known issue

Each phone held 48 queued packets after 2 messages. Acknowledgements are kept and retried, which Member 3 is fixing, and it needs a re-test because it affects traffic and battery.

## Not covered

- The Sent and Relayed states, because polling only caught Delivered
- SOS, the group code and priority order on real phones
- Delivery after reconnecting, and after a full app restart
- Tampered packets on real phones
- Range, because the phones were side by side and the A-C link was blocked in software
- Battery use
- This exact build isn't on GitHub yet
