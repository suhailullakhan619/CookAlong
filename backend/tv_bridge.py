"""Alexa <-> Fire TV bridge storage (DynamoDB table cookalong-tv), shared by both Lambdas.

The TV app can't receive pushes without Amazon Device Messaging, so it polls instead: while CookAlong is on screen
and linked to an Alexa account, it asks GET /tv/commands about once a second. The Alexa skill drops commands into
the TV's queue and reads the state the TV publishes (recipe, step, ingredients, timers) to answer by voice.

Items (partition key "pk"):
  tv#<device>    linked Alexa user, last published state (JSON), command queue (JSON) and its sequence number
  pair#<code>    a 6-digit pairing code shown on the TV, valid 10 minutes, single use
  user#<userId>  which TV an Alexa account controls

The device id is a random secret the TV generates on first run and never shows on screen. Nothing personal is
stored: Alexa user ids are opaque per-skill ids, and the state is only what's on the TV.
"""
import json
import os
import re
import secrets
import time

import boto3

TV_TABLE = os.environ.get("TV_TABLE", "cookalong-tv")
TV_REGION = os.environ.get("TV_TABLE_REGION", "ap-south-1")
DEVICE_RE = re.compile(r"^[A-Za-z0-9-]{20,64}$")
CODE_RE = re.compile(r"^[1-9][0-9]{5}$")
PAIR_TTL = 10 * 60
KEEP_TV = 90 * 24 * 3600          # a TV that hasn't been seen for 90 days is forgotten
COMMAND_MAX_AGE_MS = 60_000       # commands the TV didn't pick up within a minute are dropped, never replayed
MAX_QUEUE = 15
MAX_STATE_BYTES = 16_000


def now_ms():
    return int(time.time() * 1000)


class Bridge:
    def __init__(self, table=None):
        self.t = table or boto3.resource("dynamodb", region_name=TV_REGION).Table(TV_TABLE)
        self._conflict = self.t.meta.client.exceptions.ConditionalCheckFailedException

    # ---- pairing ---------------------------------------------------------------------------------------------

    def new_pair_code(self, device):
        """A fresh 6-digit code (never starting with 0, so Alexa's number slot keeps every digit)."""
        for _ in range(8):
            code = str(secrets.randbelow(900_000) + 100_000)
            try:
                self.t.put_item(
                    Item={"pk": "pair#" + code, "device": device, "expires": int(time.time()) + PAIR_TTL},
                    ConditionExpression="attribute_not_exists(pk) OR expires < :now",
                    ExpressionAttributeValues={":now": int(time.time())},
                )
                return code
            except self._conflict:
                continue
        raise RuntimeError("No free pairing code")

    def claim_pair_code(self, code, user):
        """Links the Alexa user to the TV that shows [code]. Returns the device id, or None if the code is bad."""
        if not CODE_RE.match(code):
            return None
        try:
            old = self.t.delete_item(
                Key={"pk": "pair#" + code},
                ConditionExpression="attribute_exists(pk) AND expires > :now",
                ExpressionAttributeValues={":now": int(time.time())},
                ReturnValues="ALL_OLD",
            )
        except self._conflict:
            return None
        device = old["Attributes"]["device"]
        self.link(user, device)
        return device

    def link(self, user, device):
        previous_tv = self.device_for_user(user)
        if previous_tv and previous_tv != device:
            self.t.update_item(Key={"pk": "tv#" + previous_tv}, UpdateExpression="REMOVE #u",
                               ExpressionAttributeNames={"#u": "user"})
        old = self.t.update_item(
            Key={"pk": "tv#" + device},
            UpdateExpression="SET #u = :u, linkedAt = :t, expires = :e",
            ExpressionAttributeNames={"#u": "user"},
            ExpressionAttributeValues={":u": user, ":t": now_ms(), ":e": int(time.time()) + KEEP_TV},
            ReturnValues="UPDATED_OLD",
        ).get("Attributes", {})
        # one Alexa account per TV: whoever linked this TV before loses it
        if old.get("user") and old["user"] != user:
            self.t.delete_item(Key={"pk": "user#" + old["user"]})
        self.t.put_item(Item={"pk": "user#" + user, "device": device})

    def unlink(self, device):
        old = self.t.update_item(
            Key={"pk": "tv#" + device}, UpdateExpression="REMOVE #u", ExpressionAttributeNames={"#u": "user"},
            ReturnValues="UPDATED_OLD",
        ).get("Attributes", {})
        if old.get("user"):
            self.t.delete_item(Key={"pk": "user#" + old["user"]})

    def device_for_user(self, user):
        item = self.t.get_item(Key={"pk": "user#" + user}).get("Item")
        return item["device"] if item else None

    # ---- commands (skill -> TV) ------------------------------------------------------------------------------

    def push(self, device, command):
        """Appends a command to the TV's queue. Optimistic concurrency on the sequence number."""
        for _ in range(5):
            item = self.t.get_item(Key={"pk": "tv#" + device}).get("Item") or {}
            seq = int(item.get("seq", 0))
            queue = json.loads(item.get("cmds", "[]"))
            cmd = dict(command, seq=seq + 1, at=now_ms())
            queue = [c for c in queue if c["at"] > now_ms() - COMMAND_MAX_AGE_MS][-(MAX_QUEUE - 1):] + [cmd]
            try:
                self.t.update_item(
                    Key={"pk": "tv#" + device},
                    UpdateExpression="SET seq = :n, cmds = :q",
                    ConditionExpression="attribute_not_exists(seq) OR seq = :old",
                    ExpressionAttributeValues={":n": seq + 1, ":q": json.dumps(queue), ":old": seq},
                )
                return seq + 1
            except self._conflict:
                continue
        raise RuntimeError("Command queue busy")

    def commands_after(self, device, after):
        """What the TV hasn't seen yet. after < 0 (first poll after launch) returns no commands, only the cursor,
        so a TV that was switched off never replays old requests."""
        item = self.t.get_item(Key={"pk": "tv#" + device}).get("Item") or {}
        seq = int(item.get("seq", 0))
        linked = bool(item.get("user"))
        if after < 0 or after >= seq:
            return seq, linked, []
        fresh = now_ms() - COMMAND_MAX_AGE_MS
        cmds = [c for c in json.loads(item.get("cmds", "[]")) if c["seq"] > after and c["at"] > fresh]
        return seq, linked, cmds

    # ---- state (TV -> skill) ---------------------------------------------------------------------------------

    def save_state(self, device, state):
        raw = json.dumps(state, separators=(",", ":"))
        if len(raw) > MAX_STATE_BYTES:
            raise ValueError("State too large")
        self.t.update_item(
            Key={"pk": "tv#" + device},
            UpdateExpression="SET #s = :s, stateAt = :t, expires = :e",
            ExpressionAttributeNames={"#s": "state"},
            ExpressionAttributeValues={":s": raw, ":t": now_ms(), ":e": int(time.time()) + KEEP_TV},
        )

    def get_state(self, device):
        """(state dict, age in ms) or (None, None) if the TV never published one."""
        item = self.t.get_item(Key={"pk": "tv#" + device}).get("Item") or {}
        if "state" not in item:
            return None, None
        return json.loads(item["state"]), now_ms() - int(item.get("stateAt", 0))
