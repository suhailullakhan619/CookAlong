"""Speak an utterance to the Cook Along skill through Alexa's own simulator and print what it replied.

    SKILL_ID=amzn1.ask.skill.xxxx python tools/alexa_sim.py "tell cook along next step" [en-US]

Needs the ASK CLI signed in (`ask configure`) and the skill enabled for testing
(`ask smapi set-skill-enablement -s <id> -g development`). The text goes through Alexa's real language understanding,
so the reply shows which intent Alexa picked; the skill Lambda then runs exactly as it would for a spoken request.
Pair a TV first: open Connect Alexa on the TV, then `python tools/alexa_sim.py "ask cook along to pair with one two three four five six"`.
"""
import json
import os
import subprocess
import sys
import time

SKILL = os.environ.get("SKILL_ID", "")
if not SKILL or len(sys.argv) < 2:
    sys.exit(__doc__)
utterance = sys.argv[1]
locale = sys.argv[2] if len(sys.argv) > 2 else "en-US"


def ask(*args):
    p = subprocess.run(["ask", "smapi", *args], capture_output=True, text=True, shell=(os.name == "nt"))
    return p.stdout.strip() or p.stderr.strip()


start = ask("simulate-skill", "-s", SKILL, "-g", "development", "--input-content", utterance,
            "--device-locale", locale, "--session-mode", "FORCE_NEW_SESSION")
try:
    sim_id = json.loads(start)["id"]
except (ValueError, KeyError):
    sys.exit("could not start the simulation: " + start[:300])
result = {}
for _ in range(20):
    out = ask("get-skill-simulation", "-s", SKILL, "--simulation-id", sim_id)
    try:
        result = json.loads(out)
    except ValueError:
        sys.exit(out[:300])
    if result.get("status") in ("SUCCESSFUL", "FAILED"):
        break
    time.sleep(2)
print("status:", result.get("status"))
for inv in result.get("result", {}).get("skillExecutionInfo", {}).get("invocations", []):
    req = inv.get("invocationRequest", {}).get("body", {}).get("request", {})
    resp = inv.get("invocationResponse", {}).get("body", {}).get("response", {})
    print("  heard as:", req.get("type"), (req.get("intent") or {}).get("name", ""))
    print("  reply   :", resp.get("outputSpeech", {}).get("text"))
if result.get("result", {}).get("error"):
    print("error:", json.dumps(result["result"]["error"])[:400])
