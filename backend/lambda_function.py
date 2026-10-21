"""CookAlong fridge scan - AWS Lambda (Python 3.12) behind a Lambda Function URL.

Flow
  1. TV:    POST /session                  -> {"code": "K7Q2XM"}   (pairing code, valid 15 min)
  2. Phone: POST /scan {code, image, mediaType}                    (photo of a fridge / pantry)
            -> Amazon Nova on Bedrock lists the visible ingredients -> stored under the code
  3. TV:    GET  /scan?code=K7Q2XM         -> {"status": "waiting" | "done" | "error", "ingredients": [...]}

Read-aloud voice
  TV:       POST /tts {device, text}       -> audio/mpeg   (Amazon Polly reads a cooking step in a natural voice)

Privacy: the photo is only held in memory for the Bedrock call; just the ingredient list is stored, and every
record expires after an hour (DynamoDB TTL). No accounts, no personal data.
Cost control: a scan needs a live pairing code, each code allows at most 3 scans, images are capped at 5 MB,
and the function's reserved concurrency is kept low (see deploy.sh).
"""
import base64
import json
import os
import re
import secrets
import time

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

import tv_bridge
from tv_bridge import Bridge

TABLE = boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])
# Amazon Nova on Bedrock: Amazon's own models, billed by AWS directly (no AWS Marketplace subscription).
# Nova Pro picks out the most detail in a fridge photo; Nova 2 Lite takes over if Pro is throttled or unavailable.
# Both are cross-region inference profiles (apac / global), the only way Bedrock serves them on demand.
MODELS = [m for m in os.environ.get("MODEL_IDS", "apac.amazon.nova-pro-v1:0,global.amazon.nova-2-lite-v1:0").split(",") if m]
BEDROCK_REGION = os.environ.get("BEDROCK_REGION", os.environ.get("AWS_REGION", "ap-south-1"))
RUNTIME = boto3.client("bedrock-runtime", region_name=BEDROCK_REGION,
                       config=Config(connect_timeout=5, read_timeout=20, retries={"max_attempts": 1}))

CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no 0/O/1/I/L: easy to read off a TV
CODE_RE = re.compile(r"^[%s]{6}$" % CODE_ALPHABET)
SESSION_TTL = 15 * 60
RESULT_TTL = 60 * 60
MAX_SCANS_PER_CODE = 3
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MEDIA_TYPES = {"image/jpeg", "image/png", "image/webp"}

SYSTEM = """You look at one photo taken in a home kitchen - usually an open fridge, a pantry shelf or a counter, sometimes a
cooked dish - and list the food ingredients you can actually see, so a cooking app can suggest recipes.

Rules:
- Only list items that are clearly visible. Do not guess what might be behind doors or inside opaque containers,
  and never add an item just because fridges usually contain it. If a label or shape makes an item reasonably
  certain (an egg carton, a milk carton, a butter pack), include it with confidence "medium".
- Name raw ingredients, never dishes: for a cooked dish list only the ingredients you can recognise in it.
- Use short, generic, lowercase English names in the singular, the way a recipe would say them: "egg",
  "tomato", "red onion", "bell pepper", "milk", "paneer", "spinach", "lemon", "butter". No brand names, and never a
  category such as "vegetables", "fruit" or "leftovers".
- Merge duplicates (three tomatoes are one "tomato"). List at most 15 items, each one once.
- Skip non-food items, drinks other than milk/juice/coconut milk, and condiments that recipes rarely need.
- If the photo has no food in it, return an empty list and say so in "note".

Reply with only this JSON object and nothing else:
{"ingredients": [{"name": "<name>", "confidence": "high" | "medium"}], "note": "<one short sentence for the user>"}"""


