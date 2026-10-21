"""Tests for the fridge-scan helpers in lambda_function.py. Run: python backend/test_scan.py (no AWS needed)."""
import base64
import json
import os
import re
import sys
import unittest
from unittest import mock

os.environ.setdefault("TABLE_NAME", "test")
os.environ.setdefault("AWS_DEFAULT_REGION", "ap-south-1")
for name in ("boto3", "botocore", "botocore.config", "botocore.exceptions", "tv_bridge"):
    sys.modules.setdefault(name, mock.MagicMock())
sys.modules["botocore.exceptions"].BotoCoreError = Exception
sys.modules["botocore.exceptions"].ClientError = Exception
sys.modules["tv_bridge"].Bridge = object
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lambda_function as L  # noqa: E402


class ParseIngredients(unittest.TestCase):
    def test_fenced_json_and_cleaning(self):
        text = '```json\n{"ingredients":[{"name":"Egg","confidence":"high"},{"name":"sauce","confidence":"high"},' \
               '{"name":"Red  Onion","confidence":"x"},{"name":"egg","confidence":"high"}],"note":"ok"}\n```'
        items, note = L.parse_ingredients(text)
        self.assertEqual([i["name"] for i in items], ["egg", "red onion"])
        self.assertEqual(items[1]["confidence"], "medium")
        self.assertEqual(note, "ok")

    def test_truncated_reply_keeps_intact_items(self):
        items, _ = L.parse_ingredients('Sure! {"ingredients": [{"name": "milk", "confidence": "high"}, {"name": "tomato", "confidence": "med')
        self.assertEqual([i["name"] for i in items], ["milk", "tomato"])

    def test_garbage_fails(self):
        with self.assertRaises(L.ScanFailed):
            L.parse_ingredients("nothing here")


class Requests(unittest.TestCase):
    def test_magic_bytes(self):
        self.assertTrue(L.looks_like(b"\xff\xd8\xff\xe0", "image/jpeg"))
        self.assertFalse(L.looks_like(b"hello world", "image/jpeg"))
        self.assertTrue(L.looks_like(b"\x89PNG\r\n\x1a\nrest", "image/png"))
        self.assertTrue(L.looks_like(b"RIFF1234WEBPx", "image/webp"))

    def test_body_must_be_an_object(self):
        with self.assertRaises(L.BadRequest):
            L.parse_body({"body": "[1, 2]"})
        self.assertEqual(L.parse_body({"body": '{"a": 1}'}), {"a": 1})


class DailyCap(unittest.TestCase):
    def fake_bridge(self, fail=False):
        counts = {"n": 0}

        def update_item(**kw):
            if fail:
                raise RuntimeError("dynamo down")
            counts["n"] += 1
            return {"Attributes": {"n": counts["n"]}}
        return mock.MagicMock(t=mock.MagicMock(update_item=update_item))

    def test_refuses_after_the_cap(self):
        with mock.patch.object(L, "bridge", return_value=self.fake_bridge()):
            self.assertEqual([L.within_daily_cap("scan", 2) for _ in range(4)], [True, True, False, False])

    def test_fails_open_if_the_counter_breaks(self):
        with mock.patch.object(L, "bridge", return_value=self.fake_bridge(fail=True)):
            self.assertTrue(L.within_daily_cap("scan", 1))


class Speech(unittest.TestCase):
    DEVICE = "a" * 32

    def call(self, body, polly_audio=b"ID3fake", limits=True):
        fake_polly = mock.MagicMock()
        fake_polly.synthesize_speech.return_value = {"AudioStream": mock.MagicMock(read=lambda: polly_audio)}
        # tv_bridge is mocked in these tests, so give it the real device-id pattern.
        with mock.patch.object(L, "polly", return_value=fake_polly), \
                mock.patch.object(L, "under_limit", return_value=limits), \
                mock.patch.object(L.tv_bridge, "DEVICE_RE", re.compile(r"^[A-Za-z0-9-]{20,64}$")):
            event = {"requestContext": {"http": {"method": "POST"}}, "rawPath": "/tts", "body": json.dumps(body)}
            return L.handler(event, None), fake_polly

    def test_returns_mp3_audio(self):
        out, polly = self.call({"device": self.DEVICE, "text": "Step 1 of 7.   Bring a pot of water to the boil."})
        self.assertEqual(out["statusCode"], 200)
        self.assertEqual(out["headers"]["content-type"], "audio/mpeg")
        self.assertTrue(out["isBase64Encoded"])
        self.assertEqual(base64.b64decode(out["body"]), b"ID3fake")
        self.assertEqual(polly.synthesize_speech.call_args.kwargs["Text"], "Step 1 of 7. Bring a pot of water to the boil.")

    def test_rejects_empty_long_and_bad_device(self):
        self.assertEqual(self.call({"device": self.DEVICE, "text": "  "})[0]["statusCode"], 400)
        self.assertEqual(self.call({"device": self.DEVICE, "text": "x" * 601})[0]["statusCode"], 400)
        self.assertEqual(self.call({"device": "short", "text": "hi"})[0]["statusCode"], 400)

    def test_over_the_limit_says_so(self):
        out, polly = self.call({"device": self.DEVICE, "text": "hello"}, limits=False)
        self.assertEqual(out["statusCode"], 429)
        polly.synthesize_speech.assert_not_called()


if __name__ == "__main__":
    unittest.main()
