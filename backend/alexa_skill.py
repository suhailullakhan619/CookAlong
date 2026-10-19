"""CookAlong Alexa skill - custom skill backend (AWS Lambda, Python 3.12, no third-party packages).

Lets any Echo on the user's Amazon account, or the Alexa button on the Fire TV remote, drive CookAlong on the TV:
  "Alexa, ask Cook Along to pair with 482157"        (one time: the TV shows the code)
  "Alexa, tell Cook Along next step"                 "...go to step 4"         "...repeat that"
  "Alexa, tell Cook Along to set a timer for 5 minutes"   "...add 2 minutes"  "...how long is left"
  "Alexa, ask Cook Along how much rice I need"       "...what can I use instead of cream"
  "Alexa, tell Cook Along to find paneer recipes"    "...make it for 6 people" "...start cooking"
  "Alexa, ask Cook Along what's for dinner"          (plans dinner from the last fridge scan with the AWS agent)

Commands go into the TV's queue (tv_bridge.py); the TV polls the CookAlong backend while it's on screen. Questions
are answered from the state the TV publishes, so Alexa reads the same scaled quantities the TV shows.
"Ask the chef" questions go to Amazon Nova on Bedrock.
"""
import json
import os
import re
import time

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from tv_bridge import Bridge

SKILL_ID = os.environ.get("ALEXA_SKILL_ID", "")
BEDROCK_REGION = os.environ.get("BEDROCK_REGION", "ap-south-1")
# Amazon Nova Pro, with Nova 2 Lite as a backup if Pro is throttled or unavailable.
CHEF_MODELS = [m for m in os.environ.get(
    "CHEF_MODELS", "apac.amazon.nova-pro-v1:0,global.amazon.nova-2-lite-v1:0").split(",") if m]
TV_STALE_MS = 3 * 60_000   # the TV re-publishes its state every minute while CookAlong is on screen
MAX_TIMERS = 4
CHEF_BUDGET_SECONDS = 4.5   # don't start a second model once the first has used this much of Alexa's 8 seconds
MAX_TIMER_SECONDS = 3 * 3600

NOT_LINKED = ("Your TV isn't connected yet. On your Fire TV, open Cook Along and choose Connect Alexa. "
              "Then say: Alexa, ask Cook Along to pair with, and the six digit code.")
TV_OFF = "I can't reach Cook Along on your TV. Open the app on your Fire TV, then try again."
NOT_COOKING = "You're not cooking a recipe right now. Pick one on your TV, then say: start cooking."
NO_RECIPE = "Open a recipe on your TV first, or say: find, and the name of a dish."
HELP = ("While you cook, you can say: next step, go back, repeat that, set a timer for five minutes, "
        "how long is left, what ingredients do I need, or what can I use instead of cream. "
        "To find a dish, say: find paneer recipes.")

_bridge = None
_bedrock = None
_dead_models = set()   # models this account can't use; skipped until the container recycles


def handler(event, context, store=None, chef=None):
    """Entry point. Alexa must always get a spoken answer: anything unexpected becomes an apology, never a crash."""
    try:
        return _handle(event, context, store, chef)
    except PermissionError:
        raise
    except Exception as e:  # noqa: BLE001 - a TV state or DynamoDB hiccup must not surface as an Alexa error
        print("Skill request failed", type(e).__name__, e)
        launched = bool(((event.get("session") or {}).get("attributes") or {}).get("launched"))
        return {"version": "1.0", "response": {
            "outputSpeech": {"type": "PlainText", "text": "Sorry, something went wrong on my side. Please try again."},
            "shouldEndSession": not launched}}


def _handle(event, context, store=None, chef=None):
    global _bridge
    if store is None:
        _bridge = _bridge or Bridge()
        store = _bridge
    system = event.get("context", {}).get("System", {})
    session = event.get("session") or {}
    app_id = (system.get("application") or session.get("application") or {}).get("applicationId", "")
    if SKILL_ID and app_id != SKILL_ID:
        raise PermissionError("Request is not for this skill")

    req = event.get("request", {})
    rtype = req.get("type", "")
    if rtype == "SessionEndedRequest":
        return {"version": "1.0", "response": {}}
    user = (system.get("user") or session.get("user") or {}).get("userId", "")
    turn = Turn(store, user, chef or ask_chef, launched=bool((session.get("attributes") or {}).get("launched")))
    if rtype == "LaunchRequest":
        return turn.launch()
    if rtype != "IntentRequest":
        return turn.say("Sorry, I can't do that.")
    intent = req.get("intent") or {}
    slots = {}
    for k, v in (intent.get("slots") or {}).items():
        value = (v or {}).get("value")
        slots[k] = None if value is None else str(value)
    fn = INTENTS.get(intent.get("name", ""), Turn.fallback)
    return fn(turn, slots)


