# Mentor changes: phone test

**Result: the three logins, area alerts, the admin location screen and shortest routes all worked on real phones.** Area alerts crossed a phone outside the area, which relayed them without showing them. A locked Samsung inside an area was alerted in 2.1 seconds.

| Detail | Value |
| --- | --- |
| Date | 31 October 2026, 13:14–13:38 IST |
| Build | Logins and alerts on `fac5f28`. Routing and regression on `member1/mentor-fixes` at `8a0fe65` |
| Phones | A: vivo V2404. B: Samsung SM-S721B. C: OnePlus CPH2569 |
| Setup | A-B-C forced in the Debug tab, so A and C couldn't reach each other by radio. Phones were indoors, so test locations stood in for GPS |
| Unit tests | 249 run on debug and release, 0 failed, 2 skipped because they need a live service |
| Crashes | 0 crash log lines on all 3 phones |

## Limits of this run

- **Test locations, not GPS.** Debug builds can set a location, and this run used that. A release build ignores it. Real GPS boundaries weren't tested.
- **Short distances.** All 3 phones were on one desk and plugged in. A was about 370 m from C and 20 km from B only on paper.
- **Three phones.** Routing ran on 3 real phones and 5 simulated ones. Nothing here tests 1,000 phones.
- **Debug build.** The final APK is a debug build, so its test location setting stays available.
- **Old alerts.** A phone doesn't check an alert's age when it arrives, so a copied alert could raise a notification again after the phone forgets it at 6 hours.

## Three logins

| Check | Result |
| --- | --- |
| Wrong code | Refused, with "That code isn't right for Responder." |
| Responder code | Opened the Control tab with Warn an area |
| Admin code | Opened the Control tab with Where people need help |
| User | No code needed, and no Control tab |
| Log out | Control tab gone, and the mesh kept running |

The app holds only public keys, so no role code is in the app or this repository. Anyone who can read the app can still try to guess a code offline, so real use needs long random codes. Every responder shares one code, so a single person can't be removed.

## Area alerts

A 1 km alert sent from A, with B 20 km away and C about 370 m away:

| Check | Result |
| --- | --- |
| C, inside the area | Showed the alert and a notification 3.0 seconds after the tap, after 1 hop |
| B, outside the area | Showed nothing and stored nothing, but relayed it |
| Same alert aimed at B's area, with B locked | B got the notification in 2.1 seconds, and C stored nothing |

A phone that doesn't know its location shows the alert and says so, because a false warning costs less than a missed one. We saw this happen before B's test location was set. The app uses the phone's last known location, up to 6 hours old, and doesn't wait for a new GPS fix.

Forged alerts, alerts from phones with no role, and repeats are covered by unit tests only.

## Where the person is

An SOS from B with a location showed on the admin's phone as `19.6 km east of you, at 17.43990, 78.49830 ±10 m`. It works with no maps or internet.

An SOS only carries a location if the phone has a fix from the last 2 minutes. Without one, the admin sees "No location sent" and the hop count, which is what happened on B before its test location was set.

## Shortest routes

Run on `8a0fe65`, the build merged with M3's routing.

| Check | Result |
| --- | --- |
| Line A-B-C, both directions | Delivered at 1 hop through B |
| Triangle, with A and C in direct range | Both direct messages arrived at 0 hops, and B's database has no record of either |
| Direct link removed, back to the line | The next message went through B at 1 hop, with no restart |
| Area alert after the merge | C stored it and B didn't |

B's database did record the line message, so this check can see a relay when one happens. We didn't run the triangle on a build without routing.

Unit tests cover the rest. With 5 simulated phones, the second message took only the 2-hop path, and it took the longer path after a phone dropped out. If the next phone stays connected but can't deliver, the longer path opens after 8 seconds. The first version of routing kept that path held until the route expired at 5 minutes. The new test fails without the fix.

Only direct messages use routes. SOS and area alerts still go to everyone, on purpose. Routes are learned from verified traffic and stay in memory, so the first message to a new phone still spreads through all paths.
