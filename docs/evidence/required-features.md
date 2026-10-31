# Required features evidence

**Result: all tested requirements passed on three real phones, offline, in the real app, with one gap.** The Relayed state wasn't seen on screen, and battery wasn't measured in this run.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 04:49–05:07 IST |
| Phone A | vivo V2404, Android 16 |
| Phone B, the relay | Samsung SM-S721B, Android 16 |
| Phone C | OnePlus CPH2569, Android 15 |
| Setup | Airplane mode on, Bluetooth on, Wi-Fi off, A-C link blocked |
| Build | `integration/relay-proven` plus `member3/engine` at `6f5ca40`, with Tink |
| Unit tests on this build | 119 of 119 passed |

## Results

| Requirement | Test | Result |
| --- | --- | --- |
| 1. Offline | All 3 phones printed `Network is unreachable` before and after | Pass |
| 2. Peer-to-peer | All 3 phones connected 29 seconds after starting | Pass |
| 3. Relay | `final71` reached C through B, showing 1 hop, and Delivered at 2.5 seconds | Pass |
| 4. SOS | A's SOS reached B directly and C through B, showing 0 and 1 hops | Pass |
| 4. SOS | With location denied, the SOS sent and showed no coordinates | Pass |
| 4. SOS | With location allowed but no recent fix, the SOS still sent without coordinates | Pass |
| 5. Priority | SOS arrived before all 20 queued regular messages, which then arrived in order | Pass |
| 6. Status | Pending, Sent and Delivered appeared on screen | Pass |
| 6. Status | Relayed confirmations reached A in the radio log, but the state wasn't caught on screen | **Gap** |
| Security | B's database and write-ahead log held none of the message text | Pass |
| Security | A packet from a trusted phone without a valid signature was rejected | Pass |
| Security | SOS was readable on phones sharing the group code | Pass |
| 8. Recovery | Isolated message stayed Pending, survived an app restart, then delivered after reconnecting | Pass |
| 8. Recovery | That message arrived exactly once | Pass |
| Stability | 0 crash log lines on all 3 phones, and existing data survived the install | Pass |

## Queued delivery timeline

| Step | Sender status |
| --- | --- |
| B and C radios stopped | Pending |
| App force-stopped and reopened | Pending |
| B back in range | Sent |
| C back in range | Delivered |

## Message arrival order at C

The SOS arrived first, followed by `queue10` to `queue29` in order. Across the whole run, C stored 24 messages with 24 unique IDs, and 3 SOS alerts with 3 unique IDs, so there were no duplicates.

## Known issues

- **Relayed on screen:** Delivered follows too quickly to see Relayed. It needs a status timeline or a similar change.
- **Queue growth:** each phone held over 200 queued packets after about 30 messages. Retries are now bounded, but acknowledged rows remain until they expire.
- **Keep-alive:** the app stops its radio when it leaves the screen, so relay phones must stay unlocked with the app open.
- **Reconnect time:** relay delivery took about 40 seconds after a phone came back, because of discovery and retry timing.

## Not covered

- Battery measurement
- Range, because the A-C link was blocked in software with phones side by side
- A location fix appearing in an SOS, because no phone had a recent fix indoors
- Wrong group code on real phones, which is covered by a unit test
