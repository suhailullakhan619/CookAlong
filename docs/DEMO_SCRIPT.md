# Demo video script (target 2:40, hard limit 3:00)

Judges may stop at 3:00, so the best material goes first. The video must show the app on a **real Fire TV device** (or the
Fire TV simulator). Record the TV screen with a camera or a capture card; record the phone separately. English voice-over.
No music unless it is royalty-free and you have the licence. Don't show third-party logos beyond the app's own screens.

**Before recording:** install the release APK on the stick, turn Read aloud on, pre-make one fridge photo with 6 to 8
visible ingredients (paneer, onion, capsicum, tomato, curd, rice), and check `plannerApiUrl` and `scanApiUrl` in
`config.json`.

| Time | On screen | Voice-over |
|---|---|---|
| 0:00-0:12 | Home on the TV: shelves of real dishes, then press Fridge scan | "It's dinner time and you have no plan. CookAlong turns your Fire TV into a kitchen assistant." |
| 0:12-0:40 | Phone scans the TV's QR code, takes one photo of the open fridge. TV shows "Looking at your photo..." then the ingredient chips and "Ready to cook / Almost there" with "You have 5 of 7, Need: ..." | "Scan the code, take one photo. Amazon Nova on Bedrock reads the fridge, and the TV ranks 477 recipes by what you can cook right now." |
| 0:40-1:15 | Press Plan dinner. Show "Planning dinner...", then the meal cards, swap ideas, the shopping list and the footer "Planned by an agent on Amazon Nova". The TV reads the summary aloud | "Plan dinner starts an agent built with the Strands SDK. It searches our recipe database with tools, suggests swaps and builds the shopping list. A Bedrock Guardrail keeps it on cooking, and the server checks every fact before it reaches the screen." |
| 1:15-1:30 | Optional split screen: terminal with the agent's tool trace (`find_recipes, get_recipe, suggest_swap, shopping_list`) | "Every step is a real tool call you can inspect." |
| 1:30-2:15 | Open a recipe, scale servings, Start Cooking. Show a big step, the step animation, start the step timer, jump to a second timer, hear Read aloud. Press the remote's next-step key | "Cooking mode shows one big step at a time, with timers, animation and read-aloud. It works with just the remote." |
| 2:15-2:30 | Voice: speak to the skill. Either say "Alexa, next" to the stick **if you confirmed it on the device**, or show the Alexa Developer Console simulator (or `python tools/alexa_sim.py "tell cook along next step"`) with the TV reacting. Say "Alexa simulator" out loud so it is not mistaken for a real Echo | "Voice works too: next step, a five minute timer, or what can I use instead of cream." |
| 2:30-2:40 | Turn Wi-Fi off: banner "You're offline. Showing saved recipes." Cooking still works | "No internet? Cooking mode and your saved recipes still work." |
| 2:40-2:55 | Title card: CookAlong, "Fire TV and AWS Builder", repo URL on screen | "CookAlong: built on Fire TV with Amazon Bedrock, Strands, Lambda and DynamoDB. The code is on GitHub." |

**Do not claim** the skill works on a real Echo or stick unless you tried it there. When you show it through the console or `tools/alexa_sim.py`, say it is the Alexa simulator. The honest status is in `docs/DEVPOST.md`.

**Retakes to plan for:** the plan takes 5 to 15 seconds, so cut the waiting or speed it up and say so. Shoot the fridge
photo in good light.
