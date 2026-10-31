# Dashboard upload phone test

Use this to prove that an SOS can travel from the offline mesh to the online dashboard. **It hasn't run on a phone yet.** The upload code has only been tested on a laptop, against the real dashboard service.

This branch is built on `member1/keep-alive`, so merge that first. The dashboard itself is on `member1/ai-dashboard`.

## Setup

1. Install `builds/emergencymesh-dashboard-upload.apk` on all 3 phones. It includes keep-alive, so test 1 below also needs the steps in `docs/KEEP_ALIVE_TEST.md`.
2. Connect the laptop and phone B to the same private hotspot or Wi-Fi. Phone B is the bridge, and A and C stay offline.
3. On the laptop, set `DASHBOARD_UPLOAD_TOKEN` in `src/dashboard/.env`, then run:

```bash
python3 dashboard.py --lan
```

4. Allow Python through the laptop's firewall if asked. Note the **Phones upload to** address it prints.
5. On phone B, open **Debug**, then **Set up dashboard upload**. Enter that address and the token, then tap **Turn on**.

## Tests

| # | Steps | Pass |
| --- | --- | --- |
| 1 | Send an SOS from A while B's screen is locked | It appears on the dashboard, showing its hops, within about a minute |
| 2 | Check Debug on B | Status says **On. 1 alert uploaded.** |
| 3 | Check A and C | Neither has any network connection |
| 4 | Stop the dashboard, send an SOS, then restart the dashboard | The status shows it can't reach the dashboard, and the alert appears within about 30 seconds of the restart |
| 5 | Enter a wrong token | The status says the token is wrong, and nothing appears on the dashboard |
| 6 | Send a direct message from A to C through B | It never appears on the dashboard |
| 7 | Turn the upload off, then send an SOS | It doesn't appear on the dashboard, and the mesh still delivers it |

## Known limits

- **Encryption:** plain `http://` isn't encrypted on the local network, so anyone on it can read uploads and the token. Use a private hotspot. Release builds refuse plain HTTP, so they need an `https://` address.
- **Trust:** the bridge phone decrypts each SOS with the group code, then sends its text and location to the dashboard. Only set this up on a phone you trust.
- **Routing:** Android may send traffic through mobile data if the Wi-Fi has no internet. If the phone can't reach the laptop, turn mobile data off on the bridge phone.
- **Repeats:** after the app restarts, the phone offers its alerts again. The dashboard ignores repeats by ID.
- **Older alerts:** they stay on the phone unless **Also upload older alerts** is ticked.

## After it passes

1. Save the results in `docs/evidence/dashboard-upload.md`.
2. Update the AI notes in `README.md`, `docs/MENTOR_PREP.md`, `docs/DEMO_SCRIPT.md` and `docs/TEST_MATRIX.md`.
3. Ask M2 to merge the branch.
