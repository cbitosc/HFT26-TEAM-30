# Laptop mesh node phone test

A laptop can't run Nearby Connections, so phones reach it over Wi-Fi. It stores each packet and passes it on, which lets phones exchange messages when they're out of radio range of each other. **It passed on real phones with radios blocked, connected to the laptop over USB:** see [extras-phone-test.md](evidence/extras-phone-test.md). Real Wi-Fi between devices hasn't been tried.

This branch is built on `member1/dashboard-upload`, which is built on `member1/keep-alive`. Merge those first. The laptop service is also on this branch.

## What has been proven

| Check | Result |
| --- | --- |
| 2 phones with no radio link exchange a message through the real service | Pass, and the sender's status reached Delivered |
| Real Tink encryption | The laptop's database held 3 packets and no message text |
| An SOS | The laptop couldn't read it either |
| The laptop changes a packet | The phone rejected it, whether the change was in the ciphertext or the signature |
| The laptop forges a packet from a real signature | The phone rejected it |
| The laptop goes away | Messages wait on the phone, and the link returns by itself |
| The sender leaves first | A later phone still gets what the laptop held |

## Setup

1. Connect the laptop and phones A and C to the same private hotspot or Wi-Fi.
2. On the laptop, set `DASHBOARD_UPLOAD_TOKEN` in `src/dashboard/.env`, then run:

```bash
python3 dashboard.py --lan
```

3. Allow Python through the laptop's firewall if asked. Note the **Phones upload to** address it prints.
4. On A and C, open **Debug**, then **Set up dashboard upload**. Enter that address and the token, tick **Also carry mesh messages through this laptop**, then tap **Turn on**.
5. On A and C, open **Debug**, then **Configure allowed devices**, and block all radio peers. The laptop isn't a radio link, so it stays allowed, and it's now the only path between them.
6. Tap **Start discovery** on both.

## Tests

| # | Steps | Pass |
| --- | --- | --- |
| 1 | Wait 20 seconds | **Laptop node** appears in Nearby on both phones, and the dashboard shows 2 phones |
| 2 | Send a message from A to C | It reaches **Delivered** within about 15 seconds |
| 3 | Send an SOS from A | C shows it |
| 4 | Stop the dashboard service, send from A, then start the service again with the same command | The message arrives within about a minute |
| 5 | Lock both phones, then send from A | It arrives, which also tests keep-alive |
| 6 | Run the command below on the laptop | It prints `0`, meaning no message text is on the laptop |
| 7 | Clear the filter and untick the laptop option on both phones | Normal radio relay still works |

Check that your own message text isn't in the laptop's database, replacing the words with text you sent:

```bash
grep -c "words you sent" src/dashboard/dashboard.db
```

## Known limits

- **It needs a shared network.** Phones must reach the laptop over Wi-Fi. Android may send traffic through mobile data if the Wi-Fi has no internet, so turn mobile data off if a phone can't reach it.
- **Speed:** phones check every 5 seconds, so each trip through the laptop can take several seconds.
- **It can see who is talking.** Sender and recipient IDs aren't encrypted, and packet times and sizes are visible.
- **It can drop packets.** It can't read or change them, but it could fail to pass them on. Phones keep using every other path.
- **Plain HTTP:** the token can be read by anyone on the same network, so they could also hand packets to the laptop. Use a private hotspot.
- **Older builds:** they have no laptop option, so every phone that uses it needs this build.
- **It isn't a Nearby peer.** A phone with Wi-Fi off can't reach it.
- **Storage:** it holds up to 5,000 packets. When full, phones keep their own copies and try again later.

## After it passes

1. Save the results in `docs/evidence/laptop-node.md`.
2. Add it to `README.md`, `docs/DEMO_SCRIPT.md` and `docs/MENTOR_PREP.md`.
3. Ask M2 to merge the branch.
