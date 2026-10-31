# Three-phone topology evidence

**Result: the forced A-B-C radio layout passed.** The vivo and OnePlus each connected only to the Samsung, while their direct link stayed blocked.

**This doesn't prove message relay.** Each packet here travelled one hop. Gate G2 still needs Member 3's engine to carry a message from A to C through B.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 01:17–01:28 IST |
| Build | Debug APK, Mesh Test screen, commit `63a6395` |
| Phone A | vivo V2404, Android 16 |
| Phone B | Samsung SM-S721B, Android 16 |
| Phone C | OnePlus CPH2569, Android 15 |
| Setup | Airplane mode on, Bluetooth on, Wi-Fi off |
| Distance | Side by side, connected to the same laptop by USB |

## Results

| Check | Result |
| --- | --- |
| No internet | All 3 phones printed `Network is unreachable` before pairing, and before and after the link test |
| Pairing | Codes matched on both phones: `0902` for A-C and `4934` for B-C. A-B was already approved |
| Full connection | All 3 phones connected to each other by 01:18:38 |
| Forced layout | A showed only B, C showed only B, and B showed A and C |
| Blocked link | A-C was still down at 01:20:34, 01:21:27 and 01:26:56 |
| Blocked link logs | A logged `Allowlist blocked 57c12729` and `Rejected blocked device 57c12729`. C logged `Allowlist blocked 98b6bd67` |
| Send failures | 0 on all 3 phones |

## Traffic on the allowed links

| Direction | Replies | Median round trip |
| --- | --- | --- |
| A to B | 5 of 5 | 99 ms |
| B to A | 5 of 5 | 131 ms |
| B to C | 5 of 5 | 106 ms |
| C to B | 5 of 5 | 155 ms |

| Phone | Unique packets sent | Unique packets received |
| --- | --- | --- |
| A, vivo | 10 | 10 |
| B, Samsung | 20 | 20 |
| C, OnePlus | 10 | 10 |

## Screenshots

- [A: radio log showing the blocked link](three-phone-A-vivo.png)
- [B: connected to both A and C](three-phone-B-samsung.png)
- [C: connected only to B](three-phone-C-oneplus.png)

Screenshots are cropped to remove the status bar, and reduced to half size. In the radio logs, the newest entries are at the top.

## Not covered

- Message relay from A to C through B
- Delivery status through a relay
- Range, because the phones were side by side and A-C could reach each other directly
- Battery use
- Phones with Wi-Fi on but no network
- Security, because Mesh Test sends unsigned plaintext
- The real EmergencyMesh app, because these tests used the debug screen
