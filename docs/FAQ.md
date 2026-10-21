# Questions judges may ask

Short, honest answers. Details are in the README and in `docs/AWS.md`.

**Why did you choose this problem?**
Cooking is the moment a phone is worst and a TV is best: hands are busy, text must be big, and you need timers. And most
evenings start with "what can I cook with what I have?", which no TV app answers.

**What makes it different from existing apps?**
It starts from your fridge (a phone photo read by Amazon Nova), plans the meal with an agent that can't invent recipes, and
then guides you step by step on the big screen with timers, read-aloud and voice. See "Why existing options fall short" in
the README.

**Why these Amazon technologies?**
Fire TV is where the cooking happens. Amazon Nova on Bedrock reads the photo and drives the agent with no Marketplace
subscription (important on my India-billed account). Strands gives the agent tool calling and typed output in about 150
lines. Bedrock Guardrails keep a kitchen assistant on topic. Lambda, Function URLs and DynamoDB give a serverless backend with
no servers and no keys in the app. Alexa lets a voice from any room control the cooking.

**What happens if the API fails or the user gives bad input?**
- *No internet:* saved recipes and timers keep working, read-aloud switches from the Amazon Polly voice to the TV's own
  voice, and Home says "You're offline". The README's "Online and offline behaviour" table covers every feature.
- *Scan or plan fails:* a clear message with Try again; a plan that returns nothing usable says so instead of guessing.
- *Model invents something:* the server drops any recipe that isn't in DynamoDB and re-checks diet, time and missing items.
- *Bad requests:* the backend validates every field and returns a 400 with a plain message; a TV never sees a stack trace.
- *Throttling or a spend limit:* Nova 2 Lite is the fallback model, and per-TV and daily caps return "try again later".

**How would you scale to 10,000 or 100,000 users?**
Lambda and DynamoDB on-demand scale as is. The first things to change are the Alexa polling (use Amazon Device Messaging or
long polling), the Lambda concurrency quota (this account allows 10), the recipe catalog (serve it from S3 and CloudFront),
and Bedrock quotas. The table in the README lists each limit and its fix. Estimated cost is about 5 cents per household per week
at two scans and two plans.

**How are user data and credentials protected?**
No accounts, no keys in the app, least-privilege roles per Lambda, fridge photos never stored, ingredient lists expire after an
hour, and a guardrail masks emails and phone numbers. The Function URLs are public by design; pairing codes, unguessable
device ids, rate limits and a daily cap protect them, not authentication.

**What was the biggest technical challenge?**
Making an AI-planned dinner trustworthy. The agent only chooses and explains; the server re-checks every recipe, diet, time
limit, missing item and shopping-list entry against DynamoDB before anything is shown. A close second: Claude could not be
enabled on my India-billed AWS account, so I moved every feature to Nova and measured Nova Pro against Nova 2 Lite and Nova
Lite on real food photos.

**What did you build during the hackathon?**
Everything. The project started in October 2026 (earliest files dated 2026-10-14, first commit 2026-10-19), after the Aug 31
cut-off for existing projects.

**What would you improve with another week?**
1. Echo Show: a standalone cooking session in the skill plus an Alexa Presentation Language screen, so kitchens without a TV
   can cook from a display. This is not built; today the cooking commands need a paired TV.
2. Publish the Alexa skill and try it on a real Echo and Fire TV (so far it was tested through Alexa's simulator).
3. Replace polling with Amazon Device Messaging push.
4. More regional recipes and a Vega OS port.
5. Measure real cooks: time to a started recipe, scans that become meals, and recipes finished.

**How would you measure whether users actually benefit?**
Time from opening the app to starting a recipe, the share of scans that end in a cooked meal, recipes finished versus
abandoned, plans where the cook buys nothing extra, and a one-question "did this save you time?" after cooking.

**What are the limitations?**
The Alexa skill is not published and was tested through Alexa's simulator, not on a real Echo. "Alexa, next" and "Alexa,
previous" were confirmed by voice on the real Fire TV, through its media controls. Echo Show is not built. The CloudFormation template is validated but the
live stack was made with the deploy scripts. Bedrock AgentCore is not used. Testing on a real Fire TV so far covers one device
(Fire TV Stick AFTMA08C15, Fire OS 8).