def handler(event, context):
    method = event.get("requestContext", {}).get("http", {}).get("method", "GET")
    path = event.get("rawPath", "/")
    try:
        if method == "POST" and path == "/session":
            return create_session()
        if method == "POST" and path == "/scan":
            return scan(parse_body(event))
        if method == "GET" and path == "/scan":
            return get_scan((event.get("queryStringParameters") or {}).get("code", ""))
        if method == "GET" and path == "/recipes":
            return list_recipes(event.get("queryStringParameters") or {})
        if method == "GET" and path.startswith("/recipes/"):
            return get_recipe(path[len("/recipes/"):])
        if path.startswith("/tv/"):
            return tv_route(method, path, event)
        if method == "POST" and path == "/tts":
            return tts(parse_body(event))
        return reply(404, {"error": "Not found"})
    except BadRequest as e:
        return reply(400, {"error": str(e)})


class BadRequest(Exception):
    pass


def parse_body(event):
    body = event.get("body") or ""
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body).decode("utf-8")
    try:
        data = json.loads(body)
    except ValueError:
        raise BadRequest("Body must be JSON")
    if not isinstance(data, dict):
        raise BadRequest("Body must be a JSON object")
    return data


def create_session():
    code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(6))
    now = int(time.time())
    TABLE.put_item(Item={"code": code, "status": "waiting", "scans": 0, "created": now, "expires": now + SESSION_TTL})
    return reply(200, {"code": code, "expiresIn": SESSION_TTL})


def get_scan(code):
    code = code.strip().upper()
    if not CODE_RE.match(code):
        raise BadRequest("Bad code")
    item = TABLE.get_item(Key={"code": code}).get("Item")
    if not item or int(item.get("expires", 0)) < time.time():
        return reply(404, {"status": "expired"})
    out = {"status": item["status"]}
    if item["status"] == "done":
        out["ingredients"] = json.loads(item.get("ingredients", "[]"))
        out["note"] = item.get("note", "")
    if item["status"] == "error":
        out["message"] = item.get("message", "")
    return reply(200, out)


SCAN_DAILY_CAP = int(os.environ.get("SCAN_DAILY_CAP", "500"))


def within_daily_cap(kind, cap, amount=1):
    """Counts today's (UTC) use of one paid feature across ALL users and says whether we're still under [cap].

    A last line of defence for the AWS bill: per-code and per-TV limits stop one user, this stops everyone together.
    [amount] is what this call adds (1 per call, or characters for the voice). If the counter can't be read we allow
    the call (a DynamoDB hiccup must not take the feature down).
    """
    day = time.strftime("%Y-%m-%d", time.gmtime())
    return under_limit(f"cap#{kind}#{day}", cap, amount, 2 * 86400)


def under_limit(key, cap, amount, ttl):
    try:
        out = bridge().t.update_item(
            Key={"pk": key},
            UpdateExpression="ADD n :a SET expires = :e",
            ExpressionAttributeValues={":a": amount, ":e": int(time.time()) + ttl},
            ReturnValues="UPDATED_NEW",
        )
        return int(out["Attributes"]["n"]) <= cap
    except Exception as e:
        print("Limit check failed, allowing", key, e)
        return True


def scan(body):
    code = str(body.get("code", "")).strip().upper()
    media_type = body.get("mediaType", "image/jpeg")
    image_b64 = body.get("image", "")
    if not CODE_RE.match(code):
        raise BadRequest("Bad code")
    if media_type not in MEDIA_TYPES:
        raise BadRequest("Unsupported image type")
    try:
        raw = base64.b64decode(image_b64, validate=True)
    except ValueError:
        raise BadRequest("Image must be base64")
    if not raw or len(raw) > MAX_IMAGE_BYTES:
        raise BadRequest("Image is empty or larger than 5 MB")
    if not looks_like(raw, media_type):
        raise BadRequest("That file isn't a photo we can read. Take a new photo and try again.")

    if not within_daily_cap("scan", SCAN_DAILY_CAP):
        return reply(429, {"error": "CookAlong's demo has reached today's scan limit. Please try again tomorrow."})

    # Claim one scan on a live session atomically (stops replay/abuse of the paid model call).
    now = int(time.time())
    try:
        TABLE.update_item(
            Key={"code": code},
            UpdateExpression="SET #n = #n + :one, #s = :busy",
            ConditionExpression="attribute_exists(#c) AND #e > :now AND #n < :max",
            ExpressionAttributeNames={"#s": "status", "#n": "scans", "#c": "code", "#e": "expires"},
            ExpressionAttributeValues={":one": 1, ":busy": "scanning", ":now": now, ":max": MAX_SCANS_PER_CODE},
        )
    except TABLE.meta.client.exceptions.ConditionalCheckFailedException:
        return reply(409, {"error": "This code has expired or been used. Start a new fridge scan on your TV."})

    try:
        ingredients, note = ask_model(image_b64, media_type)
        TABLE.update_item(
            Key={"code": code},
            UpdateExpression="SET #s = :done, #i = :i, #t = :n, #e = :e",
            ExpressionAttributeNames={"#s": "status", "#i": "ingredients", "#t": "note", "#e": "expires"},
            ExpressionAttributeValues={":done": "done", ":i": json.dumps(ingredients), ":n": note, ":e": now + RESULT_TTL},
        )
        return reply(200, {"status": "done", "ingredients": ingredients, "note": note})
    except ScanFailed as e:
        TABLE.update_item(
            Key={"code": code},
            UpdateExpression="SET #s = :err, #m = :m",
            ExpressionAttributeNames={"#s": "status", "#m": "message"},
            ExpressionAttributeValues={":err": "error", ":m": str(e)},
        )
        return reply(502, {"status": "error", "message": str(e)})


