"""Unit tests for the Alexa skill logic (no AWS needed): python -m unittest discover backend"""
import unittest

import alexa_skill
from alexa_skill import find_ingredients, handler, parse_duration, spoken_duration


class FakeStore:
    def __init__(self, linked=True, state=None, age=1000):
        self.users = {"amzn1.user": "tv-1"} if linked else {}
        self.codes = {"482157": "tv-1"}
        self.state, self.age = state, age
        self.pushed = []

    def device_for_user(self, user):
        return self.users.get(user)

    def claim_pair_code(self, code, user):
        device = self.codes.pop(code, None)
        if device:
            self.users[user] = device
        return device

    def unlink(self, device):
        self.users = {u: d for u, d in self.users.items() if d != device}

    def get_state(self, device):
        return (dict(self.state), self.age) if self.state is not None else (None, None)

    def push(self, device, command):
        self.pushed.append(command)


COOKING = {
    "screen": "cooking", "recipeId": "dal-tadka", "title": "Dal Tadka", "servings": 4, "minServings": 1,
    "maxServings": 12, "step": 1, "stepCount": 3, "readAloud": False,
    "steps": ["Rinse the dal.", "Boil the dal for 20 minutes.", "Fry the tadka and pour it over."],
    "stepTimerSeconds": 1200, "stepTimerLabel": "Dal",
    "ingredients": ["200 grams toor dal", "1 teaspoon cumin seeds", "2 tablespoons ghee", "salt, to taste"],
    "stepIngredients": ["200 grams toor dal"],
    "timers": [],
}


def intent(name, **slots):
    return {
        "version": "1.0",
        "session": {"new": True, "application": {"applicationId": "skill"}, "user": {"userId": "amzn1.user"}},
        "context": {"System": {"application": {"applicationId": "skill"}, "user": {"userId": "amzn1.user"}}},
        "request": {"type": "IntentRequest", "intent": {"name": name, "slots": {k: {"name": k, "value": v} for k, v in slots.items()}}},
    }


def speech(resp):
    return resp["response"]["outputSpeech"]["text"]


