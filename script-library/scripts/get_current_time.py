#!/usr/bin/env python3
"""Current time -- the simplest action script, a format sample.

Contract of library scripts:
  * language -- Python 3 only;
  * input parameters arrive as one JSON object on stdin;
  * the result is one JSON object on stdout;
  * errors -- as text on stderr with a non-zero exit code.
"""
import json
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

def main() -> int:
    raw = sys.stdin.read().strip()
    params = json.loads(raw) if raw else {}

    tz_name = params.get("timezone")
    now = datetime.now(ZoneInfo(tz_name)) if tz_name else datetime.now().astimezone()

    json.dump(
        {
            "iso": now.isoformat(),
            "human": now.strftime("%d.%m.%Y %H:%M:%S"),
            "timezone": str(now.tzinfo),
        },
        sys.stdout,
        ensure_ascii=False,
    )
    return 0

if __name__ == "__main__":
    sys.exit(main())
