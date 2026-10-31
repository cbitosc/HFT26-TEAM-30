# EmergencyMesh AI dashboard

An optional online page for volunteers. It classifies alerts, pulls key facts out of long messages, suggests a review order, and translates between English, Hindi and Telugu.

The mesh never depends on this. Phones work the same with it switched off.

## Status

| Check | Result |
| --- | --- |
| Tests | 54 pass, using a fake Featherless server |
| Page | Checked in a browser with the fake server, including AI failure, phone width and light mode |
| Real Featherless model | **Not tested yet.** No key was available |
| Accuracy | **Not measured yet.** Run `evaluate.py` once you have a key |

## Run it

You need Python 3.9 or newer, and nothing else installed.

```bash
cd src/dashboard
cp .env.example .env
python3 dashboard.py
```

Put your key in `.env`, then open <http://127.0.0.1:8080>. Without a key, the page still works: it sorts alerts by the sender's priority and shows **AI off**. Git ignores `.env`, so never put the key anywhere else.

Tap **Load sample alerts** for 30 practice messages. Every one is marked **Sample data**.

If Featherless refuses the default model, your plan may not include it. Change `FEATHERLESS_MODEL` in `.env`. These cost 1 concurrency unit and are hosted there: `Qwen/Qwen2.5-14B-Instruct`, `mistralai/Mistral-Nemo-Instruct-2407`, `google/gemma-4-E4B-it`.

## What each part does

| Feature | Output | Without AI |
| --- | --- | --- |
| Classification | Category, urgency, confidence and a one-line reason | Uses the sender's priority |
| Key facts | People, place, injuries, needs, deadline and personal note, for messages over 200 characters | Shows the original text |
| Review order | Ranked list with reasons | Sorts by priority, then age |
| Translation | English, Hindi or Telugu beside the original | Shows the original text |

## Safety rules

- **The original message is always shown,** and is never changed.
- **Every AI result is labelled** as a suggestion, with its reason.
- **A person can override any result,** and that choice always wins.
- **AI can't bury an SOS.** An SOS never ranks below high unless a person sets it lower.
- **Low confidence moves an alert up,** by one level.
- **Message text is treated as data.** It reaches the model as a JSON value, so instructions inside a message aren't part of the prompt. A model can still be fooled, which is why the SOS rule exists.
- **Failures are visible.** A failed or invalid reply shows **AI unavailable** and keeps the message.
- **Direct messages aren't uploaded.** Only SOS alerts reach the dashboard, because direct messages are end-to-end encrypted.
- **Nothing about a request is logged.**

## Measure accuracy

This sends all 30 sample messages to Featherless, so it uses your plan. Run it with 2 models to compare them:

```bash
python3 evaluate.py --models sarvamai/sarvam-m,Qwen/Qwen2.5-14B-Instruct
```

A missed critical alert matters more than overall accuracy, so report that number first. Only quote results from this command. The samples were written by this team, so they aren't an independent test.

## Accept uploads from a phone

Set `DASHBOARD_UPLOAD_TOKEN` in `.env` to a long random value, then:

```bash
python3 dashboard.py --lan
```

Phones can only upload alerts in this mode. The page and everything else stay limited to this laptop. A phone sends alerts like this, with the token in an `X-Upload-Token` header:

```bash
curl -H 'Content-Type: application/json' -H 'X-Upload-Token: <token>' \
  -d '{"alerts":[{"id":"sos-1","text":"Two people trapped at Gate 3","priority":"P0","hops":2}]}' \
  http://<laptop address>:8080/api/alerts
```

**Plain HTTP isn't encrypted.** Anyone on the same network can read uploads and the token, so use a private hotspot for the demo.

## Test it

```bash
python3 -m unittest discover -s tests
```

`tests/fake_featherless.py` only matches keywords. Use it to check the page without a key, and never for accuracy numbers or in the demo.
