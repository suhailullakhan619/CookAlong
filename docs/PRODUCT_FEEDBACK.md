# Product feedback

One entry per tool, API or SDK we used while building CookAlong, for the Devpost form. "Build again?" answers are honest:
where we only used a tool lightly or couldn't finish with it, we say so.

## AWS services (AWS Builder mini challenge)

### Amazon Bedrock: Amazon Nova Pro and Nova 2 Lite (Converse API)
- **Used for:** reading a fridge photo and listing ingredients, spoken "ask the chef" answers, and the reasoning model of
  the dinner-planner agent.
- **Worked well:** one Converse call shape for text, images and tool use. Nova Pro read food photos in about 2 seconds,
  called our four tools in a sensible order, and followed a structured-output schema. We compared Nova Pro, Nova 2 Lite and
  Nova Lite on four real photos and picked Nova Pro first, Nova 2 Lite as backup. No model-access request was needed for Nova
  in Mumbai.
- **Needs work:** model IDs for regional profiles (`apac.…`, `global.…`) are easy to get wrong and the error doesn't list the
  valid ones. Nova sometimes states numbers in prose that disagree with the data (e.g. a wrong cooking time), so we
  validate everything shown on screen server-side.
- **Onboarding:** quick, the docs and the CLI `list-inference-profiles` made it easy to find what was available.
- **Build again?** Yes.

### Amazon Bedrock Guardrails
- **Used for:** keeping the dinner-planner on cooking: a denied topic for medical and allergy-safety advice, abuse filters, a
  prompt-attack filter and masking of emails and phone numbers.
- **Worked well:** created with one API call and attached to the model with two parameters. In our test it blocked "Is this
  recipe safe for my peanut allergy?" and a prompt-injection string, and let a normal substitution question through.
- **Needs work:** versions are easy to multiply (publishing creates a new numbered version each time); a "get latest
  version" call would help.
- **Build again?** Yes.

### Strands Agents SDK
- **Used for:** the dinner-planner agent (`backend/dinner_agent.py`): four `@tool` functions and a Pydantic
  `structured_output_model`.
- **Worked well:** about 150 lines for a working tool-using agent with typed output, running on Amazon Nova through Bedrock.
- **Needs work:** installing it into a Linux Lambda zip from Windows fails (`mcp` pulls in Windows-only `pywin32`, which pip
  resolves on the host). We pinned a `--no-deps` list. See the friction log.
- **Onboarding:** good examples; a Lambda packaging recipe would save an hour.
- **Build again?** Yes.

### AWS Lambda and Function URLs
- **Used for:** three functions (recipes + fridge scan + TV bridge, the agent, the Alexa skill).
- **Worked well:** Function URLs gave a public HTTPS API for a TV app in one command, with no API Gateway. A 29 MB zip with
  the Strands SDK deployed directly.
- **Needs work:** the account's concurrency limit was too low to set reserved concurrency, so cost control relies on
  in-function limits.
- **Build again?** Yes.

### Amazon DynamoDB
- **Used for:** the recipe catalog (477 items), fridge-scan results and Alexa pairing and command queues (TTL), per-TV rate
  limits.
- **Worked well:** on-demand billing and TTL fit short-lived data perfectly; conditional writes made pairing codes safe.
- **Needs work:** nothing significant. A paginated `Scan` is fine at this size.
- **Build again?** Yes.

### AWS IAM, CloudWatch Logs, CloudFormation, AWS CLI
- **Used for:** one least-privilege role per function, function logs, a template that recreates the stack, and the CLI for
  deploy scripts.
- **Worked well:** `aws cloudformation validate-template` caught mistakes early; the CLI made scripted deploys easy.
- **Needs work:** we only validated the template, we deployed with scripts. Bedrock Marketplace errors (see the friction
  log) are the one real rough edge.
- **Build again?** Yes.

## Amazon platform (Fire TV track)

### Fire OS and the Fire TV Stick
- **Used for:** running the native Kotlin app, `adb` over the network, the MediaSession voice path, text-to-speech.
- **Worked well:** installing over `adb connect` was painless, and an Android app with no Google services ran unchanged.
- **Needs work:** the stick's default text-to-speech engine had no voice data, so speech silently failed until we tried every
  installed engine.
- **Build again?** Yes.

### Jetpack Compose for TV (`androidx.tv:tv-material`)
- **Used for:** the whole UI: focus handling, cards, rows, overlays.
- **Worked well:** focus-aware surfaces and the focus system gave a good TV feel quickly.
- **Needs work:** the default scroll-into-view pivots the focused item near 30% of the width, so rows slid on every key
  press. We replaced it with a "scroll only when needed" `BringIntoViewSpec`.
- **Build again?** Yes.

### Android Studio and the Android TV emulator
- **Used for:** most development and screenshots (a 1080p Android TV API 30 image).
- **Worked well:** fast iteration and scripted input with `adb`.
- **Needs work:** it was not clear to us whether an Android TV emulator counts as "the Fire TV simulator" for the demo
  video requirement. A Fire OS emulator image would remove the doubt.

### Alexa Skills Kit and the ASK CLI
- **Used for:** the skill's interaction model (36 intents, sample phrases, slots), request handling in Lambda, and
  testing. We created the skill with the ASK CLI (`ask deploy`) and spoke to it through `ask smapi simulate-skill`, which
  uses Alexa's real language understanding. We did not try it on a real Echo or Fire TV and did not publish it.
- **Worked well:** the interaction model is easy to author as JSON; `ask deploy` built all models in about two minutes,
  and the simulator let us test the full voice-to-TV chain with no Echo.
- **Needs work:** a Fire TV app has no way to learn which Alexa account is speaking, so linking needs a pairing code.
  `ask configure` needs an interactive browser sign-in plus a Y/n prompt, which can't be scripted, and it also asks about
  an AWS profile. See the friction log and feature requests.
- **Build again?** Yes, once the app-to-account link exists.

## Other tools and data sources
- **GitHub and GitHub Pages:** hosted the repo, the phone shopping-list page and 406 credited recipe photos. No problems.
- **Wikimedia Commons and Openverse APIs:** found openly licensed photos with author and licence metadata, which made
  correct credits possible. Openverse needed no API key.
- **Open recipe sources (based.cooking, Wikibooks Cookbook, HowToCook):** clear licences made a 477-recipe library possible.
  HowToCook is in Chinese, so we translated it with Amazon Nova and re-checked quantities.
