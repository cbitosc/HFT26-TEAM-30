# Talk-Back phone test

A rescuer types a question. The hidden phone reads it aloud, listens once, and sends back what it heard as text, so the person never has to touch the phone. **None of this has run on a phone yet.** The messaging and consent rules passed unit tests, but speech and the microphone have never been tried, so expect some of it to fail.

This branch is built on `member1/call-out`, which is built on `member1/keep-alive`. Merge those first.

## Setup

1. Install the build on all 3 phones, with the A-B-C topology from the Debug tab.
2. Tap **Start discovery** on each phone.
3. A is the rescuer. C is the phone being helped.
4. On C, open **SOS**, tick **Let rescuers talk to this phone during my SOS**, and allow the microphone.
5. Stay on Airplane mode. If a test fails, record whether the phone has an offline speech pack, because this feature can't use the internet in the field.

## Tests

| # | Steps | Pass |
| --- | --- | --- |
| 1 | Send an SOS from C | A shows it |
| 2 | With C unlocked, tap **Ask a question** on A and send `Are you hurt?` | C reads it aloud, then listens, and A's chat shows **Spoken answer** with what you said |
| 3 | Repeat with C locked | The same as test 2. This is the hardest case, because Android limits the microphone off screen |
| 4 | Set C to silent, then repeat | C still reads it aloud |
| 5 | Stay quiet after the question | A's chat says it **heard no answer** |
| 6 | Untick the box on C, then ask again | C reads it aloud, the microphone stays off, and A's chat says it **isn't allowed to listen** |
| 7 | Tap **End SOS scanning** on C, then ask again | C stays silent, and the question only arrives as a chat message |
| 8 | Check B during test 2 | B stayed silent, and its database holds neither the question nor the answer |
| 9 | Ask `क्या आपको चोट लगी है?`, then `మీకు గాయం అయిందా?` | Record whether each language is spoken, and whether the answer is understood |

Record the microphone indicator's behavior, and the time from sending to the answer. Check for crashes after each run:

```bash
adb -s <serial> logcat -d -b crash
```

## If it fails

| What happened | What it means |
| --- | --- |
| Test 2 says it **heard no answer** every time | The phone probably has no offline recognizer. Check its speech settings for an offline language pack |
| Test 2 passes but test 3 doesn't | Android blocked the microphone off screen. Demo it with C unlocked, and say so |
| Hindi or Telugu is silent, or read with the wrong accent | That voice isn't installed on the phone |
| Keep-alive stops working after ticking the box | Untick it, because this also changes how the keep-alive service starts |

## Safety rules

- **The owner opts in.** Sending an SOS doesn't turn on listening. The box does, and it's off by default.
- **It only listens during an active SOS,** once, right after asking aloud, for 12 seconds at most.
- **Only a verified contact can ask.** The signature is checked at each hop.
- **Relays can't read it.** Questions and answers are end-to-end encrypted like any direct message, and only text crosses the mesh.
- **A late question is never spoken.** After 5 minutes it stays a plain chat message.

## Known limits

- **Offline recognition isn't on every phone.** This uses Android's own recognizer instead of adding 36 MB to the app, so results depend on the phone and on which language packs it has.
- **Background microphone:** Android may refuse it when the phone is locked, and it can't be regained after Android restarts the service.
- **Accuracy:** a person under rubble may be quiet, or hard to hear, so the rescuer must treat the text as a rough transcript.
- **Battery:** speaking and listening use the alarm volume and the microphone for up to about 40 seconds per question.
- **Volume:** if the app dies while speaking, the alarm volume stays at maximum.

## After it passes

1. Save the results in `docs/evidence/talk-back.md`.
2. Add it to `README.md`, `docs/DEMO_SCRIPT.md` and `docs/MENTOR_PREP.md`.
3. Ask M2 to merge the branch.