class ScanFailed(Exception):
    pass


def looks_like(raw, media_type):
    """Cheap magic-byte check, so a text file or the wrong format never costs a scan or a model call."""
    if media_type == "image/jpeg":
        return raw[:3] == b"\xff\xd8\xff"
    if media_type == "image/png":
        return raw[:8] == b"\x89PNG\r\n\x1a\n"
    return raw[:4] == b"RIFF" and raw[8:12] == b"WEBP"


def ask_model(image_b64, media_type):
    """Asks Amazon Nova (Bedrock Converse API) for the ingredients in the photo, trying each model in MODELS."""
    image = base64.b64decode(image_b64)
    unreadable = False
    empty = None  # an empty answer is kept, in case the second opinion fails
    started = time.time()
    for model in MODELS + MODELS[:1]:
        if time.time() - started > 35:  # the Lambda is cut off at 60 s: answer with what we have rather than hang the TV
            break
        try:
            reply = RUNTIME.converse(
                modelId=model,
                system=[{"text": SYSTEM}],
                messages=[{"role": "user", "content": [
                    {"image": {"format": media_type.split("/")[1], "source": {"bytes": image}}},
                    {"text": "List the ingredients you can see."},
                ]}],
                inferenceConfig={"maxTokens": 700, "temperature": 0},
            )
        except ClientError as e:
            code = e.response.get("Error", {}).get("Code")
            print("Bedrock error", model, code, e)
            if code == "ValidationException":  # the photo itself was rejected; another model won't like it either
                raise ScanFailed("The AI couldn't read that photo. Try a different picture.")
            continue
        except BotoCoreError as e:
            print("Bedrock connection error", model, e)
            continue
        if reply.get("stopReason") == "content_filtered":
            raise ScanFailed("The AI couldn't analyse that photo. Try a clearer picture of your food.")
        text = "".join(b.get("text", "") for b in reply["output"]["message"]["content"]).strip()
        try:
            items, note = parse_ingredients(text)
            if not items and empty is None:
                empty = (items, note)  # Nova sometimes answers "nothing" for a busy fridge: ask again before believing it
                continue
            return items, note
        except ScanFailed:
            print("Unreadable model reply", model, text[:300])
            unreadable = True  # the model sometimes wraps or truncates its JSON: try the next model, then once more
    if empty is not None:
        return empty
    if unreadable:
        raise ScanFailed("The AI reply couldn't be read. Please try again.")
    raise ScanFailed("The AI is busy right now. Please try again in a minute.")


NOT_INGREDIENTS = {"food", "foods", "vegetable", "vegetables", "veggie", "veggies", "fruit", "fruits", "dish", "meal",
                   "leftover", "leftovers", "drink", "drinks", "beverage", "condiment", "condiments", "sauce", "sauces",
                   "container", "containers", "snack", "snacks", "ingredient", "ingredients", "item", "items", "other"}


