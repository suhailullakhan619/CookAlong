"""Unit tests for the dinner planner's non-model logic (no AWS calls): python -m unittest test_dinner_agent
Needs strands-agents importable (pip install strands-agents) because dinner_agent builds its tools at import."""
import unittest

import dinner_agent as d


class FakeMeal:
    def __init__(self, rid, why="fits", swaps=None):
        self.recipe_id, self.why, self.swaps = rid, why, swaps or []


class FakePlan:
    def __init__(self, meals, summary="Tonight: two meals.", tip=""):
        self.meals, self.summary, self.tip = meals, summary, tip


CATALOG = [
    {"id": "paneer-masala", "title": "Paneer Masala", "totalMinutes": 35, "dietaryTags": ["vegetarian"],
     "categories": ["indian"], "ingredientNames": ["paneer, cubed", "2 onions, chopped", "tomato puree", "butter", "salt"]},
    {"id": "raita", "title": "Raita", "totalMinutes": 10, "dietaryTags": ["vegetarian"],
     "categories": ["indian", "salad"], "ingredientNames": ["yogurt", "onion", "cumin seeds"]},
    {"id": "chicken-curry", "title": "Chicken Curry", "totalMinutes": 40, "dietaryTags": [],
     "categories": ["indian"], "ingredientNames": ["chicken thighs", "onion", "tomato"]},
]


class Matching(unittest.TestCase):
    def test_keywords_fold_synonyms_plurals_and_descriptors(self):
        self.assertEqual(d.keywords("2 Red onions, finely chopped"), {"onion"})
        self.assertEqual(d.keywords("capsicum"), {"pepper"})
        self.assertEqual(d.keywords("Curd"), {"yogurt"})
        self.assertEqual(d.keywords("tomatoes (about 360 g)"), {"tomato"})
        self.assertEqual(d.keywords("brinjal"), d.keywords("eggplant"))

    def test_staples(self):
        for n in ("salt", "olive oil", "cooking oil", "water", "sugar"):
            self.assertTrue(d.is_staple(n), n)
        self.assertFalse(d.is_staple("coconut milk"))

    def test_variants_do_not_match_the_plain_ingredient(self):
        mine = [d.keywords("milk")]
        self.assertTrue(d.have_it("whole milk", mine))
        self.assertFalse(d.have_it("coconut milk", mine))
        self.assertTrue(d.have_it("chicken thighs", [d.keywords("chicken")]))

    def test_match_ignores_staples(self):
        have, missing = d.match(CATALOG[0]["ingredientNames"], [d.keywords("paneer"), d.keywords("onion")])
        self.assertEqual(have, ["paneer, cubed", "2 onions, chopped"])
        self.assertEqual(missing, ["tomato puree", "butter"])  # salt is a staple: never "missing"


class Quantities(unittest.TestCase):
    def test_kitchen_rounding(self):
        self.assertEqual(d.quantity(0.8, "cup", 1), "1 cup")
        self.assertEqual(d.quantity(1.6, "tbsp", 1), "1.5 tbsp")
        self.assertEqual(d.quantity(402.0, "g", 1), "400 g")
        self.assertEqual(d.quantity(None, "", 2), "")
        self.assertEqual(d.quantity(0.1, "tsp", 1), "0.5 tsp")


class Finalize(unittest.TestCase):
    def setUp(self):
        d._catalog.update(at=9e18, items=CATALOG)
        self._full = d.full_recipe
        d.full_recipe = lambda rid: None

    def tearDown(self):
        d.full_recipe = self._full
        d._catalog.update(at=0.0, items=[])

    def test_invented_recipes_are_dropped_and_facts_come_from_the_catalog(self):
        plan = FakePlan([FakeMeal("paneer-masala"), FakeMeal("made-up-dish")])
        out = d.finalize(plan, ["paneer", "onion"], 4, "", 0)
        self.assertEqual([m["recipeId"] for m in out["meals"]], ["paneer-masala"])
        self.assertEqual(out["meals"][0]["minutes"], 35)
        self.assertEqual(out["meals"][0]["missing"], ["tomato puree", "butter"])

    def test_diet_and_time_limits_are_enforced_even_if_the_model_ignores_them(self):
        plan = FakePlan([FakeMeal("chicken-curry"), FakeMeal("paneer-masala")])
        self.assertEqual([m["recipeId"] for m in d.finalize(plan, ["onion"], 2, "vegetarian", 0)["meals"]], ["paneer-masala"])
        self.assertEqual([m["recipeId"] for m in d.finalize(plan, ["onion"], 2, "", 36)["meals"]], ["paneer-masala"])

    def test_no_valid_meal_gives_a_helpful_message(self):
        out = d.finalize(FakePlan([FakeMeal("nope")]), ["x"], 2, "", 0)
        self.assertEqual(out["meals"], [])
        self.assertIn("couldn't find", out["summary"])