class SkillTest(unittest.TestCase):

    def run_intent(self, store, name, chef=None, **slots):
        return handler(intent(name, **slots), None, store=store, chef=chef or (lambda q, s: "Use thick yoghurt."))

    def test_not_linked_explains_pairing(self):
        resp = self.run_intent(FakeStore(linked=False), "NextStepIntent")
        self.assertIn("Connect Alexa", speech(resp))

    def test_pairing_links_and_notifies_tv(self):
        store = FakeStore(linked=False)
        resp = self.run_intent(store, "PairIntent", code="482157")
        self.assertIn("connected", speech(resp))
        self.assertEqual(store.users["amzn1.user"], "tv-1")
        self.assertEqual(store.pushed, [{"type": "linked"}])

    def test_bad_code_keeps_session_open(self):
        resp = self.run_intent(FakeStore(linked=False), "PairIntent", code="111111")
        self.assertIn("didn't work", speech(resp))
        self.assertFalse(resp["response"]["shouldEndSession"])

    def test_stale_tv_is_reported(self):
        resp = self.run_intent(FakeStore(state=COOKING, age=10 * 60_000), "NextStepIntent")
        self.assertIn("can't reach", speech(resp))

    def test_next_step_reads_text_when_tv_is_quiet(self):
        store = FakeStore(state=COOKING)
        resp = self.run_intent(store, "NextStepIntent")
        self.assertEqual(store.pushed, [{"type": "next"}])
        self.assertEqual(speech(resp), "Step 3 of 3. Fry the tadka and pour it over.")
        self.assertTrue(resp["response"]["shouldEndSession"])

    def test_next_step_is_short_when_tv_reads_aloud(self):
        resp = self.run_intent(FakeStore(state=dict(COOKING, readAloud=True)), "AMAZON.NextIntent")
        self.assertEqual(speech(resp), "Step 3.")

    def test_last_step_does_not_advance(self):
        store = FakeStore(state=dict(COOKING, step=2))
        resp = self.run_intent(store, "NextStepIntent")
        self.assertIn("last step", speech(resp))
        self.assertEqual(store.pushed, [])

    def test_not_cooking(self):
        resp = self.run_intent(FakeStore(state=dict(COOKING, screen="home", recipeId=None)), "NextStepIntent")
        self.assertIn("not cooking", speech(resp))

    def test_go_to_step_validates_range(self):
        store = FakeStore(state=COOKING)
        self.assertIn("3 steps", speech(self.run_intent(store, "GoToStepIntent", number="9")))
        self.run_intent(store, "GoToStepIntent", number="1")
        self.assertEqual(store.pushed, [{"type": "goto", "step": 0}])

    def test_timer_with_duration(self):
        store = FakeStore(state=COOKING)
        resp = self.run_intent(store, "StartTimerIntent", duration="PT5M")
        self.assertEqual(store.pushed, [{"type": "timer", "seconds": 300}])
        self.assertEqual(speech(resp), "Starting a 5 minute timer on your TV.")

    def test_timer_without_duration_uses_step_timer(self):
        store = FakeStore(state=COOKING)
        resp = self.run_intent(store, "StartTimerIntent")
        self.assertEqual(store.pushed, [{"type": "startStepTimer"}])
        self.assertIn("Dal timer for 20 minutes", speech(resp))

    def test_timer_outside_cooking_points_to_alexa_timer(self):
        resp = self.run_intent(FakeStore(state=dict(COOKING, screen="home")), "StartTimerIntent", duration="PT5M")
        self.assertIn("Alexa, set a timer", speech(resp))

    def test_timer_limit(self):
        timers = [{"id": str(i), "label": f"T{i}", "status": "RUNNING", "remainingMs": 60_000} for i in range(4)]
        resp = self.run_intent(FakeStore(state=dict(COOKING, timers=timers)), "StartTimerIntent", duration="PT1M")
        self.assertIn("already have 4", speech(resp))

    def test_timer_status_accounts_for_state_age(self):
        timers = [
            {"id": "a", "label": "Dal", "status": "RUNNING", "remainingMs": 300_000},
            {"id": "b", "label": "Rice", "status": "PAUSED", "remainingMs": 120_000},
        ]
        resp = self.run_intent(FakeStore(state=dict(COOKING, timers=timers), age=60_000), "TimerStatusIntent")
        self.assertEqual(speech(resp), "The Rice timer is paused with 2 minutes left and the Dal timer has 4 minutes left.")

    def test_add_time_targets_soonest_timer(self):
        timers = [
            {"id": "a", "label": "Dal", "status": "RUNNING", "remainingMs": 300_000},
            {"id": "b", "label": "Rice", "status": "RUNNING", "remainingMs": 30_000},
        ]
        store = FakeStore(state=dict(COOKING, timers=timers))
        resp = self.run_intent(store, "AddTimeIntent", duration="PT2M")
        self.assertEqual(store.pushed, [{"type": "addTime", "seconds": 120, "timerId": "b"}])
        self.assertIn("Rice", speech(resp))

    def test_stop_timer_dismisses_finished_first(self):
        timers = [{"id": "a", "label": "Dal", "status": "COMPLETED", "remainingMs": 0}]
        store = FakeStore(state=dict(COOKING, timers=timers))
        self.run_intent(store, "StopTimerIntent")
        self.assertEqual(store.pushed, [{"type": "dismissFinished"}])

    def test_ingredient_amount(self):
        resp = self.run_intent(FakeStore(state=COOKING), "IngredientAmountIntent", item="dal")
        self.assertEqual(speech(resp), "You need 200 grams toor dal.")

    def test_ingredient_missing(self):
        resp = self.run_intent(FakeStore(state=COOKING), "IngredientAmountIntent", item="paneer")
        self.assertIn("don't see paneer", speech(resp))

    def test_servings_range(self):
        store = FakeStore(state=COOKING)
        self.assertIn("1 to 12", speech(self.run_intent(store, "ServingsIntent", count="40")))
        self.run_intent(store, "ServingsIntent", count="6")
        self.assertEqual(store.pushed, [{"type": "servings", "count": 6}])

    def test_start_cooking_from_detail(self):
        store = FakeStore(state=dict(COOKING, screen="detail"))
        resp = self.run_intent(store, "StartCookingIntent")
        self.assertEqual(store.pushed, [{"type": "startCooking"}])
        self.assertIn("Step 1. Rinse the dal.", speech(resp))

    def test_find_recipe(self):
        store = FakeStore(state=dict(COOKING, screen="home", recipeId=None))
        self.run_intent(store, "FindRecipeIntent", query="paneer")
        self.assertEqual(store.pushed, [{"type": "search", "query": "paneer"}])

    def test_substitute_goes_to_chef_and_tv(self):
        store = FakeStore(state=COOKING)
        asked = []
        resp = self.run_intent(store, "SubstituteIntent", chef=lambda q, s: asked.append((q, s["title"])) or "Use thick yoghurt.", item="cream")
        self.assertEqual(speech(resp), "Use thick yoghurt.")
        self.assertEqual(asked, [("I don't have cream. What can I use instead?", "Dal Tadka")])
        self.assertEqual(store.pushed[0]["type"], "chef")

    def test_chef_failure_is_spoken(self):
        resp = self.run_intent(FakeStore(state=COOKING), "ChefQuestionIntent", chef=lambda q, s: None, question="how do I know the oil is hot")
        self.assertIn("couldn't think", speech(resp))

    def test_launch_keeps_session_open_and_later_turns_too(self):
        store = FakeStore(state=COOKING)
        event = intent("x")
        event["request"] = {"type": "LaunchRequest"}
        resp = handler(event, None, store=store)
        self.assertFalse(resp["response"]["shouldEndSession"])
        self.assertTrue(resp["sessionAttributes"]["launched"])
        follow = intent("NextStepIntent")
        follow["session"]["attributes"] = resp["sessionAttributes"]
        self.assertFalse(handler(follow, None, store=store)["response"]["shouldEndSession"])

    def test_wrong_skill_id_is_rejected(self):
        alexa_skill.SKILL_ID = "other"
        try:
            with self.assertRaises(PermissionError):
                self.run_intent(FakeStore(state=COOKING), "NextStepIntent")
        finally:
            alexa_skill.SKILL_ID = ""


