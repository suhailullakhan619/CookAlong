# Devpost submission: CookAlong

Copy these answers into the Devpost form. Items marked **TO FILL** need something only you have.

## Basics
- **Project name:** CookAlong
- **Tagline:** Your Fire TV becomes a hands-free kitchen: scan your fridge, let an AI agent plan dinner, then cook step by step with timers and your voice.
- **Track:** Fire TV
- **Mini challenge:** AWS Builder (not entering the Open Source challenge)
- **Code repository:** https://github.com/suhailullakhan619/CookAlong (public, MIT licence)
- **Demo video:** TO FILL (public YouTube or Vimeo link, under 3 minutes, English)
- **Built with:** Kotlin, Jetpack Compose for TV, Android/Fire OS, Amazon Bedrock (Amazon Nova Pro, Nova 2 Lite, Guardrails), Strands Agents SDK, AWS Lambda, Amazon DynamoDB, AWS CloudFormation, Alexa Skills Kit, Python, GitHub Pages

## Text description

### The problem
Cooking from a phone or tablet means greasy screens, tiny text, and a timer app you keep losing. A TV is the biggest, easiest
screen in the home, but recipe apps are built for phones. And most of us open the fridge with no plan.

### What CookAlong does
CookAlong is a native Fire TV app for the whole cooking evening:

1. **Fridge scan (computer vision).** The TV shows a QR code. You scan it with your phone, take one photo of your open
   fridge or pantry, and Amazon Nova Pro on Amazon Bedrock lists the ingredients it can see. The TV then ranks all 477
   recipes by what you can cook ("Ready to cook" and "Almost there", with exactly what is missing). It understands Indian
   and international names (capsicum, curd, brinjal, atta).
2. **Plan dinner (an AI agent).** One button hands the ingredients to an agent built with the Strands Agents SDK on Nova Pro.
   It searches the recipe database with tools, checks recipes, suggests swaps for missing items and builds a shopping list.
   The TV shows one to three meals, the shopping list and a tip, and reads the summary aloud. A Bedrock Guardrail keeps it
   on cooking and away from medical or allergy advice.
3. **Cooking mode (multi-modal).** One big step at a time, readable from across the room, with animation, read-aloud, up to
   four timers, a chime, and resume after a restart. It works with the remote only, and works fully offline.
4. **Voice.** On Fire TV, "Alexa, next / previous / pause / play" drive the steps and timers through the media session.
   A custom Alexa skill ("cook along") lets any Echo in the home talk to the app on the TV once you pair it with a
   six-digit code: next step, go to step 4, a five-minute timer, "how much paneer do I need" (scaled to your servings),
   recipe search, "what's for dinner" and "ask the chef". I created the skill in an Alexa developer account and tested the
   whole chain through Alexa's simulator with the app running on an emulator (see "Honest status").
5. **Shopping list to your phone** with a QR code, on any network.

The recipe library has 477 recipes, 174 of them Indian, all from openly licensed sources (based.cooking, Wikibooks
Cookbook, HowToCook), stored in DynamoDB and synced to the TV, with 406 credited photos.

### How it works
The TV app is Kotlin with Jetpack Compose for TV and needs no Google services. Everything smart runs on AWS in Mumbai, with
no API keys in the app:

- **Lambda `cookalong-fridge-scan`** serves recipes from DynamoDB, runs the fridge scan (phone photo to Nova Pro), and hosts
  the TV bridge.
- **Lambda `cookalong-agent`** runs the Strands dinner-planner agent. Its tools read DynamoDB. After the agent answers, the
  server re-checks every fact the TV shows (the recipe exists, diet and time limits hold, minutes, missing items), so the
  model chooses and explains but can't invent a recipe.
- **Amazon Bedrock Guardrails** protect the agent; **DynamoDB** with TTL holds scans, pairing codes and per-TV limits.
- **Lambda `cookalong-alexa`** handles the Alexa skill. Fire TV apps can't receive pushes, so the TV polls a small command
  queue while it is on screen.
- A CloudFormation template (`backend/template.yaml`) recreates the AWS resources. Architecture diagram: `docs/AWS.md`.

### Challenges
- Claude could not be enabled on an India-billed AWS account (the Marketplace subscription fails), so we moved everything to
  Amazon Nova and measured Nova Pro and Nova 2 Lite against each other on real food photos.
- The Fire Stick's default text-to-speech engine has no voices, so Read aloud was silent until we made it try every
  installed engine.
- Linking a Fire TV app to a user's Alexa account has no API, so we built a six-digit pairing code and a polling queue.
- Making a model-planned dinner trustworthy: the agent's output is validated against the database before it is shown.

### What we're proud of
A real working pipeline from a phone photo to an agent-planned dinner to hands-free cooking on a real Fire TV Stick,
tested with 82 app tests and 61 backend tests, with credited open-licence recipes and photos.

### What we learned
Nova Pro calls tools reliably and reads food photos well in about two seconds. Guardrails are a cheap way to keep a
cooking assistant on topic. On TV, focus and scrolling details decide whether an app feels good.

### What's next
Kitchens without a TV: a standalone cooking session kept in the skill plus an Alexa Presentation Language screen, so an Echo Show can show the current step, ingredients and timers by itself (not built yet; today the cooking commands need a paired TV). Also: publish the Alexa skill, push messages through Amazon Device Messaging, more regional recipes, and a Vega OS port.

## Honest status (so the judges are not surprised)
- **Tested on a real Fire TV Stick** (AFTSSS, Fire OS 7): install, launch, Home with all online recipes and photos, Read
  aloud, cooking flow. The rest of the testing was on an Android TV emulator.
- **The Alexa skill is created in an Alexa developer account (Development stage) but not published**, and it has not been
  tried by voice on a real Echo or Fire TV. It was tested through Alexa's own simulator (`tools/alexa_sim.py`), so Alexa's
  real language understanding picked the intent, with an Android TV emulator as the TV. The plain "Alexa, next/pause"
  media-session commands were tested with `adb shell cmd media_session dispatch`, not by voice on a real stick.
- **CloudFormation:** the template validates, but the live resources were created with the deploy scripts.
- **Amazon Bedrock AgentCore is not used**; the agent runs on Lambda.

## AWS Builder mini challenge
**Services used:** Amazon Bedrock (Nova Pro, Nova 2 Lite via the Converse API, Guardrails), Strands Agents SDK, AWS Lambda
(three functions plus Function URLs), Amazon DynamoDB (three tables with TTL), AWS IAM (one least-privilege role per
function), Amazon CloudWatch Logs, AWS CloudFormation.

**How:** see "How it works" above and `docs/AWS.md` (architecture diagram, what each service does, cost and safety controls,
how to deploy). The agent is a multi-step tool-using design, not a single model call: it chooses which of four tools to
call, then returns a typed plan that is verified server-side.

## If the project existed before the hackathon
It did not. Work started in October 2026 (earliest files dated 2026-10-14, first commit 2026-10-19), after the Aug 31
cut-off. Everything in the repository was built during the submission window.

## Repository access
The repository is public with an MIT `LICENSE`, so no collaborators need to be added. (Only a private repo must be shared
with testing@devpost.com and the Amazon reviewers.)

## Product feedback, feature requests, friction log
Paste from `docs/PRODUCT_FEEDBACK.md`, `docs/FEATURE_REQUESTS.md` and `docs/FRICTION_LOG.md`.
