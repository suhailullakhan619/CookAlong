# CookAlong demo: voice-over script

Timed to the draft video (2:42, see `video/TIMING.txt` next to the video), at a calm pace (roughly 360 words). Read the **bold** lines; the notes in *italics* say what
is on screen at that moment. Pause for a beat at each `/`. If a part runs long, cut the lines marked *(optional)*.

---

### 1. Opening (0:00 – 0:15)

*Home screen on the Fire TV. Move along a row so the big photo at the top changes.*

**It's seven in the evening, / you're hungry, / and you have no plan.**
**This is CookAlong. / It turns your Fire TV into a kitchen assistant / you control from the sofa, / or from the stove.**

### 2. Fridge scan (0:15 – 0:45)

*Press Fridge scan. Show the QR code, then the phone scanning it and taking one photo of the open fridge. Back on the TV:
"Looking at your photo", then the ingredient chips and the recipes you can cook.*

**Press Fridge scan, / point your phone at the code, / and take one photo of your fridge.**
**Amazon Nova on Bedrock reads the photo / and finds the ingredients.**
**CookAlong then checks all 585 recipes / and shows what you can cook right now, / and what you're only one or two items away from.**

### 3. Plan dinner with an agent (0:45 – 1:20)

*Press Plan dinner. Show "Planning dinner", then the meal cards, swaps, the shopping list and the footer "Planned by an
agent on Amazon Nova".*

**Now press Plan dinner.**
**An AI agent, / built with the Strands Agents SDK on Amazon Nova, / searches our recipe database, / suggests swaps for what's missing, / and builds a shopping list for the number of people you're cooking for.**
**A Bedrock Guardrail keeps it on topic, / and the server checks every recipe, / time and ingredient / before anything reaches your screen. / The agent can't invent a dish.**
*(optional)* **Scan the code on screen / and the shopping list goes to your phone.**

### 4. Cooking mode (1:20 – 2:05)

*Open a recipe. Change the servings, press Start Cooking. Show a big step with its animation, start the step timer,
move to the next step, let Read aloud speak.*

**Pick a recipe / and press Start Cooking.**
**Cooking mode shows one big step at a time, / easy to read from across the kitchen.**
**Timers start with one press, / and you can run several at once.**
**Read aloud speaks each step, / so your eyes can stay on the pan.**
**Everything works with just the remote.**

### 5. Voice with Alexa (2:05 – 2:32)

*Show the Connect Alexa screen, then the Alexa simulator (Developer Console or `tools/alexa_sim.py`) next to the TV, and
the TV moving to the next step.*

**You can also cook by voice. / Here I'm using the Alexa simulator / to say "next step", / and the TV follows.**
**You can set a timer, / or ask what to use instead of parmesan.**

### 6. Close (2:32 – 2:42)

*Title card: CookAlong, "Fire TV + AWS Builder", the GitHub URL.*

**CookAlong. / Built for Fire TV, / with Amazon Bedrock, / Strands, / Lambda / and DynamoDB.**
**The code is open on GitHub. / Thanks for watching.**

---

## Recording tips

- Record the voice after the screen video, so you can match each section to its clip.
- Use a quiet room and keep the phone or mic about a hand's width from your mouth.
- Say "Alexa simulator" exactly as written: the skill has only been tested in the simulator, not on a real Echo.
- The plan takes 5 to 15 seconds. Cut the wait in editing, or speed it up and show "sped up" on screen.
- Don't say anything the video doesn't show. If you skip a part (for example offline), skip its lines too.
