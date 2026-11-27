#!/usr/bin/env python3
"""Anonymous Bilibili playurl smoke test; uses only Python's standard library."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote_plus, urlencode, urlparse
from urllib.request import Request, urlopen

WBI_MIXIN_KEY_ENC_TAB = (
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
)
INVALID_WBI_CHARS = re.compile(r"[!'()*]")
BVID_PATTERN = re.compile(r"^BV[0-9A-Za-z]{10}$")
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


def request_json(url: str) -> dict:
    request = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Referer": "https://www.bilibili.com/",
            "Origin": "https://www.bilibili.com",
            "Accept": "application/json, text/plain, */*",
            "Accept-Encoding": "identity",
        },
    )
    with urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def image_key(url: str) -> str:
    return urlparse(url).path.rsplit("/", 1)[-1].split(".", 1)[0]


def derive_mixin_key(img_key: str, sub_key: str) -> str:
    raw = img_key + sub_key
    return "".join(raw[index] for index in WBI_MIXIN_KEY_ENC_TAB if index < len(raw))[:32]


def sign_wbi(parameters: dict[str, str], mixin_key: str, timestamp: int | None = None) -> str:
    values = dict(parameters)
    values["wts"] = str(int(time.time()) if timestamp is None else timestamp)
    cleaned = {
        key: INVALID_WBI_CHARS.sub("", str(value))
        for key, value in values.items()
    }
    canonical = "&".join(
        f"{quote_plus(key)}={quote_plus(cleaned[key])}"
        for key in sorted(cleaned)
    )
    signature = hashlib.md5((canonical + mixin_key).encode("utf-8")).hexdigest()
    return canonical + "&w_rid=" + signature


def checked_data(root: dict, key: str = "data") -> dict:
    code = root.get("code", 0)
    if code != 0:
        raise RuntimeError(f"Bilibili API code={code}: {root.get('message', 'unknown error')}")
    value = root.get(key)
    if not isinstance(value, dict):
        raise RuntimeError("Bilibili API response did not contain an object payload")
    return value


def run_probe(bvid: str, page_number: int) -> dict:
    if not BVID_PATTERN.fullmatch(bvid):
        raise ValueError("BV number must look like BV1xxxxxxxxx (12 characters total)")
    if page_number < 1:
        raise ValueError("page number must be greater than zero")

    # No Cookie header is sent anywhere in this script. This intentionally
    # measures the anonymous preview path rather than an authenticated session.
    view_data = checked_data(
        request_json("https://api.bilibili.com/x/web-interface/view?" + urlencode({"bvid": bvid}))
    )
    pages = view_data.get("pages") or []
    if not pages:
        cid = int(view_data.get("cid") or 0)
    else:
        if page_number > len(pages):
            raise ValueError(f"Video has only {len(pages)} part(s); requested P{page_number}")
        cid = int(pages[page_number - 1].get("cid") or 0)
    if cid <= 0:
        raise RuntimeError("Could not resolve a valid CID for the selected part")

    nav = checked_data(request_json("https://api.bilibili.com/x/web-interface/nav"))
    wbi = nav.get("wbi_img") or {}
    img_key = image_key(wbi.get("img_url", ""))
    sub_key = image_key(wbi.get("sub_url", ""))
    if not img_key or not sub_key:
        raise RuntimeError("Bilibili did not return WBI image keys")
    mixin_key = derive_mixin_key(img_key, sub_key)

    parameters = {
        "bvid": bvid,
        "cid": str(cid),
        "qn": "80",
        "fnval": "4048",
        "fourk": "1",
        "platform": "pc",
        "web_location": "1315873",
        "try_look": "1",
    }
    signed_query = sign_wbi(parameters, mixin_key)
    playurl = checked_data(
        request_json("https://api.bilibili.com/x/player/wbi/playurl?" + signed_query)
    )

    dash = playurl.get("dash") or {}
    video_tracks = dash.get("video") or []
    returned_ids = sorted(
        {int(item.get("id") or 0) for item in video_tracks if int(item.get("id") or 0) > 0},
        reverse=True,
    )
    accepted = [int(value) for value in (playurl.get("accept_quality") or [])]
    support_formats = [
        {
            "quality": int(item.get("quality") or 0),
            "description": item.get("new_description") or item.get("display_desc") or "",
        }
        for item in (playurl.get("support_formats") or [])
    ]
    has_1080p_stream = any(int(item.get("id") or 0) == 80 for item in video_tracks)
    return {
        "bvid": bvid,
        "part": page_number,
        "cid": cid,
        "title": view_data.get("title", ""),
        "anonymous_request": True,
        "request_parameters": {
            "qn": 80,
            "fnval": 4048,
            "platform": "pc",
            "try_look": 1,
        },
        "dash_video_quality_ids": returned_ids,
        "accept_quality": accepted,
        "support_formats": support_formats,
        "has_1080p_dash_stream": has_1080p_stream,
        "message": "1080P DASH stream returned" if has_1080p_stream else
            "API responded, but did not return a 1080P DASH video track for this video/part",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bvid", required=True, help="Public Bilibili BV number")
    parser.add_argument("--page", type=int, default=1, help="1-based multipart page number")
    parser.add_argument(
        "--require-1080p",
        action="store_true",
        help="Return exit code 3 when the API does not return a DASH video track with id=80",
    )
    args = parser.parse_args()

    try:
        result = run_probe(args.bvid, args.page)
    except (HTTPError, URLError, TimeoutError, ValueError, RuntimeError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False, indent=2))
        return 2

    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.require_1080p and not result["has_1080p_dash_stream"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
