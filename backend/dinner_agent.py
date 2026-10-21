"""CookAlong dinner planner: a Strands agent on Amazon Nova (Amazon Bedrock) with tools over our recipe database.

Request (Lambda Function URL):
    POST /plan  {"device": "<tv id>", "ingredients": ["paneer", "onion"], "servings": 4, "diet": "vegetarian",
                 "maxMinutes": 45}                       ingredients can instead be "scanCode": "K7Q2XM" (a fridge scan)
    GET  /health

Flow: validate -> the agent calls tools (find_recipes, get_recipe, suggest_swap, shopping_list) -> it answers with a
structured DinnerPlan -> the server re-checks every fact the TV will show (recipe exists, minutes, missing items,
shopping list) from DynamoDB, so the model only chooses and explains; it never invents a recipe.

AWS services: Amazon Bedrock (Nova Pro via the Converse API, plus a Bedrock Guardrail), AWS Lambda (Strands runs here),
Amazon DynamoDB (recipes, fridge-scan results, per-TV rate limit).
"""
import json
import math
import os
import re
import time

import boto3
from pydantic import BaseModel, Field
from strands import Agent, tool
from strands.models import BedrockModel

REGION = os.environ.get("BEDROCK_REGION", os.environ.get("AWS_REGION", "ap-south-1"))
MODEL_ID = os.environ.get("AGENT_MODEL_ID", "apac.amazon.nova-pro-v1:0")
GUARDRAIL_ID = os.environ.get("GUARDRAIL_ID", "")
GUARDRAIL_VERSION = os.environ.get("GUARDRAIL_VERSION", "1")
RECIPES_TABLE = os.environ.get("RECIPES_TABLE", "cookalong-recipes")
SCANS_TABLE = os.environ.get("TABLE_NAME", "cookalong-scans")
TV_TABLE = os.environ.get("TV_TABLE", "cookalong-tv")
PLANS_PER_HOUR = 12
PLAN_DAILY_CAP = int(os.environ.get("PLAN_DAILY_CAP", "300"))   # all TVs together, per UTC day
DIETS = {"", "vegetarian", "vegan", "gluten-free", "dairy-free"}
DEVICE_RE = re.compile(r"^[A-Za-z0-9-]{20,64}$")
CODE_RE = re.compile(r"^[A-Z2-9]{6}$")

_ddb = None
_catalog = {"at": 0.0, "items": []}
TRACE = []   # tool calls of the request in flight, returned to the TV so judges (and users) can see what the agent did


def ddb():
    global _ddb
    _ddb = _ddb or boto3.resource("dynamodb", region_name=REGION)
    return _ddb


# ---- ingredient matching (same idea as the TV app's RecipeMatcher, in Python) -------------------------------------

FILLERS = {
    "fresh", "ripe", "large", "small", "medium", "red", "green", "yellow", "white", "black", "plain", "cold", "ground",
    "dried", "whole", "chopped", "sliced", "diced", "minced", "grated", "crushed", "canned", "frozen", "cooked", "finely",
    "thinly", "optional", "and", "or", "of", "the", "a", "to", "for", "in", "extra", "virgin", "raw", "peeled", "boneless",
    "skinless", "halved", "drained", "beaten", "juiced", "neutral", "mixed", "hot", "ground", "powder", "about", "g", "ml",
}
SYNONYMS = {
    "capsicum": "pepper", "bell": "pepper", "brinjal": "aubergine", "eggplant": "aubergine", "curd": "yogurt",
    "yoghurt": "yogurt", "dahi": "yogurt", "coriander": "cilantro", "maida": "flour", "atta": "flour", "chilli": "chili",
    "chillies": "chili", "chilies": "chili", "bhindi": "okra", "ladyfinger": "okra", "jeera": "cumin", "haldi": "turmeric",
    "scallion": "onion", "scallions": "onion", "aloo": "potato", "gobi": "cauliflower", "palak": "spinach",
    "garbanzo": "chickpea", "chana": "chickpea", "dal": "lentil", "daal": "lentil", "masoor": "lentil", "moong": "lentil",
}
KINDS = {"drink", "sweet", "snack", "salad", "baking", "dessert", "side", "sauce"}  # not a dinner unless nothing else fits
VARIANTS = {"coconut", "almond", "soy", "peanut", "oat", "sweet", "cream", "rice", "wine", "vinegar", "paste", "sauce"}