def parse_ingredients(text):
    """Validates the model's JSON reply (it is asked for JSON only, but we never trust that blindly)."""
    data = None
    match = re.search(r"\{.*\}", text, re.S)
    if match:
        try:
            data = json.loads(match.group(0))
        except ValueError:
            data = None
    if not isinstance(data, dict):
        # Truncated or chatty reply: salvage the {"name": ..., "confidence": ...} pairs that are intact.
        pairs = re.findall(r'"name"\s*:\s*"([^"]*)"(?:\s*,\s*"confidence"\s*:\s*"([^"]*)")?', text)
        if not pairs:
            raise ScanFailed("The AI reply couldn't be read. Please try again.")
        data = {"ingredients": [{"name": n, "confidence": c} for n, c in pairs], "note": ""}
    seen, items = set(), []
    for it in (data.get("ingredients") or [])[:40]:
        if not isinstance(it, dict):
            continue
        name = re.sub(r"\s+", " ", re.sub(r"[^a-z \-]", "", str(it.get("name", "")).lower())).strip(" -")[:40]
        if name and name not in NOT_INGREDIENTS and name not in seen:
            seen.add(name)
            items.append({"name": name, "confidence": "high" if it.get("confidence") == "high" else "medium"})
    note = str(data.get("note", ""))[:200]
    return items, note


# ---- Recipe catalog (table cookalong-recipes) --------------------------------------------------------------------
#
# One item per recipe:
#   id (partition key), title, categories[], dietaryTags[], totalMinutes, ingredientNames[], source,
#   updatedAt (epoch ms), recipe (the full recipe JSON, exactly the app's recipes.json format, as a string)
# The flat fields make querying easy (for people and for AI features); the app only needs "recipe".

RECIPES = boto3.resource("dynamodb").Table(os.environ.get("RECIPES_TABLE", "cookalong-recipes"))
ID_RE = re.compile(r"^[a-z0-9-]{1,80}$")
PAGE = 300


def list_recipes(params):
    """GET /recipes?since=<epoch ms>&next=<token> -> {recipes:[...], next, serverTime}.
    Pass the previous serverTime as `since` to fetch only new/changed recipes."""
    try:
        since = int(params.get("since", "0") or 0)
    except ValueError:
        raise BadRequest("since must be a number")
    scan = {"Limit": PAGE, "ProjectionExpression": "#r, updatedAt", "ExpressionAttributeNames": {"#r": "recipe"}}
    if since > 0:
        scan["FilterExpression"] = "updatedAt > :s"
        scan["ExpressionAttributeValues"] = {":s": since}
    token = params.get("next")
    if token:
        try:
            scan["ExclusiveStartKey"] = {"id": base64.urlsafe_b64decode(token.encode()).decode()}
        except ValueError:
            raise BadRequest("Bad next token")
    server_time = int(time.time() * 1000)
    page = RECIPES.scan(**scan)
    recipes = []
    for item in page.get("Items", []):
        try:
            recipes.append(json.loads(item["recipe"]))
        except (KeyError, ValueError):
            continue
    last = page.get("LastEvaluatedKey")
    nxt = base64.urlsafe_b64encode(last["id"].encode()).decode() if last else None
    return reply(200, {"recipes": recipes, "next": nxt, "serverTime": server_time})


def get_recipe(recipe_id):
    if not ID_RE.match(recipe_id):
        raise BadRequest("Bad recipe id")
    item = RECIPES.get_item(Key={"id": recipe_id}).get("Item")
    if not item:
        return reply(404, {"error": "No such recipe"})
    return reply(200, json.loads(item["recipe"]))


# ---- Alexa bridge: endpoints the TV app calls (see tv_bridge.py and alexa_skill.py) ---------------------------------

_bridge = None


def bridge():
    global _bridge
    if _bridge is None:
        _bridge = Bridge()
    return _bridge


