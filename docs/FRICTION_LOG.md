# Friction log

Problems we actually hit while building CookAlong, with what we expected, what happened, and a suggestion.

| # | Task | Severity |
|---|---|---|
| 1 | Enable Claude on Amazon Bedrock from an India-billed AWS account | High |
| 2 | Install the Strands SDK into a Lambda zip from Windows | Medium |
| 3 | Link a Fire TV app to a user's Alexa account | Medium |
| 4 | Read steps aloud with the Fire Stick's text-to-speech | Medium |
| 5 | Keep a TV shelf from sliding when focus moves | Low |

## 1. Claude on Bedrock fails on an India (AISPL) account (High)
- **Task:** call Claude Sonnet through Bedrock from a Lambda.
- **Steps:** submitted the use-case form, accepted the model agreement (`aws bedrock create-foundation-model-agreement`),
  added a second payment card, then tried again as the root user.
- **Expected:** the agreement activates, or a clear message says why not.
- **Actual:** the agreement sat in `PENDING` for about 10 minutes, then went to `ERROR INVALID_PAYMENT_INSTRUMENT`, and
  an "agreement expired" email followed. Model calls failed with "IAM user or service role is not authorized to perform
  aws-marketplace:Subscribe", even as root. Community posts suggest AWS Marketplace doesn't accept stored cards for
  AISPL accounts; we found no AWS page saying so.
- **Workaround:** switched everything to Amazon Nova, which AWS bills directly.
- **Suggestion:** say in the console and in the error that Anthropic models need a Marketplace-capable payment method,
  document the AISPL limit, and don't blame IAM when the cause is billing.

## 2. Strands SDK won't install for Lambda from Windows (Medium)
- **Task:** build a Linux Lambda zip with `pip install --platform manylinux2014_x86_64 --target ... strands-agents`.
- **Expected:** a Linux package set.
- **Actual:** `ResolutionImpossible`. `mcp` depends on `pywin32` for `sys_platform == "win32"`, and pip evaluates that
  marker on the host even when targeting Linux, so no solution exists.
- **Workaround:** resolve once, drop the Windows-only package, then install the rest with `--no-deps` from a pinned list
  (`backend/requirements-agent.txt`).
- **Suggestion:** document a Lambda packaging recipe for Windows, or make the Windows-only dependency an extra.

## 3. A Fire TV app can't learn which Alexa account to talk to (Medium)
- **Task:** let "Alexa, tell Cook Along next step" control the app on the user's own TV automatically.
- **Expected:** an API to ask the Fire TV for the Alexa user or device id, or a skill-to-app channel for third-party apps.
- **Actual:** the skill only sees an anonymous per-skill user id, and the app can't read it. A custom skill can't reach an
  installed app without Amazon Device Messaging or your own backend.
- **Workaround:** a 6-digit pairing code shown on the TV and said once to Alexa, plus a polling command queue in DynamoDB.
- **Suggestion:** a "link this app to the signed-in Alexa account" API for Fire TV apps.

## 4. The Fire Stick's default text-to-speech engine has no voices (Medium)
- **Task:** read recipe steps aloud with `TextToSpeech`.
- **Expected:** the default engine speaks English.
- **Actual:** `com.ivona.tts.oem` bound fine, but `setLanguage` returned `LANG_NOT_SUPPORTED` for every English locale
  (log: "error occur on reading voice configuration"), so speech silently never worked.
- **Workaround:** try every installed engine until one has an English voice (`com.svox.pico` did).
- **Suggestion:** ship voice data with the default engine, or document that apps must probe the installed engines.

## 5. Compose's default scroll-into-view moves TV shelves on every key press (Low)
- **Task:** keep the first card in place when focus moves to the second.
- **Actual:** the default `BringIntoViewSpec` pins the focused item near 30% of the width, so the whole row slid on every
  D-pad press.
- **Workaround:** a custom `BringIntoViewSpec` that scrolls only when the card would leave the safe area.
- **Suggestion:** make "scroll only when needed" the TV default, or document the pivot behaviour.
