"""Tests for the fridge-scan helpers in lambda_function.py. Run: python backend/test_scan.py (no AWS needed)."""
import os
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


if __name__ == "__main__":
    unittest.main()