def keywords(text):
    """'2 Red onions, finely chopped' -> {'onion'}; synonyms folded, plurals stemmed, descriptors dropped."""
    text = re.sub(r"\([^)]*\)", " ", text.lower())
    out = set()
    for w in re.findall(r"[a-z]+", text.split(",")[0]):
        if w in FILLERS:
            continue
        if len(w) > 4 and w.endswith("ies"):
            w = w[:-3] + "y"
        elif len(w) > 4 and w.endswith("oes"):
            w = w[:-2]
        elif len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
            w = w[:-1]
        out.add(SYNONYMS.get(w, w))
    return out


def is_staple(name):
    k = keywords(name)
    return bool(k) and (k <= {"salt", "water", "oil", "sugar", "ice"} or ("oil" in k and len(k) <= 2))


def have_it(ingredient_name, mine):
    """True if one of the user's ingredient keyword-sets covers this recipe ingredient."""
    need = keywords(ingredient_name)
    if not need:
        return True
    for m in mine:
        if m and m <= need and not ((need & VARIANTS) - m):
            return True
    return False


def match(recipe_ingredient_names, mine):
    counted = [n for n in recipe_ingredient_names if not is_staple(n)]
    have = [n for n in counted if have_it(n, mine)]
    missing = [n for n in counted if n not in have]
    return have, missing


def rarity(m, items, cache={}):
    """How special one of the cook's ingredients is: log(recipes / recipes that use it). Paneer > onion."""
    key = (id(items), frozenset(m))
    if key not in cache:
        df = sum(1 for r in items if any(m <= keywords(n) for n in r.get("ingredientNames", [])))
        cache[key] = math.log((len(items) + 1) / (df + 1)) + 0.2
    return cache[key]


def used_weight(r, mine, items):
    """Weight of the cook's ingredients this recipe actually uses (what makes it a good fit for tonight)."""
    names = [keywords(n) for n in r.get("ingredientNames", [])]
    return sum(rarity(m, items) for m in mine if any(m <= need for need in names))


def catalog():
    """All recipes' flat fields, cached for 5 minutes in a warm Lambda."""
    if time.time() - _catalog["at"] < 300 and _catalog["items"]:
        return _catalog["items"]
    table, items, kw = ddb().Table(RECIPES_TABLE), [], {}
    while True:
        page = table.scan(
            ProjectionExpression="id, title, totalMinutes, dietaryTags, categories, ingredientNames, #s",
            ExpressionAttributeNames={"#s": "source"}, **kw,
        )
        items += page["Items"]
        if "LastEvaluatedKey" not in page:
            break
        kw["ExclusiveStartKey"] = page["LastEvaluatedKey"]
    _catalog.update(at=time.time(), items=items)
    return items


def full_recipe(recipe_id):
    item = ddb().Table(RECIPES_TABLE).get_item(Key={"id": recipe_id}).get("Item")
    return json.loads(item["recipe"]) if item else None


# ---- agent tools ---------------------------------------------------------------------------------------------------

SWAPS = {
    "cream": "full-fat yogurt or blended soaked cashews", "butter": "ghee or neutral oil", "ghee": "butter or neutral oil",
    "yogurt": "buttermilk or thick coconut milk", "paneer": "firm tofu", "milk": "any plant milk or diluted cream",
    "egg": "a mashed banana or 3 tbsp yogurt (for baking)", "flour": "any other flour at the same weight, results vary",
    "lemon": "lime or a splash of vinegar", "cilantro": "fresh parsley or mint", "parsley": "cilantro or celery leaves",
    "onion": "shallots or a pinch of asafoetida", "tomato": "tomato puree or a little tamarind",
    "garlic": "garlic powder (1/4 tsp per clove)", "ginger": "1/4 tsp dry ginger per tbsp fresh",
    "chicken": "paneer, tofu or chickpeas", "rice": "quinoa, millet or cauliflower rice", "lentil": "another dal such as moong or masoor",
    "coconut": "cashew paste with a little water", "sugar": "jaggery or honey", "pepper": "any other crunchy vegetable",
}


