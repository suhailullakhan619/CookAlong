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
tested with 82 app tests and 78 backend tests, with credited open-licence recipes and photos.

### What we learned
Nova Pro calls tools reliably and reads food photos well in about two seconds. Guardrails are a cheap way to keep a
cooking assistant on topic. On TV, focus and scrolling details decide whether an app feels good.

### What's next
Kitchens without a TV: a standalone cooking session kept in the skill plus an Alexa Presentation Language screen, so an Echo Show can show the current step, ingredients and timers by itself (not built yet; today the cooking commands need a paired TV). Also: publish the Alexa skill, push messages through Amazon Device Messaging, more regional recipes, and a Vega OS port.

## Why it matters

### Why existing options fall short

To my knowledge, the options people have today each solve one piece:

- **Phone, tablet and web recipe apps** are built for a screen held in the hand. In the kitchen the text is tiny, the screen
  locks, and the timers live in a separate app.
- **Voice-only recipe skills on smart speakers** read steps aloud but can't show the ingredients, the list or several timers
  at a glance, and they don't know what is in your fridge.
- **TV apps** are overwhelmingly for streaming. There is little cooking software designed for a remote and a 10-foot screen.
- **None of them start from your fridge.** Deciding what to cook, finding out what is missing and shopping for it is still
  manual.

CookAlong puts the whole evening on the biggest screen in the house, in one flow, and starts from what you actually have.

### Who it is for, and the impact

**Who:** households that cook at home and have a Fire TV, with a library built first for Indian home cooking (174 of the 477
recipes) and international dishes alongside. Anyone who cooks while watching the clock, or cooks with family around a screen.

**Before and after.** The machine time is measured; the human time is my estimate, not a user study.

| | Before | With CookAlong |
|---|---|---|
| Decide what to cook from what's in the fridge | Browse recipes on a phone, check the fridge, repeat: roughly 10 to 20 minutes (estimate) | Scan (about 2 s measured), plan (4 to 11 s measured), then pick: about a minute |
| Work out what to buy | Re-read ingredient lists and write a list | The plan shows the shopping list, scaled to your servings, and sends it to your phone with a QR code |
| Cook | A phone propped up with greasy hands, timers in another app | One large step at a time, several timers, read-aloud, voice commands |

**Measuring real benefit later:** time from opening the app to starting a recipe, the share of scans that end in a recipe being
cooked, recipes finished versus abandoned, and plans where the cook buys nothing extra.

### Scale, cost and privacy

**Cost (estimates from Amazon Nova list prices, not yet billed):** a fridge scan is under 1 US cent and a dinner plan is
roughly 1 to 3 cents. At two scans and two plans per household per week that is about 5 cents per household per week.

**Controls that protect the bill:** a scan needs a live pairing code (3 scans per code); each TV is limited to 12 plans an
hour; and the whole service has a daily cap (300 plans and 500 scans per UTC day), stored in DynamoDB and returned to the
app as a friendly "try again tomorrow".

**What would break first at 10,000 households, and the fix:**

| Limit | Why | Fix |
|---|---|---|
| The Alexa bridge polls once a second per TV | Thousands of TVs means millions of requests an hour | Amazon Device Messaging push, or long polling, instead of polling |
| This AWS account allows 10 concurrent Lambda executions | A default new-account quota | Request a quota increase |
| The recipe catalog is read with a DynamoDB scan (cached for 5 minutes per warm function) | Fine for 477 recipes, wasteful for tens of thousands | Serve the catalog from S3 and CloudFront, keep DynamoDB for changes |
| Bedrock model quotas | Many plans at once could be throttled | Nova 2 Lite is already the fallback; request higher quotas |

Everything else (Lambda, DynamoDB on-demand, Function URLs) scales without changes.

**Privacy and security:**
- No accounts and no sign-up. The app has no API keys; the Lambda roles are the only things allowed to call Bedrock, and they
  can invoke only Amazon Nova models and the cookalong guardrail.
- The fridge photo is shrunk on the phone, held in memory only for the Bedrock call and never stored. Only the ingredient
  list is kept, and it expires after an hour. Amazon Bedrock does not use inputs to train Nova models (per AWS's Bedrock
  data-protection documentation).
- A random device id identifies each TV. The Alexa user id is stored only to map the speaker to the TV, and "Disconnect
  Alexa" deletes the link.
- A Bedrock Guardrail masks emails and phone numbers and blocks medical and allergy-safety advice, abuse and prompt attacks.
- Honest limits: the Function URLs are public by design. The pairing code, the unguessable device id, the rate limits and the
  daily cap are the protection, not authentication.

## Honest status (so the judges are not surprised)
- **Tested on a real Fire TV Stick** (AFTMA08C15, Fire OS 8 / Android 11): install, launch, Home, Fridge scan, Plan
  dinner, cooking with timers, read-aloud in the Amazon Polly voice with the TV-voice fallback, and Alexa-simulator
  commands moving the real TV. The rest of the testing was on an Android TV emulator.
- **The Alexa skill is created in an Alexa developer account (Development stage) but not published**, and it has not been
  tried by voice on a real Echo or Fire TV. It was tested through Alexa's own simulator (`tools/alexa_sim.py`), so Alexa's
  real language understanding picked the intent, and it moved the real Fire TV. By voice on the real stick, "Alexa, next"
  and "Alexa, previous" change the cooking step through Fire TV's own media controls, with no skill involved.
  To try the full skill on your own Echo or Fire TV, email **suhailullakhan619@gmail.com** with your Amazon account's
  email and I'll send an Alexa beta invitation (private, usually within minutes).
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