class EdgeCaseTest(unittest.TestCase):
    run_intent = SkillTest.run_intent

    def test_broken_tv_state_never_crashes(self):
        resp = self.run_intent(FakeStore(state={"screen": "cooking"}), "NextStepIntent")
        self.assertIn("something went wrong", speech(resp))
        self.assertTrue(resp["response"]["shouldEndSession"])

    def test_quick_recipes_short_duration_speaks_clamped_minutes(self):
        store = FakeStore(state=dict(COOKING, screen="home"))
        resp = self.run_intent(store, "QuickRecipesIntent", duration="PT30S")
        self.assertEqual(store.pushed, [{"type": "search", "maxMinutes": 5, "query": ""}])
        self.assertIn("5 minutes", speech(resp))

    def test_how_much_time_left_phrase_goes_to_timers(self):
        resp = self.run_intent(FakeStore(state=COOKING), "IngredientAmountIntent", item="time do i have left")
        self.assertIn("any timers", speech(resp))

    def test_step_timer_already_running_is_not_started_twice(self):
        timers = [{"id": "t1", "label": "Dal", "status": "RUNNING", "remainingMs": 60000}]
        store = FakeStore(state=dict(COOKING, timers=timers))
        resp = self.run_intent(store, "StartTimerIntent")
        self.assertIn("already going", speech(resp))
        self.assertEqual(store.pushed, [])

    def test_find_recipe_does_not_claim_results(self):
        resp = self.run_intent(FakeStore(state=dict(COOKING, screen="home")), "FindRecipeIntent", query="zzz")
        self.assertNotIn("found", speech(resp))

    def test_huge_query_is_not_echoed_in_full(self):
        store = FakeStore(state=dict(COOKING, screen="home"))
        resp = self.run_intent(store, "FindRecipeIntent", query="a" * 5000)
        self.assertLess(len(speech(resp)), 200)
        self.assertEqual(len(store.pushed[0]["query"]), 60)

    def test_null_intent_does_not_crash(self):
        event = intent("NextStepIntent")
        event["request"]["intent"] = None
        resp = handler(event, None, store=FakeStore(state=COOKING))
        self.assertIn("didn't get that", speech(resp))

    def test_numeric_slot_value_is_accepted(self):
        store = FakeStore(linked=False)
        resp = self.run_intent(store, "PairIntent", code=482157)
        self.assertIn("connected", speech(resp))

    def test_chef_stops_trying_models_when_out_of_time(self):
        calls = []

        class Slow:
            def converse(self, **kw):
                calls.append(kw["modelId"])
                alexa_skill.time.sleep(0.05)
                raise alexa_skill.BotoCoreError()

        old = (alexa_skill._bedrock, alexa_skill.CHEF_BUDGET_SECONDS, alexa_skill._dead_models.copy())
        alexa_skill._bedrock, alexa_skill.CHEF_BUDGET_SECONDS = Slow(), 0.01
        try:
            self.assertIsNone(alexa_skill.ask_chef("why", None))
        finally:
            alexa_skill._bedrock, alexa_skill.CHEF_BUDGET_SECONDS = old[0], old[1]
        self.assertEqual(len(calls), 1)

    SCANNED = {"screen": "scan", "scanIngredients": ["paneer", "onion", "tomato"], "timers": []}

    def test_plan_dinner_pushes_plan_with_servings(self):
        store = FakeStore(state=self.SCANNED)
        resp = self.run_intent(store, "PlanDinnerIntent", count="4")
        self.assertEqual(speech(resp), "Planning dinner with what you scanned. Watch your TV.")
        self.assertEqual(store.pushed, [{"type": "plan", "servings": 4}])

    def test_plan_dinner_defaults_to_two(self):
        store = FakeStore(state=self.SCANNED)
        self.run_intent(store, "PlanDinnerIntent")
        self.assertEqual(store.pushed, [{"type": "plan", "servings": 2}])

    def test_plan_dinner_needs_a_scan(self):
        for state in ({"screen": "home", "timers": []}, {"screen": "home", "scanIngredients": []},
                      {"screen": "home", "scanIngredients": None}, {"screen": "home", "scanIngredients": ["", "  "]}):
            store = FakeStore(state=state)
            resp = self.run_intent(store, "PlanDinnerIntent", count="4")
            self.assertEqual(speech(resp), "Scan your fridge first: say 'scan my fridge'.")
            self.assertEqual(store.pushed, [])

    def test_plan_dinner_needs_a_linked_live_tv(self):
        self.assertIn("Connect Alexa", speech(self.run_intent(FakeStore(linked=False), "PlanDinnerIntent")))
        resp = self.run_intent(FakeStore(state=self.SCANNED, age=10 * 60_000), "PlanDinnerIntent")
        self.assertIn("can't reach", speech(resp))

    def test_plan_dinner_not_while_cooking(self):
        store = FakeStore(state=dict(COOKING, scanIngredients=["paneer"]))
        self.assertIn("Finish or leave", speech(self.run_intent(store, "PlanDinnerIntent")))
        self.assertEqual(store.pushed, [])

    def test_plan_dinner_rejects_silly_counts(self):
        store = FakeStore(state=self.SCANNED)
        for n in ("0", "13", "-2"):
            self.assertIn("one to twelve", speech(self.run_intent(store, "PlanDinnerIntent", count=n)))
        self.assertEqual(store.pushed, [])

    def test_every_model_intent_has_a_handler_and_vice_versa(self):
        import json, os
        path = os.path.join(os.path.dirname(__file__), "..", "alexa", "skill-package", "interactionModels", "custom", "en-US.json")
        with open(path, encoding="utf-8") as f:
            model = {i["name"] for i in json.load(f)["interactionModel"]["languageModel"]["intents"]}
        handled = set(alexa_skill.INTENTS)
        self.assertEqual(model - handled, set(), "intent in the model without a handler")
        self.assertEqual(handled - model, set(), "handler without an intent in the model")


class HelperTest(unittest.TestCase):
    def test_parse_duration(self):
        self.assertEqual(parse_duration("PT5M"), 300)
        self.assertEqual(parse_duration("PT1H30M"), 5400)
        self.assertEqual(parse_duration("PT90S"), 90)
        self.assertEqual(parse_duration("PT0.5H"), 1800)
        self.assertIsNone(parse_duration("PT?M"))
        self.assertIsNone(parse_duration(None))
        self.assertIsNone(parse_duration("P"))

    def test_spoken_duration(self):
        self.assertEqual(spoken_duration(300), "5 minutes")
        self.assertEqual(spoken_duration(3660), "1 hour 1 minute")
        self.assertEqual(spoken_duration(61), "1 minute 1 second")
        self.assertEqual(spoken_duration(5400, adjective=True), "1 hour 30 minute")

    def test_find_ingredients_plural_insensitive(self):
        lines = ["3 eggs", "2 tomatoes", "1 egg yolk"]
        self.assertEqual(find_ingredients(lines, "egg"), ["3 eggs", "1 egg yolk"])
        self.assertEqual(find_ingredients(lines, "tomatoes"), ["2 tomatoes"])


if __name__ == "__main__":
    unittest.main()