@tool
def find_recipes(ingredients: list[str], max_minutes: int = 0, diet: str = "", limit: int = 8) -> list[dict]:
    """Find recipes in the CookAlong library that the cook can make with the ingredients they have.

    Args:
        ingredients: What the cook has, e.g. ["paneer", "onion", "capsicum"].
        max_minutes: Only recipes ready within this many minutes (0 means no limit).
        diet: One of vegetarian, vegan, gluten-free, dairy-free, or empty for any.
        limit: How many recipes to return, best first.

    Returns:
        Recipes with id, title, minutes, have (count), total (main ingredients) and missing (names), best first.
    """
    TRACE.append({"tool": "find_recipes", "input": {"ingredients": ingredients, "max_minutes": max_minutes, "diet": diet}})
    mine = [keywords(i) for i in ingredients if keywords(i)]
    rows, items = [], catalog()
    for r in items:
        if max_minutes and int(r.get("totalMinutes", 0)) > max_minutes:
            continue
        if diet and diet not in r.get("dietaryTags", []):
            continue
        have, missing = match(r.get("ingredientNames", []), mine)
        if not have:
            continue
        total = len(have) + len(missing)
        rows.append({"id": r["id"], "title": r["title"], "minutes": int(r.get("totalMinutes", 0)),
                     "have": len(have), "total": total, "missing": missing[:6], "diet": r.get("dietaryTags", []),
                     "type": [c for c in r.get("categories", []) if c in KINDS][:1] or ["main"],
                     "fit": round(used_weight(r, mine, items) - 0.25 * len(missing)
                                  - (3.0 if any(c in KINDS for c in r.get("categories", [])) else 0), 2)})
    # Best fit first: uses the cook's special ingredients (paneer beats onion), little shopping, not a side or sweet.
    rows.sort(key=lambda x: (-x["fit"], x["minutes"]))
    return rows[: max(1, min(limit, 12))]


@tool
def get_recipe(recipe_id: str, servings: int = 2) -> dict:
    """Get one recipe's ingredients (scaled to the servings) and step count.

    Args:
        recipe_id: The id returned by find_recipes.
        servings: How many people to cook for.

    Returns:
        title, minutes, ingredients (with scaled amounts) and the number of steps, or an error.
    """
    TRACE.append({"tool": "get_recipe", "input": {"recipe_id": recipe_id, "servings": servings}})
    r = full_recipe(recipe_id)
    if not r:
        return {"error": "No recipe with that id"}
    factor = servings / max(1, r.get("servings", 1))
    items = []
    for i in r.get("ingredients", []):
        items.append(f"{quantity(i.get('amount'), i.get('unit', ''), factor)} {i['name']}".strip())
    return {"title": r["title"], "minutes": r.get("prepTime", 0) + r.get("cookTime", 0), "ingredients": items,
            "steps": len(r.get("steps", []))}


@tool
def suggest_swap(missing_ingredient: str) -> str:
    """Suggest a common kitchen substitute for an ingredient the cook is missing.

    Args:
        missing_ingredient: The ingredient they lack, e.g. "cream".

    Returns:
        A short swap idea, or a note that there is no standard swap.
    """
    TRACE.append({"tool": "suggest_swap", "input": {"missing_ingredient": missing_ingredient}})
    for word in keywords(missing_ingredient):
        if word in SWAPS:
            return f"{SWAPS[word]}. (Check labels if cooking for allergies.)"
    return "No standard swap; it is best to buy this one."


def quantity(amount, unit, factor):
    """'0.8 cup' -> '1 cup', '400.0 g' -> '400 g': kitchen-friendly rounding."""
    if amount is None:
        return ""
    value = amount * factor
    if unit in ("g", "ml"):
        value = 5 * round(value / 5) if value >= 20 else round(value)
    else:
        value = max(0.5, round(value * 2) / 2)
    return f"{value:g} {unit}".strip()


def shopping(recipe_ids, servings, have_ingredients):
    """Server-side shopping list: every main ingredient of the chosen recipes the cook doesn't have, scaled."""
    mine = [keywords(i) for i in have_ingredients if keywords(i)]
    seen, out = set(), []
    for rid in recipe_ids:
        r = full_recipe(rid)
        if not r:
            continue
        factor = servings / max(1, r.get("servings", 1))
        for i in r.get("ingredients", []):
            if is_staple(i["name"]) or have_it(i["name"], mine):
                continue
            key = tuple(sorted(keywords(i["name"])))
            if key in seen:
                continue
            seen.add(key)
            out.append(f"{quantity(i.get('amount'), i.get('unit', ''), factor)} {i['name']}".strip())
    return out