class Requests(unittest.TestCase):
    DEVICE = "a" * 32

    def test_valid_request(self):
        got = d.parse({"device": self.DEVICE, "ingredients": [" paneer ", "", "onion"], "servings": 4, "diet": "Vegan", "maxMinutes": 30})
        self.assertEqual(got, (["paneer", "onion"], 4, "vegan", 30, self.DEVICE))

    def test_bad_requests(self):
        bad = [
            {"ingredients": ["x"]},                                   # no device
            {"device": self.DEVICE},                                   # nothing to cook with
            {"device": self.DEVICE, "ingredients": ["x"], "servings": 99},
            {"device": self.DEVICE, "ingredients": ["x"], "diet": "carnivore"},
            {"device": self.DEVICE, "ingredients": ["x"], "maxMinutes": 9999},
            {"device": self.DEVICE, "ingredients": "paneer"},
        ]
        for body in bad:
            with self.assertRaises(ValueError, msg=str(body)):
                d.parse(body)

    def test_ingredient_list_is_capped(self):
        got = d.parse({"device": self.DEVICE, "ingredients": [f"item{i}" for i in range(80)]})
        self.assertEqual(len(got[0]), 30)

    def test_handler_routes(self):
        ev = lambda m, p, b="": {"requestContext": {"http": {"method": m}}, "rawPath": p, "body": b}
        self.assertEqual(d.handler(ev("GET", "/health"), None)["statusCode"], 200)
        self.assertEqual(d.handler(ev("GET", "/nope"), None)["statusCode"], 404)
        self.assertEqual(d.handler(ev("POST", "/plan", "not json"), None)["statusCode"], 400)
        self.assertEqual(d.handler(ev("POST", "/plan", "{}"), None)["statusCode"], 400)


class TrustedSummary(unittest.TestCase):
    def test_keeps_a_consistent_model_sentence(self):
        text = "Tonight, enjoy Paneer Masala. Pick up a few spices."
        self.assertEqual(d.trusted_summary(text, ["Paneer Masala"], 3), text)

    def test_replaces_a_false_nothing_to_buy_claim(self):
        out = d.trusted_summary("Enjoy Jeera Rice and Malai Mix Veg. No shopping needed.", ["Jeera Rice", "Malai Mix Veg"], 9)
        self.assertEqual(out, "Tonight: Jeera Rice and Malai Mix Veg. You'll need to pick up 9 items; the list is on your screen.")

    def test_nothing_to_buy_claim_is_fine_when_the_list_is_empty(self):
        text = "You have everything you need for Jeera Rice."
        self.assertEqual(d.trusted_summary(text, ["Jeera Rice"], 0), text)

    def test_numbers_in_the_model_text_are_not_trusted(self):
        out = d.trusted_summary("Ready in 45 minutes: Dal.", ["Dal"], 1)
        self.assertEqual(out, "Tonight: Dal. You'll need to pick up 1 item; the list is on your screen.")

    def test_empty_model_text_falls_back_and_three_titles_read_well(self):
        out = d.trusted_summary("", ["A", "B", "C"], 0)
        self.assertEqual(out, "Tonight: A, B and C. You have everything you need.")


class DailyCap(unittest.TestCase):
    class FakeTable:
        def __init__(self, fail=False):
            self.n, self.fail, self.keys = 0, fail, []

        def update_item(self, **kw):
            if self.fail:
                raise RuntimeError("dynamo down")
            self.n += 1
            self.keys.append(kw["Key"]["pk"])
            return {"Attributes": {"n": self.n}}

    def setUp(self):
        self._ddb = d.ddb

    def tearDown(self):
        d.ddb = self._ddb

    def use(self, table):
        d.ddb = lambda: type("R", (), {"Table": lambda self, name: table})()

    def test_allows_up_to_the_cap_then_refuses(self):
        t = self.FakeTable()
        self.use(t)
        self.assertEqual([d.within_daily_cap(3) for _ in range(5)], [True, True, True, False, False])
        self.assertTrue(t.keys[0].startswith("cap#plan#20"))

    def test_fails_open_when_dynamodb_errors(self):
        self.use(self.FakeTable(fail=True))
        self.assertTrue(d.within_daily_cap(1))

    def test_handler_returns_429_when_the_daily_cap_is_reached(self):
        self.use(self.FakeTable())
        old = d.PLAN_DAILY_CAP
        d.PLAN_DAILY_CAP = 0
        try:
            body = '{"device": "%s", "ingredients": ["paneer"]}' % ("a" * 32)
            out = d.handler({"requestContext": {"http": {"method": "POST"}}, "rawPath": "/plan", "body": body}, None)
        finally:
            d.PLAN_DAILY_CAP = old
        self.assertEqual(out["statusCode"], 429)
        self.assertIn("tomorrow", out["body"])


if __name__ == "__main__":
    unittest.main()
