import hashlib
import importlib.util
import unittest
from pathlib import Path
from urllib.parse import quote_plus

SCRIPT = Path(__file__).resolve().parents[1] / "probe-anonymous-playurl.py"
SPEC = importlib.util.spec_from_file_location("probe_anonymous_playurl", SCRIPT)
PROBE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(PROBE)


class AnonymousPlayurlProbeTest(unittest.TestCase):
    def test_wbi_mixin_key_uses_bilibili_permutation_table(self):
        raw = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-_"
        expected = "".join(
            raw[index] for index in PROBE.WBI_MIXIN_KEY_ENC_TAB if index < len(raw)
        )[:32]
        self.assertEqual(expected, PROBE.derive_mixin_key(raw[:32], raw[32:]))

    def test_try_look_is_part_of_signed_canonical_query(self):
        parameters = {
            "bvid": "BV1xx411c7mD",
            "cid": "123456",
            "qn": "80",
            "fnval": "4048",
            "try_look": "1",
            "caption": "a!'()*b",
        }
        mixin_key = "0123456789abcdef0123456789abcdef"
        timestamp = 1_700_000_000

        signed = PROBE.sign_wbi(parameters, mixin_key, timestamp=timestamp)
        cleaned = {
            key: PROBE.INVALID_WBI_CHARS.sub("", str(value))
            for key, value in {**parameters, "wts": str(timestamp)}.items()
        }
        canonical = "&".join(
            f"{quote_plus(key)}={quote_plus(cleaned[key])}"
            for key in sorted(cleaned)
        )
        expected = hashlib.md5((canonical + mixin_key).encode("utf-8")).hexdigest()

        self.assertTrue(signed.endswith("&w_rid=" + expected))
        self.assertIn("try_look=1", signed)
        self.assertIn("caption=ab", signed)

    def test_bvid_validation_accepts_complete_bvid_only(self):
        self.assertIsNotNone(PROBE.BVID_PATTERN.fullmatch("BV1xx411c7mD"))
        self.assertIsNone(PROBE.BVID_PATTERN.fullmatch("av170001"))
        self.assertIsNone(PROBE.BVID_PATTERN.fullmatch("BV123"))


if __name__ == "__main__":
    unittest.main()