@tool
def shopping_list(recipe_ids: list[str], servings: int, ingredients_they_have: list[str]) -> list[str]:
    """Build the shopping list for the chosen recipes: what to buy, scaled to the servings.

    Args:
        recipe_ids: Ids of the recipes the cook picked.
        servings: How many people.
        ingredients_they_have: What the cook already has.

    Returns:
        A list like "400 g spaghetti".
    """
    TRACE.append({"tool": "shopping_list", "input": {"recipe_ids": recipe_ids, "servings": servings}})
    return shopping(recipe_ids, servings, ingredients_they_have)


# ---- the agent -----------------------------------------------------------------------------------------------------

class Meal(BaseModel):
    recipe_id: str = Field(description="Exactly an id returned by find_recipes")
    title: str
    why: str = Field(description="One short sentence on why this suits the cook (time, what they have, diet)")
    swaps: list[str] = Field(default_factory=list, description="Swap ideas for missing ingredients, e.g. 'cream: yogurt'")


class DinnerPlan(BaseModel):
    summary: str = Field(description="One or two friendly sentences to say aloud, under 40 words, no lists or symbols")
    meals: list[Meal] = Field(description="One to three meals, best first")
    tip: str = Field(default="", description="One short cooking tip, or empty")


SYSTEM = """You are CookAlong's dinner planner for a family cooking from their Fire TV.
Use the tools: call find_recipes with the ingredients, look closer at the best candidates with get_recipe, call
suggest_swap for at most three important missing ingredients, and shopping_list to check what to buy. Choose one to three meals.
Rules:
- Prefer a proper main dish that uses the main ingredients the cook has (type "main"); only suggest a side, snack, drink
  or sweet (any other type) as an extra, or when no main fits.
- Only recommend recipes returned by find_recipes. Never invent a recipe or an id.
- Respect the diet and time limit given. Prefer meals that need the fewest extra purchases.
- Never say a recipe or swap is safe for an allergy. Tell people to check labels.
- Keep every text short and friendly; the summary is spoken aloud by Alexa. In the summary name the meals and the
  main thing to buy, but never state minutes, counts or quantities (the screen shows those)."""


def build_agent():
    kwargs = {"model_id": MODEL_ID, "region_name": REGION, "temperature": 0.3, "max_tokens": 1500}
    if GUARDRAIL_ID:
        kwargs.update(guardrail_id=GUARDRAIL_ID, guardrail_version=GUARDRAIL_VERSION)
    return Agent(
        model=BedrockModel(**kwargs), system_prompt=SYSTEM,
        tools=[find_recipes, get_recipe, suggest_swap, shopping_list], callback_handler=None,
    )


def plan_dinner(ingredients, servings, diet, max_minutes):
    TRACE.clear()
    prompt = (f"I have: {', '.join(ingredients)}. Cooking for {servings}. "
              + (f"Diet: {diet}. " if diet else "") + (f"Ready within {max_minutes} minutes. " if max_minutes else "")
              + "Plan dinner.")
    result = build_agent()(prompt, structured_output_model=DinnerPlan)
    plan = result.structured_output
    return finalize(plan, ingredients, servings, diet, max_minutes), list(TRACE)


def finalize(plan, ingredients, servings, diet, max_minutes):
    """Replace anything the TV will show with ground truth from the database; drop meals the agent made up."""
    mine = [keywords(i) for i in ingredients if keywords(i)]
    by_id = {r["id"]: r for r in catalog()}
    meals = []
    for m in plan.meals[:3]:
        r = by_id.get(m.recipe_id)
        if not r or (diet and diet not in r.get("dietaryTags", [])):
            continue
        if max_minutes and int(r.get("totalMinutes", 0)) > max_minutes:
            continue
        have, missing = match(r.get("ingredientNames", []), mine)
        meals.append({"recipeId": r["id"], "title": r["title"], "minutes": int(r.get("totalMinutes", 0)), "why": m.why,
                      "have": len(have), "total": len(have) + len(missing), "missing": missing[:6], "swaps": m.swaps[:3]})
    ids = [m["recipeId"] for m in meals]
    return {"summary": plan.summary if meals else "I couldn't find a good match. Try adding a few more ingredients.",
            "meals": meals, "shoppingList": shopping(ids, servings, ingredients)[:20], "tip": plan.tip}


# ---- Lambda entry point --------------------------------------------------------------------------------------------

