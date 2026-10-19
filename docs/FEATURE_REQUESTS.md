# Feature requests

What we would want built, why it matters, and how urgent it is.

| # | Request | Why it matters | Urgency |
|---|---|---|---|
| 1 | An API for a Fire TV app to link itself to the Alexa account signed in on that device | Today a custom skill only sees an anonymous user id, so apps need a pairing code. Any "talk to the app on my TV" skill hits this | Important |
| 2 | A first-party channel from an Alexa custom skill to an installed Fire TV app (without running your own backend or Amazon Device Messaging) | Skills can't reach apps, so every developer builds a polling queue like ours | Important |
| 3 | A clear Bedrock error when a model needs a Marketplace-capable payment method, and documentation of which account types can enable third-party models | Our India (AISPL) account got "not authorized to perform aws-marketplace:Subscribe" even as root, which sent us debugging IAM instead of billing | Important |
| 4 | A Fire OS emulator image or a documented simulator for Fire OS (Android) apps, and a statement of whether it satisfies the demo-video rule | Developers without a stick can't show a "Fire TV simulator" for an Android app | Important |
| 5 | Voice data shipped with the Fire Stick's default text-to-speech engine, or an API to ask which voices are available | Read aloud silently failed on a stock stick because `com.ivona.tts.oem` had no English voice | Important |
| 6 | A documented recipe for packaging the Strands SDK into Lambda from Windows (or make the Windows-only `pywin32` dependency an extra) | `pip install --platform manylinux2014_x86_64 strands-agents` fails on Windows with `ResolutionImpossible` | Nice-to-have |
| 7 | "Scroll only when needed" as the default `BringIntoViewSpec` for Compose for TV rows, or a documented TV preset | The default pivot slid the whole row on every D-pad press | Nice-to-have |
| 8 | A `get-latest-version` call for Bedrock Guardrails | Setup scripts have to list versions to avoid publishing a new one every run | Nice-to-have |
