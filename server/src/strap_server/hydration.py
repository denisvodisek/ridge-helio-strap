"""Water for one day, and whether to remind (docs/denis/SPEC.md S8).

The totals are the log, summed. The average is only over days that have a log. The
reminder names a workout's calories and a hot day's high; it never invents a volume to drink.
Heat needs a home area and Open-Meteo. Without either, that part is withheld.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from uuid import UUID

import httpx
from psycopg import Connection
from pydantic import BaseModel, Field

from strap_server.derive._common import _day_bounds_utc
from strap_server.journal import DEFAULT_SUPPLEMENTS, SUPPLEMENT_LIMITS
from strap_server.read.calories import calorie_card
from strap_server.sessions import VISIBLE_WORKOUT_SQL

# A day whose forecast high reaches this is "hot" for the reminder. Ours: a fixed gate so
# the word means the same thing every day, not a heat-health warning (those depend on place).
HOT_DAY_C = 30.0
AVERAGE_WINDOW_DAYS = 28
AVERAGE_MIN_DAYS = 3
# The quick amounts on the water card, millilitres. 1000 is shown as 1 L.
WATER_AMOUNTS_ML = (100, 200, 300, 400, 500, 750, 1000)
OPEN_METEO = "https://api.open-meteo.com/v1/forecast"
_HEAT_NOTES = {
    "no_home": "Heat isn't included until a home area is set.",
    "weather_unavailable": "Weather didn't answer, so heat isn't included.",
}
# One high per rounded home and local day. A failed fetch is not cached.
_MAX_CACHE: dict[tuple[float, float, str, str], float] = {}


class HomeIn(BaseModel):
    """Coarse coordinates for the heat limb. Not a body measurement, and not re-derived."""

    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)


def set_home(conn: Connection, user_id: UUID, home: HomeIn) -> None:
    """Stores the home area on the profile row without touching the science fields."""
    conn.execute(
        "INSERT INTO profile (user_id, home_lat, home_lon) VALUES (%s, %s, %s) "
        "ON CONFLICT (user_id) DO UPDATE SET home_lat = EXCLUDED.home_lat, home_lon = EXCLUDED.home_lon",
        (user_id, home.lat, home.lon),
    )


def clear_home(conn: Connection, user_id: UUID) -> None:
    conn.execute("UPDATE profile SET home_lat = NULL, home_lon = NULL WHERE user_id = %s", (user_id,))


def average_of(daily_ml: list[float]) -> dict:
    """Mean of the daily totals handed in. Fewer than three days is withheld, never shown as a thin mean."""
    days = [round(v) for v in daily_ml]
    window = AVERAGE_WINDOW_DAYS
    if len(days) < AVERAGE_MIN_DAYS:
        return {
            "withheld": "water_average_learning",
            "have": len(days),
            "need": AVERAGE_MIN_DAYS,
            "window_days": window,
            "line": f"Average appears after {AVERAGE_MIN_DAYS} days with water logged ({len(days)} so far).",
        }
    ml = round(sum(days) / len(days))
    return {
        "ml": ml,
        "days": len(days),
        "window_days": window,
        "line": f"Average {ml} ml on the {len(days)} days you logged in the {window} days before today.",
    }


def build_reminder(
    *,
    today_ml: int,
    average_ml: int | None,
    average_days: int | None,
    workouts: int,
    workout_kcal: int | None,
    workout_withheld: str | None,
    temp_max_c: float | None,
    heat_withheld: str | None,
) -> dict:
    """The reminder card. `show` is false on a cool day with no workout, and the text never names a target."""
    if heat_withheld:
        temp_max_c = None
    hot = temp_max_c is not None and temp_max_c >= HOT_DAY_C
    parts: list[str] = []
    if workouts > 0:
        if workout_kcal is not None and workout_kcal >= 1:
            parts.append(f"Workouts added {workout_kcal} kcal today.")
        elif workout_withheld:
            parts.append("You trained today. The calorie total isn't available.")
        else:
            parts.append("You trained today.")
    if hot and temp_max_c is not None:
        parts.append(f"The high is {_temp(temp_max_c)}°C.")
    show = bool(parts)
    if show:
        logged = "No water logged today" if today_ml == 0 else f"{today_ml} ml logged today"
        if average_ml is None or average_days is None:
            parts.append(logged + ".")
        else:
            parts.append(f"{logged}, against {average_ml} ml on the {average_days} days you logged.")
        if workouts > 0 and hot:
            parts.append("Exercise and heat both raise sweat loss. This is a reminder, not a target.")
        elif workouts > 0:
            parts.append("Exercise raises sweat loss. This is a reminder, not a target.")
        else:
            parts.append("Heat raises sweat loss. This is a reminder, not a target.")
    return {
        "show": show,
        "text": " ".join(parts) if show else None,
        "heat": hot,
        "heat_withheld": heat_withheld,
        "heat_note": None if hot else _HEAT_NOTES.get(heat_withheld or ""),
        "workouts": workouts,
        "workout_kcal": workout_kcal,
        "workout_withheld": workout_withheld,
    }


def summary(conn: Connection, user_id: UUID, tz: str, day: date, now: datetime, day_max) -> dict:
    """Today's water, the average over logged days before it, the supplement chips, and the reminder."""
    start, end = _day_bounds_utc(day, tz)
    window_start = _day_bounds_utc(day - timedelta(days=AVERAGE_WINDOW_DAYS), tz)[0]
    with conn.cursor() as cur:
        cur.execute(
            "SELECT (ts AT TIME ZONE %s)::date, SUM(amount) FROM manual_entry "
            "WHERE user_id = %s AND kind = 'water' AND ts >= %s AND ts < %s GROUP BY 1",
            (tz, user_id, window_start, end),
        )
        by_day = {d: float(total) for d, total in cur.fetchall()}
        cur.execute(
            "SELECT (SELECT count(*) FROM session WHERE user_id = %s AND start_ts >= %s AND start_ts < %s) "
            "+ (SELECT count(*) FROM workout WHERE user_id = %s AND start_ts >= %s AND start_ts < %s "
            f"AND duration_s > 0 AND {VISIBLE_WORKOUT_SQL})",
            (user_id, start, end, user_id, start, end),
        )
        workouts = int(cur.fetchone()[0])
        card = calorie_card(cur, user_id, tz, day, now) if workouts else None
        cur.execute(
            "SELECT DISTINCT ON (lower(btrim(name))) btrim(name), unit, amount FROM manual_entry "
            "WHERE user_id = %s AND kind = 'supplement' AND name IS NOT NULL AND btrim(name) <> '' "
            "ORDER BY lower(btrim(name)), ts DESC",
            (user_id,),
        )
        logged = cur.fetchall()
    home = conn.execute("SELECT home_lat, home_lon FROM profile WHERE user_id = %s", (user_id,)).fetchone()
    lat, lon = (None, None) if home is None else home
    if lat is None or lon is None:
        temp, heat_withheld = None, "no_home"
    else:
        temp = day_max(float(lat), float(lon), day, tz)
        heat_withheld = None if temp is not None else "weather_unavailable"
    if workouts == 0:
        workout_kcal, workout_withheld = None, None
    elif card is None:
        workout_kcal, workout_withheld = None, "no_calorie_total"
    else:
        workout_kcal, workout_withheld = int(card["parts"]["workouts"]), None
    today_ml = round(by_day.get(day, 0.0))
    # Days with no log are absent, so they are not zeros. Today is left out of the average
    # so the reminder can compare it with earlier days.
    average = average_of([total for d, total in by_day.items() if d < day and total > 0])
    return {
        "day": day.isoformat(),
        "today_ml": today_ml,
        "amounts_ml": list(WATER_AMOUNTS_ML),
        "average": average,
        "reminder": build_reminder(
            today_ml=today_ml,
            average_ml=average.get("ml"),
            average_days=average.get("days"),
            workouts=workouts,
            workout_kcal=workout_kcal,
            workout_withheld=workout_withheld,
            temp_max_c=temp,
            heat_withheld=heat_withheld,
        ),
        "home_set": lat is not None and lon is not None,
        "supplements": _catalog(logged),
    }