class Turn:
    """One request from Alexa: who is speaking, which TV they control, and helpers to answer."""

    def __init__(self, store, user, chef, launched):
        self.store, self.user, self.chef, self.launched = store, user, chef, launched
        self._device = None

    # ---- responses ---------------------------------------------------------------------------------------------

    def say(self, text, end=None, reprompt=None):
        """One-shot requests ("tell Cook Along next step") end the session; after "open Cook Along" it stays open."""
        if end is None:
            end = not self.launched
        response = {
            "outputSpeech": {"type": "PlainText", "text": text},
            "card": {"type": "Simple", "title": "Cook Along", "content": text},
            "shouldEndSession": end,
        }
        if not end:
            response["reprompt"] = {"outputSpeech": {"type": "PlainText", "text": reprompt or "What next?"}}
        return {"version": "1.0", "sessionAttributes": {"launched": self.launched}, "response": response}

    # ---- the TV ------------------------------------------------------------------------------------------------

    @property
    def device(self):
        if self._device is None:
            self._device = self.store.device_for_user(self.user) or ""
        return self._device

    def tv(self):
        """(state, problem): the TV's current state, or the sentence to say when it can't be used."""
        if not self.device:
            return None, NOT_LINKED
        state, age = self.store.get_state(self.device)
        if state is None or age > TV_STALE_MS:
            return None, TV_OFF
        state["_ageMs"] = age  # running timers have ticked down since the TV published
        return state, None

    def send(self, command_type, **args):
        self.store.push(self.device, dict(args, type=command_type))

    # ---- launch & pairing --------------------------------------------------------------------------------------

    def launch(self):
        self.launched = True
        if not self.device:
            return self.say("Welcome to Cook Along. To connect your TV, open Cook Along on your Fire TV and choose "
                            "Connect Alexa. Then tell me the six digit code.", reprompt="What's the code on your TV?")
        state, problem = self.tv()
        if problem:
            return self.say(problem, end=True)
        if state.get("screen") == "cooking":
            return self.say(f"You're on step {state['step'] + 1} of {state['stepCount']} of {state['title']}. "
                            "Say next step, repeat that, or set a timer.")
        return self.say("Cook Along is connected to your TV. You can say: find paneer recipes, start cooking, "
                        "or what can I make in 20 minutes.")

    def pair(self, slots):
        code = re.sub(r"\D", "", slots.get("code") or "")
        if len(code) != 6:
            return self.say("Please say the six digit code shown on your TV.", end=False,
                            reprompt="What's the six digit code on your TV?")
        device = self.store.claim_pair_code(code, self.user)
        if not device:
            return self.say("That code didn't work. It may have expired. Choose Connect Alexa on your TV to get a "
                            "new one.", end=False, reprompt="What's the code on your TV?")
        self._device = device
        self.send("linked")
        return self.say("Your TV is connected! While you cook, try: Alexa, tell Cook Along next step.")

    def unlink(self, slots):
        if not self.device:
            return self.say("Cook Along isn't connected to a TV.")
        self.store.unlink(self.device)
        return self.say("Done. Your TV is disconnected from Alexa.", end=True)

    # ---- steps -------------------------------------------------------------------------------------------------

    def cooking(self):
        state, problem = self.tv()
        if problem:
            return None, problem
        if state.get("screen") != "cooking":
            return None, NOT_COOKING
        return state, None

    def step_reply(self, state, index, prefix=""):
        """The TV reads the step aloud itself when Read aloud is on; then Alexa keeps it short to avoid talking over it."""
        if state.get("readAloud"):
            return self.say(f"{prefix}Step {index + 1}.")
        text = state["steps"][index] if index < len(state.get("steps", [])) else ""
        return self.say(f"{prefix}Step {index + 1} of {state['stepCount']}. {text}")

    def next_step(self, slots):
        state, problem = self.cooking()
        if problem:
            return self.say(problem)
        i = state["step"]
        if i >= state["stepCount"] - 1:
            return self.say("That's the last step. When you're done, say: I'm finished.")
        self.send("next")
        return self.step_reply(state, i + 1)

    def previous_step(self, slots):
        state, problem = self.cooking()
        if problem:
            return self.say(problem)
        i = state["step"]
        if i == 0:
            return self.say("You're on the first step.")
        self.send("previous")
        return self.step_reply(state, i - 1)

    def repeat_step(self, slots):
        state, problem = self.cooking()
        if problem:
            return self.say(problem)
        self.send("repeat")
        return self.step_reply(state, state["step"])

    def go_to_step(self, slots):
        state, problem = self.cooking()
        if problem:
            return self.say(problem)
        try:
            n = int(slots.get("number") or "")
        except ValueError:
            return self.say(f"Which step? There are {state['stepCount']}.", end=False)
        if not 1 <= n <= state["stepCount"]:
            return self.say(f"This recipe has {state['stepCount']} steps.")
        self.send("goto", step=n - 1)
        return self.step_reply(state, n - 1)

    def start_over(self, slots):
        return self.go_to_step({"number": "1"})

    def finish(self, slots):
        state, problem = self.cooking()
        if problem:
            return self.say(problem)
        self.send("finish")
        return self.say(f"Well done! Enjoy your {state['title']}.")

    # ---- timers ------------------------------------------------------------------------------------------------

    def start_timer(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if state.get("screen") != "cooking":
            return self.say("Cook Along timers work while you're cooking a recipe on your TV. "
                            "For a regular timer, just say: Alexa, set a timer.")
        active = [t for t in state.get("timers", []) if t["status"] != "COMPLETED"]
        if len(active) >= MAX_TIMERS:
            return self.say(f"You already have {MAX_TIMERS} timers going. Cancel one first.")
        seconds = parse_duration(slots.get("duration"))
        if seconds is None:
            step_timer = state.get("stepTimerSeconds")
            label = state.get("stepTimerLabel") or ""
            if step_timer and label and any(t.get("label") == label and t["status"] != "COMPLETED" for t in active):
                return self.say(f"The {label} timer is already going.")
            if step_timer:
                self.send("startStepTimer")
                return self.say(f"Starting the {state.get('stepTimerLabel') or 'step'} timer for {spoken_duration(step_timer)}.")
            return self.say("How long should the timer be?", end=False, reprompt="For example: five minutes.")
        if not 0 < seconds <= MAX_TIMER_SECONDS:
            return self.say("Timers can be up to three hours.")
        self.send("timer", seconds=seconds)
        return self.say(f"Starting a {spoken_duration(seconds, adjective=True)} timer on your TV.")

    def add_time(self, slots):
        state, problem = self.cooking()
        if problem:
            return self.say(problem)
        timer = soonest_timer(state)
        if timer is None:
            return self.say("There's no timer running to add time to.")
        seconds = parse_duration(slots.get("duration")) or 60
        if not 0 < seconds <= MAX_TIMER_SECONDS:
            return self.say("I can add up to three hours.")
        self.send("addTime", seconds=seconds, timerId=timer["id"])
        return self.say(f"Added {spoken_duration(seconds)} to the {timer['label']} timer.")

    def timer_status(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        active = [t for t in state.get("timers", []) if t["status"] != "COMPLETED"]
        done = [t for t in state.get("timers", []) if t["status"] == "COMPLETED"]
        if not active and not done:
            return self.say("You don't have any timers going.")
        parts = []
        for t in sorted(active, key=lambda t: remaining_seconds(t, state)):
            left = spoken_duration(remaining_seconds(t, state))
            parts.append(f"the {t['label']} timer is paused with {left} left" if t["status"] == "PAUSED"
                         else f"the {t['label']} timer has {left} left")
        parts += [f"the {t['label']} timer is done" for t in done]
        text = sentence(parts)
        return self.say(text[0].upper() + text[1:] + ".")

    def pause_timers(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if not any(t["status"] == "RUNNING" for t in state.get("timers", [])):
            return self.say("No timers are running.")
        self.send("pause")
        return self.say("Timers paused.")

    def resume_timers(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if not any(t["status"] == "PAUSED" for t in state.get("timers", [])):
            return self.say("No timers are paused.")
        self.send("resume")
        return self.say("Timers running again.")

    def stop_timer(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if any(t["status"] == "COMPLETED" for t in state.get("timers", [])):
            self.send("dismissFinished")
            return self.say("Timer dismissed.")
        timer = soonest_timer(state)
        if timer is None:
            return self.say("You don't have any timers going.")
        self.send("cancelTimer", timerId=timer["id"])
        return self.say(f"Cancelled the {timer['label']} timer.")

    # ---- ingredients & recipe ----------------------------------------------------------------------------------

    def recipe_state(self):
        state, problem = self.tv()
        if problem:
            return None, problem
        if not state.get("recipeId"):
            return None, NO_RECIPE
        return state, None

    def ingredients(self, slots):
        state, problem = self.recipe_state()
        if problem:
            return self.say(problem)
        items = state.get("ingredients", [])
        self.send("ingredients")
        if not items:
            return self.say("This recipe doesn't list any ingredients.")
        head = f"For {state['servings']} servings of {state['title']}, you need "
        if len(items) > 12:
            return self.say(head + sentence(items[:12]) + f", plus {len(items) - 12} more. They're on your TV.")
        return self.say(head + sentence(items) + ".")

    def step_ingredients(self, slots):
        state, problem = self.cooking()
        if problem:
            return self.say(problem)
        items = state.get("stepIngredients", [])
        if not items:
            return self.say("This step doesn't use any new ingredients.")
        return self.say("For this step you need " + sentence(items) + ".")

    def ingredient_amount(self, slots):
        state, problem = self.recipe_state()
        if problem:
            return self.say(problem)
        wanted = (slots.get("item") or "").strip().lower()
        if not wanted:
            return self.ingredients(slots)
        # "how much time do I have left" can land here because "how much {item}" is a sample utterance
        if re.search(r"\b(time|longer|left|minutes?|seconds?|hours?)\b", wanted):
            return self.timer_status(slots)
        hits = find_ingredients(state.get("ingredients", []), wanted)
        if not hits:
            return self.say(f"I don't see {wanted} in {state['title']}.")
        return self.say("You need " + sentence(hits) + ".")

    def servings(self, slots):
        state, problem = self.recipe_state()
        if problem:
            return self.say(problem)
        try:
            n = int(slots.get("count") or "")
        except ValueError:
            return self.say("How many people are you cooking for?", end=False)
        lo, hi = state.get("minServings", 1), state.get("maxServings", 12)
        if not lo <= n <= hi:
            return self.say(f"I can scale from {lo} to {hi} servings.")
        self.send("servings", count=n)
        return self.say(f"Okay, {state['title']} is now scaled for {n}." if n != 1 else f"Okay, scaled for one person.")

    def start_cooking(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if state.get("screen") == "cooking":
            return self.say("You're already cooking. Say next step when you're ready.")
        if state.get("screen") != "detail":
            return self.say(NO_RECIPE)
        self.send("startCooking")
        if state.get("readAloud") or not state.get("steps"):
            return self.say(f"Let's cook {state['title']}!")
        return self.say(f"Let's cook {state['title']}! Step 1. {state['steps'][0]}")

    def find_recipe(self, slots):
        query = (slots.get("query") or "").strip()[:60]
        if not query:
            return self.say("What would you like to cook?", end=False, reprompt="For example: find paneer recipes.")
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if state.get("screen") == "cooking":
            return self.say("Finish or leave this recipe on your TV first, then ask me again.")
        self.send("search", query=query)
        return self.say(f"Okay, looking for {query} on your TV.")

    def quick_recipes(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if state.get("screen") == "cooking":
            return self.say("Finish or leave this recipe on your TV first, then ask me again.")
        minutes = min(max(5, (parse_duration(slots.get("duration")) or 20 * 60) // 60), 240)
        self.send("search", maxMinutes=minutes, query="")
        return self.say(f"Here are recipes you can make in {minutes} minutes or less, on your TV.")

    def shopping_list(self, slots):
        state, problem = self.recipe_state()
        if problem:
            return self.say(problem)
        self.send("shoppingList")
        return self.say("Scan the code on your TV with your phone to get the shopping list.")

    def fridge_scan(self, slots):
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if state.get("screen") == "cooking":
            return self.say("Finish or leave this recipe on your TV first.")
        self.send("fridgeScan")
        return self.say("Scan the code on your TV with your phone, then take one photo of your fridge. "
                        "I'll find recipes you can cook.")

    def plan_dinner(self, slots):
        """The TV runs the AWS dinner-planner agent on the ingredients of its last fridge scan (the TV holds them)."""
        state, problem = self.tv()
        if problem:
            return self.say(problem)
        if state.get("screen") == "cooking":
            return self.say("Finish or leave this recipe on your TV first, then ask me again.")
        scanned = [i for i in (state.get("scanIngredients") or []) if isinstance(i, str) and i.strip()]
        if not scanned:
            return self.say("Scan your fridge first: say 'scan my fridge'.")
        try:
            servings = int(slots.get("count") or "")
        except ValueError:
            servings = None
        if servings is not None and not 1 <= servings <= 12:
            return self.say("I can plan dinner for one to twelve people.")
        self.send("plan", servings=servings or 2)
        return self.say("Planning dinner with what you scanned. Watch your TV.")

    # ---- ask the chef ------------------------------------------------------------------------------------------

    def substitute(self, slots):
        item = (slots.get("item") or "").strip()
        if not item:
            return self.say("What are you missing?", end=False)
        return self.ask(f"I don't have {item}. What can I use instead?")

    def chef_question(self, slots):
        q = (slots.get("question") or "").strip()
        if not q:
            return self.say("What would you like to ask?", end=False)
        return self.ask(q)

    def ask(self, question):
        state, _ = self.tv() if self.device else (None, None)
        answer = self.chef(question, state)
        if not answer:
            return self.say("Sorry, I couldn't think of an answer right now. Please try again.")
        if state:
            self.send("chef", question=question[:120], text=answer[:400])
        return self.say(answer)

    # ---- built-ins ---------------------------------------------------------------------------------------------

    def help(self, slots):
        if not self.device:
            return self.say(NOT_LINKED, end=False, reprompt="What's the code on your TV?")
        return self.say(HELP, end=False, reprompt="What would you like to do?")

    def stop(self, slots):
        return self.say("Happy cooking!", end=True)

    def fallback(self, slots):
        return self.say("Sorry, I didn't get that. You can say: next step, set a timer for five minutes, "
                        "or what can I use instead of cream.", end=False if self.launched else True)


INTENTS = {
    "PairIntent": Turn.pair,
    "UnlinkIntent": Turn.unlink,
    "NextStepIntent": Turn.next_step,
    "AMAZON.NextIntent": Turn.next_step,
    "PreviousStepIntent": Turn.previous_step,
    "AMAZON.PreviousIntent": Turn.previous_step,
    "RepeatStepIntent": Turn.repeat_step,
    "AMAZON.RepeatIntent": Turn.repeat_step,
    "GoToStepIntent": Turn.go_to_step,
    "AMAZON.StartOverIntent": Turn.start_over,
    "FinishIntent": Turn.finish,
    "StartTimerIntent": Turn.start_timer,
    "AddTimeIntent": Turn.add_time,
    "TimerStatusIntent": Turn.timer_status,
    "PauseTimersIntent": Turn.pause_timers,
    "AMAZON.PauseIntent": Turn.pause_timers,
    "ResumeTimersIntent": Turn.resume_timers,
    "AMAZON.ResumeIntent": Turn.resume_timers,
    "StopTimerIntent": Turn.stop_timer,
    "IngredientsIntent": Turn.ingredients,
    "StepIngredientsIntent": Turn.step_ingredients,
    "IngredientAmountIntent": Turn.ingredient_amount,
    "ServingsIntent": Turn.servings,
    "StartCookingIntent": Turn.start_cooking,
    "FindRecipeIntent": Turn.find_recipe,
    "QuickRecipesIntent": Turn.quick_recipes,
    "ShoppingListIntent": Turn.shopping_list,
    "FridgeScanIntent": Turn.fridge_scan,
    "PlanDinnerIntent": Turn.plan_dinner,
    "SubstituteIntent": Turn.substitute,
    "ChefQuestionIntent": Turn.chef_question,
    "AMAZON.HelpIntent": Turn.help,
    "AMAZON.StopIntent": Turn.stop,
    "AMAZON.CancelIntent": Turn.stop,
    "AMAZON.NavigateHomeIntent": Turn.stop,
    "AMAZON.FallbackIntent": Turn.fallback,
}


# ---- helpers ---------------------------------------------------------------------------------------------------

DURATION_RE = re.compile(r"^P(?:(\d+)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$")


def parse_duration(value):
    """Alexa's AMAZON.DURATION is ISO 8601: PT5M, PT1H30M, PT90S. Returns whole seconds, or None."""
    m = DURATION_RE.match(value or "")
    if not m or not any(m.groups()):
        return None
    d, h, mi, s = (float(g) if g else 0.0 for g in m.groups())
    total = int(round(d * 86400 + h * 3600 + mi * 60 + s))
    return total or None


def spoken_duration(seconds, adjective=False):
    """'5 minutes', or as an adjective: 'a 5 minute timer'."""
    if adjective:
        return re.sub(r"\b(hour|minute|second)s\b", r"\1", spoken_duration(seconds))
    seconds = int(seconds)
    h, rest = divmod(seconds, 3600)
    m, s = divmod(rest, 60)
    parts = []
    if h:
        parts.append("1 hour" if h == 1 else f"{h} hours")
    if m:
        parts.append("1 minute" if m == 1 else f"{m} minutes")
    if s and not h:
        parts.append("1 second" if s == 1 else f"{s} seconds")
    return " ".join(parts) or "0 seconds"


def sentence(items):
    items = list(items)
    if len(items) <= 1:
        return "".join(items)
    return ", ".join(items[:-1]) + " and " + items[-1]


def remaining_seconds(timer, state):
    """The TV publishes remainingMs when it saves its state; running timers have ticked down since then."""
    left = timer.get("remainingMs", 0)
    if timer["status"] == "RUNNING":
        left -= state.get("_ageMs", 0)
    return max(0, (left + 999) // 1000)


def soonest_timer(state):
    active = [t for t in state.get("timers", []) if t["status"] != "COMPLETED"]
    return min(active, key=lambda t: remaining_seconds(t, state)) if active else None


WORD_RE = re.compile(r"[a-z]+")


def find_ingredients(ingredients, wanted):
    """'how much rice' -> ['300 grams basmati rice']. Plural-insensitive word match on the spoken ingredient lines."""
    def words(text):
        return {w.rstrip("s") for w in WORD_RE.findall(text.lower()) if len(w) > 2}
    want = words(wanted) - {"the", "and", "need", "much", "many", "for", "some"}
    if not want:
        return []
    return [line for line in ingredients if want <= words(line)] or \
           [line for line in ingredients if want & words(line)]


def ask_chef(question, state):
    """Short spoken answer from Amazon Nova on Bedrock, grounded in the recipe on the TV."""
    global _bedrock
    if _bedrock is None:
        _bedrock = boto3.client("bedrock-runtime", region_name=BEDROCK_REGION,
                                config=Config(connect_timeout=2, read_timeout=5, retries={"max_attempts": 1}))
    context = "The user isn't following a recipe right now."
    if state and state.get("recipeId"):
        context = (f"The user is making {state['title']} for {state.get('servings')} servings. "
                   f"Ingredients: {'; '.join(state.get('ingredients', []))}.")
        steps, step = state.get("steps") or [], state.get("step") or 0
        if state.get("screen") == "cooking" and 0 <= step < len(steps):
            context += f" They are on step {step + 1}: {steps[step]}"
    system = ("You are Cook Along's chef, answering out loud through Alexa while someone cooks at home. "
              "Reply in one or two short spoken sentences, under 45 words, plain words only: no lists, markdown, "
              "symbols or emoji. Use the recipe context. Prefer swaps from the dish's own cuisine (for an Indian dal, "
              "another dal such as moong or masoor) and give quantities that fit the recipe. If asked about "
              "allergies, say to check labels and never call a swap allergy-safe. If the question isn't about "
              "cooking or food, say you can only help with cooking.\n\n" + context)
    started = time.monotonic()
    for model in CHEF_MODELS:
        if model in _dead_models:
            continue
        if time.monotonic() - started > CHEF_BUDGET_SECONDS:
            break   # Alexa gives up on a skill after 8 seconds
        try:
            out = _bedrock.converse(
                modelId=model, system=[{"text": system}],
                messages=[{"role": "user", "content": [{"text": question}]}],
                inferenceConfig={"maxTokens": 200, "temperature": 0.3},
            )
            text = " ".join(b.get("text", "") for b in out["output"]["message"]["content"]).strip()
            return re.sub(r"[*#_`]", "", text)
        except ClientError as e:
            code = e.response.get("Error", {}).get("Code", "")
            print("Chef model failed", model, code, e)
            if code in ("AccessDeniedException", "ResourceNotFoundException", "ValidationException"):
                _dead_models.add(model)
        except BotoCoreError as e:
            print("Chef model failed", model, e)
    return None

