# No-app SOS phone test

Someone who never installed EmergencyMesh renames their Bluetooth to start with `SOS`. A phone running the mesh hears that name without pairing and adds an unverified SOS. **It passed between 2 phones, with one standing in for a phone without the app:** see [extras-phone-test.md](evidence/extras-phone-test.md). A phone that has never had the app hasn't been tried.

This branch is built on `member1/keep-alive`, so merge that first.

## Setup

1. Install the build on the bridge phone, B, and on phone A.
2. Tap **Start discovery** on both.
3. On B, open **Nearby** and tap **Listen for SOS names**.
4. Use a teammate's own phone that has never had the app. Never uninstall the app from a demo phone to test this.

## Tests

| # | Steps | Pass |
| --- | --- | --- |
| 1 | On the fourth phone, set the Bluetooth name to `SOS 3 TRAPPED FLOOR2`, then stay on its Bluetooth screen | Within about 2 minutes, B and A show an alert starting **No-app SOS, not verified** |
| 2 | Wait 5 more minutes | No repeat alert appears |
| 3 | Change the name to `SOS WATER RISING` | A new alert appears at the next scan |
| 4 | Change the name to `Pixel 8` | Nothing new appears |
| 5 | Check the link between A and B during tests 1–3 | They stay connected, and messages still deliver |
| 6 | On B, tap **Reply by name**, keep the text, and allow Android's request | `HELP COMING 10 MIN` appears in the fourth phone's Bluetooth list |
| 7 | Wait 2 minutes, then check B's Bluetooth name in its settings | B has its own name back |
| 8 | Send a message from A to B during test 6 | Record whether it delivers |
| 9 | Tap **Stop listening** on B | No new alerts appear, and the mesh keeps working |

Try tests 1 and 6 with every kind of phone you can borrow, including an iPhone, and record each result. Note how far apart the phones were.

## If test 5 or 8 fails

Scanning or replying is hurting the mesh, so leave this out of the demo or use it on a phone that isn't relaying.

## Known limits

- **Not verified:** anyone nearby can set any Bluetooth name, so a person must check each alert before acting on it. A limit of 10 alerts an hour per listening phone stops a prank from flooding the mesh.
- **Discoverability:** a phone is usually only visible while its Bluetooth screen is open, and some phones limit how long that lasts.
- **Speed:** a scan runs every 90 seconds and lasts about 12 seconds, so an alert can take a couple of minutes.
- **Battery and speed:** scanning uses more battery and can slow Bluetooth links, so it's off by default.
- **Reply:** Nearby also uses the Bluetooth name while advertising, so a reply may disturb the mesh on that phone. Android asks the person to allow it each time.
- **Location:** the alert carries the listening phone's location, not the caller's. The caller is within Bluetooth range of it.
- **Nobody knows about this yet:** a person has to be told to rename their Bluetooth, for example by a volunteer or a public notice.

## After it passes

1. Save the results in `docs/evidence/no-app-sos.md`.
2. Add it to `README.md`, `docs/DEMO_SCRIPT.md` and `docs/MENTOR_PREP.md`.
3. Ask M2 to merge the branch.