def fetch_day_max_c(lat: float, lon: float, day: date, tz: str, client: httpx.Client) -> float | None:
    """The local day's forecast maximum, °C, from Open-Meteo. None when it doesn't answer."""
    key = (round(lat, 2), round(lon, 2), day.isoformat(), tz)
    if key in _MAX_CACHE:
        return _MAX_CACHE[key]
    try:
        response = client.get(
            OPEN_METEO,
            params={
                "latitude": lat,
                "longitude": lon,
                "daily": "temperature_2m_max",
                "timezone": tz,
                "start_date": day.isoformat(),
                "end_date": day.isoformat(),
            },
        )
        response.raise_for_status()
        values = response.json()["daily"]["temperature_2m_max"]
        if not values or values[0] is None:
            return None
        temp = float(values[0])
    except (httpx.HTTPError, KeyError, TypeError, ValueError, IndexError):
        return None
    _MAX_CACHE[key] = temp
    return temp


def live_day_max(lat: float, lon: float, day: date, tz: str) -> float | None:
    """`fetch_day_max_c` on a short-lived client. The only place coordinates leave the server."""
    with httpx.Client(timeout=4.0) as client:
        return fetch_day_max_c(lat, lon, day, tz, client)


def _catalog(rows: list[tuple]) -> list[dict]:
    """Default names first, then names the owner has logged that aren't already there."""
    by_lower = {name.lower(): (name, unit, amount) for name, unit, amount in rows}
    out = []
    seen = set()
    for name, unit in DEFAULT_SUPPLEMENTS:
        seen.add(name.lower())
        hit = by_lower.get(name.lower())
        theirs = hit is not None and hit[1] in SUPPLEMENT_LIMITS
        out.append(
            {
                "name": name,
                "unit": hit[1] if theirs else unit,
                "custom": False,
                "last_amount": None if not theirs else float(hit[2]),
            }
        )
    customs = [
        {"name": name, "unit": unit, "custom": True, "last_amount": float(amount)}
        for name, unit, amount in rows
        if name.lower() not in seen and unit in SUPPLEMENT_LIMITS
    ]
    return out + sorted(customs, key=lambda s: s["name"].lower())


def _temp(value: float) -> str:
    rounded = round(value, 1)
    return str(int(rounded)) if rounded == int(rounded) else f"{rounded:.1f}"
