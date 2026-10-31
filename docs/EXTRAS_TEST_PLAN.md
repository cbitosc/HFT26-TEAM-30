# Extras test plan

**Every extra has now run on real phones:** see [extras-phone-test.md](evidence/extras-phone-test.md). They all worked, but keep-alive depends on the phone brand, and some tests weren't run. `feature/prototype` at `125bd0e` stays the fallback until M2 merges.

`builds/emergencymesh-extras.apk` holds every extra, and it installs over the existing app without losing data. It's built from `member1/extras`.

**For the demo:** use the Samsung as the locked phone, and keep the OnePlus screen on. The vivo needs **Allow background relay** and its high background power setting, which are set on the team's phone.

## Order

These times are estimates, and they add up to about 1 hour 35 minutes. With 60 minutes before the 11 AM freeze, do rows 1–5 first.

| # | Extra | Time | Steps | If it fails |
| --- | --- | --- | --- | --- |
| 1 | Background keep-alive | 15 min | [KEEP_ALIVE_TEST.md](KEEP_ALIVE_TEST.md) | **Stop here.** Most other extras need it, so keep the demo on the handover build and tell me what happened |
| 2 | Rescue Call-Out | 10 min | [CALL_OUT_TEST.md](CALL_OUT_TEST.md) | Leave it out of the demo |
| 3 | Last Breath | 10 min | [LAST_BREATH_TEST.md](LAST_BREATH_TEST.md) | Leave it out of the demo |
| 4 | AI dashboard with a real key | 10 min | [README.md](../src/dashboard/README.md) | The page still works and shows **AI unavailable** |
| 5 | SOS upload and laptop node | 15 min | [DASHBOARD_UPLOAD_TEST.md](DASHBOARD_UPLOAD_TEST.md), [LAPTOP_NODE_TEST.md](LAPTOP_NODE_TEST.md) | Show the dashboard with sample data, which is marked as sample data |
| 6 | Talk-Back | 15 min | [TALK_BACK_TEST.md](TALK_BACK_TEST.md) | Leave it out of the demo. The box is off by default, so it can't affect anything else |
| 7 | No-app SOS | 20 min | [NO_APP_SOS_TEST.md](NO_APP_SOS_TEST.md) | Leave it out of the demo. Listening is off by default |

Tests 6 and 7 are the most likely to fail, because they depend on what each phone supports. Rows 2, 3 and 5 reuse the tested mesh, so they're the safer bets.

## What each failure risks

| Extra | Can it hurt the mesh if it's broken? |
| --- | --- |
| Keep-alive | **Yes.** It changes how the radio and engine start and stop, so re-run the relay test on this build |
| Call-Out, Last Breath | Very unlikely. They only act during an active SOS |
| Upload, laptop node, No-app SOS, Talk-Back | Only if someone turns them on |

## Merging

Tell M2 which rows passed. Only merge what passed on real phones.

- **Everything passed:** merge `member1/extras`.
- **Some failed:** merge the passing branches below instead, in this order. Later branches include the ones they're built on, and the conflicts are small because they mostly add lines in the same places.

| Order | Branch | Built on |
| --- | --- | --- |
| 1 | `member1/keep-alive` | `feature/prototype` |
| 2 | `member1/call-out` | keep-alive |
| 3 | `member1/talk-back` | call-out |
| 4 | `member1/last-breath` | keep-alive |
| 5 | `member1/no-app-sos` | keep-alive |
| 6 | `member1/ai-dashboard` | `feature/prototype` |
| 7 | `member1/dashboard-upload` | keep-alive |
| 8 | `member1/laptop-node` | dashboard-upload and ai-dashboard |

After merging, run the relay test again on the final build before recording the video.

## What to say in the demo

Only claim what passed, and say what was simulated. The evidence so far is:

- **Tests:** 230 Android unit tests and 70 dashboard tests pass.
- **Phones:** the results and what wasn't tested are in the evidence file above.
- **USB, not Wi-Fi:** the upload and laptop node reached the laptop over USB, so don't claim real Wi-Fi yet.
- **Keep-alive:** say it depends on the phone. Don't say it works on every Android phone.
- **Fake AI:** the dashboard has only run against a fake model, so don't quote any accuracy figure until `evaluate.py` has run with a real key.
- **Battery:** keep-alive keeps the CPU awake while the mesh is on, so measure it again on this build.