def tv_route(method, path, event):
    """POST /tv/pair {device}            -> {code, expiresIn}   6-digit code the user says to Alexa
       GET  /tv/commands?device=&after=N  -> {seq, linked, commands}   polled ~1/s while the app is on screen
       POST /tv/state {device, state}     -> {ok}   what's on screen, so Alexa can answer questions
       POST /tv/unlink {device}           -> {ok}"""
    if method == "GET" and path == "/tv/commands":
        params = event.get("queryStringParameters") or {}
        device = check_device(params.get("device", ""))
        try:
            after = int(params.get("after", "-1"))
        except ValueError:
            raise BadRequest("after must be a number")
        seq, linked, commands = bridge().commands_after(device, after)
        return reply(200, {"seq": seq, "linked": linked, "commands": commands})
    if method != "POST":
        return reply(404, {"error": "Not found"})
    body = parse_body(event)
    device = check_device(body.get("device", ""))
    if path == "/tv/pair":
        return reply(200, {"code": bridge().new_pair_code(device), "expiresIn": tv_bridge.PAIR_TTL})
    if path == "/tv/state":
        state = body.get("state")
        if not isinstance(state, dict):
            raise BadRequest("state must be an object")
        try:
            bridge().save_state(device, state)
        except ValueError as e:
            raise BadRequest(str(e))
        return reply(200, {"ok": True})
    if path == "/tv/unlink":
        bridge().unlink(device)
        return reply(200, {"ok": True})
    return reply(404, {"error": "Not found"})


# ---- read-aloud voice (Amazon Polly) -------------------------------------------------------------------------------
# The TV speaks cooking steps with Polly's generative voice and falls back to the TV's own speech engine offline.
# Generative voices are served from us-east-1. Limits keep the bill small: 600 characters per request,
# 300 requests per TV per hour, and 200,000 characters per day across all TVs (about 6 US dollars at list price).
POLLY_REGION = os.environ.get("POLLY_REGION", "us-east-1")
POLLY_VOICE = os.environ.get("POLLY_VOICE", "Matthew")
TTS_MAX_CHARS = 600
TTS_PER_DEVICE_HOUR = int(os.environ.get("TTS_PER_DEVICE_HOUR", "300"))
TTS_DAILY_CHARS = int(os.environ.get("TTS_DAILY_CHARS", "200000"))
_polly = None


def polly():
    global _polly
    if _polly is None:
        _polly = boto3.client("polly", region_name=POLLY_REGION,
                              config=Config(connect_timeout=3, read_timeout=10, retries={"max_attempts": 1}))
    return _polly


def tts(body):
    device = check_device(body.get("device", ""))
    text = re.sub(r"\s+", " ", str(body.get("text", ""))).strip()
    if not text:
        raise BadRequest("Nothing to say")
    if len(text) > TTS_MAX_CHARS:
        raise BadRequest(f"Text is longer than {TTS_MAX_CHARS} characters")
    hour = time.strftime("%Y-%m-%dT%H", time.gmtime())
    if not under_limit(f"tts#{device}#{hour}", TTS_PER_DEVICE_HOUR, 1, 2 * 3600):
        return reply(429, {"error": "Too much read-aloud for one hour. Using the TV's own voice."})
    if not within_daily_cap("tts", TTS_DAILY_CHARS, len(text)):
        return reply(429, {"error": "Today's read-aloud limit is reached. Using the TV's own voice."})
    audio = None
    for engine in ("generative", "neural"):  # neural is the fallback if the generative engine is unavailable
        try:
            out = polly().synthesize_speech(Text=text, VoiceId=POLLY_VOICE, Engine=engine, OutputFormat="mp3",
                                            SampleRate="24000")
            audio = out["AudioStream"].read()
            break
        except (BotoCoreError, ClientError) as e:
            print("Polly", engine, "failed", e)
    if audio is None:
        return reply(503, {"error": "The voice service is busy. Using the TV's own voice."})
    return {
        "statusCode": 200,
        "headers": {"content-type": "audio/mpeg", "cache-control": "private, max-age=604800"},
        "body": base64.b64encode(audio).decode(),
        "isBase64Encoded": True,
    }


def check_device(device):
    device = str(device)
    if not tv_bridge.DEVICE_RE.match(device):
        raise BadRequest("Bad device id")
    return device


def reply(status, body):
    return {
        "statusCode": status,
        "headers": {"content-type": "application/json", "cache-control": "no-store"},
        "body": json.dumps(body),
    }