def reply(status, body):
    return {"statusCode": status, "headers": {"content-type": "application/json", "cache-control": "no-store"},
            "body": json.dumps(body)}


def handler(event, context):
    http = event.get("requestContext", {}).get("http", {})
    method, path = http.get("method", "GET"), event.get("rawPath", "/")
    if method == "GET" and path == "/health":
        return reply(200, {"ok": True, "model": MODEL_ID, "guardrail": bool(GUARDRAIL_ID)})
    if not (method == "POST" and path == "/plan"):
        return reply(404, {"error": "Not found"})
    try:
        body = json.loads(event.get("body") or "{}")
    except ValueError:
        return reply(400, {"error": "Body must be JSON"})
    try:
        ingredients, servings, diet, max_minutes, device = parse(body)
    except ValueError as e:
        return reply(400, {"error": str(e)})
    if not allow(device):
        return reply(429, {"error": "That's a lot of plans for one hour. Please try again later."})
    if not within_daily_cap(PLAN_DAILY_CAP):
        return reply(429, {"error": "CookAlong's demo has reached today's planning limit. Please try again tomorrow."})
    try:
        plan, trace = plan_dinner(ingredients, servings, diet, max_minutes)
    except Exception as e:  # never hand a stack trace to a TV
        print("Plan failed", type(e).__name__, e)
        return reply(502, {"error": "The planner couldn't finish. Please try again."})
    return reply(200, {"plan": plan, "steps": trace, "model": MODEL_ID})


def parse(body):
    device = str(body.get("device", ""))
    if not DEVICE_RE.match(device):
        raise ValueError("Bad device id")
    ingredients = body.get("ingredients")
    code = str(body.get("scanCode", "")).upper()
    if not ingredients and code:
        if not CODE_RE.match(code):
            raise ValueError("Bad scan code")
        item = ddb().Table(SCANS_TABLE).get_item(Key={"code": code}).get("Item")
        if not item or item.get("status") != "done":
            raise ValueError("That fridge scan isn't ready or has expired")
        ingredients = [i["name"] for i in json.loads(item.get("ingredients", "[]"))]
    if not isinstance(ingredients, list):
        raise ValueError("Send ingredients or a scanCode")
    ingredients = [str(i).strip()[:40] for i in ingredients if str(i).strip()][:30]
    if not ingredients:
        raise ValueError("Tell me at least one ingredient")
    servings = int(body.get("servings", 2) or 2)
    diet = str(body.get("diet", "")).lower()
    max_minutes = int(body.get("maxMinutes", 0) or 0)
    if not 1 <= servings <= 12 or diet not in DIETS or not 0 <= max_minutes <= 240:
        raise ValueError("servings 1-12, maxMinutes 0-240, diet vegetarian/vegan/gluten-free/dairy-free")
    return ingredients, servings, diet, max_minutes, device


def within_daily_cap(cap):
    """Counts today's plans across ALL TVs (a last line of defence for the AWS bill). Fails open if DynamoDB errors."""
    day = time.strftime("%Y-%m-%d", time.gmtime())
    try:
        out = ddb().Table(TV_TABLE).update_item(
            Key={"pk": f"cap#plan#{day}"},
            UpdateExpression="ADD n :one SET expires = :e",
            ExpressionAttributeValues={":one": 1, ":e": int(time.time()) + 2 * 86400},
            ReturnValues="UPDATED_NEW",
        )
        return int(out["Attributes"]["n"]) <= cap
    except Exception as e:
        print("Daily cap check failed, allowing", e)
        return True


def allow(device):
    """At most PLANS_PER_HOUR plans per TV per hour (each plan is several model calls)."""
    now = int(time.time())
    try:
        out = ddb().Table(TV_TABLE).update_item(
            Key={"pk": "plan#" + device},
            UpdateExpression="SET n = if_not_exists(n, :z) + :one, expires = if_not_exists(expires, :e)",
            ExpressionAttributeValues={":z": 0, ":one": 1, ":e": now + 3600},
            ReturnValues="ALL_NEW",
        )["Attributes"]
        if int(out["expires"]) < now:  # expired counter not yet purged by TTL: start a new hour
            ddb().Table(TV_TABLE).put_item(Item={"pk": "plan#" + device, "n": 1, "expires": now + 3600})
            return True
        return int(out["n"]) <= PLANS_PER_HOUR
    except Exception as e:
        print("Rate-limit check failed, allowing", e)
        return True
