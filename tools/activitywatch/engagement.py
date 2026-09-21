"""Whether the top-bar menu is used at all, measured rather than assumed.

Always-visible displays tend to fade from attention once their novelty wears off. The indicator records each day it ran
and each time its menu was opened (the shell sends an "opened" event on the menu's root), in the same state folder as
phone.json. `nocturne_aw.py status` reports it. After two weeks, a menu opened on only a few days means the text is not
being read: the moon alone, with its not-recording warning, would be enough.
"""

from __future__ import annotations

import json
import threading
from datetime import date, timedelta
from pathlib import Path

from tonight import state_dir

# The indicator's refresh thread and its menu events both write; one at a time.
_lock = threading.Lock()


def _path(path: Path | None) -> Path:
    return path or state_dir() / "indicator.json"


def _load(path: Path) -> dict:
    try:
        data = json.loads(path.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(data, sort_keys=True))
    temp.replace(path)


def record(day: date, opened: bool, path: Path | None = None) -> None:
    """Marks [day] as a day the indicator ran, and counts an opening of its menu when [opened]."""
    path = _path(path)
    with _lock:
        data = _load(path)
        key = day.isoformat()
        data[key] = data.get(key, 0) + (1 if opened else 0)
        _save(path, data)


def summary(today: date, days: int = 14, path: Path | None = None) -> tuple[int, int, int]:
    """(days the indicator ran, days its menu was opened, openings) over the last [days] days."""
    data = _load(_path(path))
    keys = {(today - timedelta(days=i)).isoformat() for i in range(days)}
    ran = [data[k] for k in keys if k in data]
    return len(ran), sum(1 for n in ran if n > 0), sum(ran)
