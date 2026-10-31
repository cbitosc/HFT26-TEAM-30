# G1 evidence: two-phone offline exchange

**Result: passed.** Two real phones exchanged packets in both directions with no internet or cellular connection.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 00:23–00:33 IST |
| Build | Debug APK, Mesh Test screen, commit `1a9fcb7` |
| Phone 1 | vivo V2404, Android 16 |
| Phone 2 | Samsung SM-S721B, Android 16 |
| Setup | Airplane mode on, Bluetooth on, Wi-Fi off |
| Distance | Side by side, connected to the same laptop by USB |

## Results

| Check | Result |
| --- | --- |
| No internet | Both phones printed `Network is unreachable` before and after the offline runs |
| Ping from vivo | 10 of 10 replied |
| Ping from Samsung | 10 of 10 replied |
| Text from vivo | `hello71` arrived on the Samsung |
| Text from Samsung | `hello82` arrived on the vivo |
| Median round trip | 86 ms on the vivo, 128 ms on the Samsung, over 11 samples each |
| Errors | No send failures or malformed packets in the collected screens |
| Pairing | Both phones showed code `7938` and connected after approval on both sides |
| Reconnect after airplane mode | Reconnected with no approval prompt |

The first discovery, pairing and restart test ran before airplane mode was on, so the reconnect time below is not an offline result:

| Check | Result |
| --- | --- |
| First discovery | Peers found about 14 seconds after starting the second phone |
| Stop and restart on the Samsung | Reconnected in 17.9 seconds with no approval prompt |

Each round-trip set includes one sample from the earlier online run.

## Final build recheck

The first results above used commit `1a9fcb7`. This recheck ran on the build with the connection fixes, between 00:46 and 00:50 IST, with the same phones and offline setup.

| Check | Result |
| --- | --- |
| No internet | Both phones printed `Network is unreachable` before and after |
| Connection | Connected 18 seconds after both phones started, with no error |
| Ping from vivo | 5 of 5 replied, median 117 ms |
| Ping from Samsung | 5 of 5 replied, median 99 ms |
| Text | `hello71` and `hello82` each arrived on the other phone |
| Errors | No send failures or malformed packets in the collected screens |

## Connection collisions

Phone testing showed both phones requesting a connection at the same moment, which failed with `STATUS_ENDPOINT_IO_ERROR` (8012) before recovering. Bluetooth-only connecting took about 20 seconds, which was longer than the 8-second fallback wait.

| Fallback wait | Runs | Result |
| --- | --- | --- |
| 8 seconds | 2 | Error 8012 in both runs, then recovered |
| 20 seconds | 1 | No error |
| 30 seconds, final build | 1 | No error |

The final build also ignores a late request failure once a handshake has started. These are small samples, so a collision may still happen, but the phones recovered each time it did.

## Packet IDs match across phones

The radio logs show the same packets from both sides. The vivo logged `Sent MESSAGE b60350ce`, and the Samsung logged `Received MESSAGE b60350ce`. The Samsung logged `Sent MESSAGE da864ca5`, and the vivo logged `Received MESSAGE da864ca5`.

- [vivo radio log](g1-vivo.png)
- [Samsung radio log](g1-samsung.png)

The screenshots were cropped to remove the status bar. A blank name shows beside the vivo's ID because that phone was started with an empty name, which has since been fixed.

## Not covered

- Three-phone relay and the forced A-B-C topology
- Range, or reconnecting after walking out of range
- Battery use
- Phones with Wi-Fi on but no network
- Which radio Nearby chose. With Wi-Fi off, it had to be Bluetooth or Bluetooth Low Energy
- Security, because Mesh Test sends unsigned plaintext
- The real EmergencyMesh app, because these tests used the debug screen
