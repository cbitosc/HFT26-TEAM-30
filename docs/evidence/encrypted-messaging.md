# Encrypted messaging evidence

**Result: passed.** Two real phones exchanged encrypted messages in both directions in the real EmergencyMesh app, with no internet, and each sender saw Delivered from a real acknowledgement.

**This was one hop.** Relay from A to C through B still has to be proven on 3 phones.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 04:05–04:08 IST |
| Phones | vivo V2404, Android 16, and Samsung SM-S721B, Android 16 |
| Setup | Airplane mode on, Bluetooth on, Wi-Fi off |
| Build | Private trial of `feature/prototype` at `bdf3155`, `member1/transport` at `4d7c238` and `member3/engine` at `1fafbd4`, with Tink restored |
| Security | Tink, with keys in the Android Keystore |
| Unit tests on this build | 115 of 115 passed |

## Results

| Check | Result |
| --- | --- |
| No internet | Both phones printed `Network is unreachable` before and after |
| Database upgrade | Installed over existing data with no crash, keeping contacts and keys |
| Connection | 13 seconds after both phones started discovery |
| Fingerprints | Each phone's view of the other matched before it was marked verified |
| vivo to Samsung | `hello91` arrived, and the sender showed Delivered at 2.6 seconds |
| Samsung to vivo | `hello92` arrived, and the sender showed Delivered at 2.4 seconds |
| Crashes | 0 crash log lines for the app on either phone |

## Not covered

- Relay through a middle phone
- The Sent and Relayed states, because the status polling only caught Delivered
- SOS and group code on real phones
- Priority order
- Proof on real phones that a relay can't read a message
- Delivery after reconnecting
- This exact build isn't on GitHub yet, because it needs Member 2 to merge the same pieces
