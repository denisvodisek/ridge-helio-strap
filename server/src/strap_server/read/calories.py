"""Calories by source for one day: the resting base, then steps, everyday movement and
workouts on top of it (ours, docs/denis/SPEC.md S6).

No new science: the same minute walk as `derive/energy.py`, with `energy._minute_met` and
the stored BMR and stride, each minute's energy filed by what the minute was. So for a
finished day the parts add up to the stored `total_calories`. A day still running is walked
only up to the current minute; the stored total counts the rest of the day as seated time,
which a tracker can't show as burned.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from uuid import UUID

from psycopg import Cursor

from strap_server.derive._common import _day_bounds_utc, _day_minutes
from strap_server.derive.energy import _minute_met


def calorie_card(cur: Cursor, user_id: UUID, tz: str, day, now: datetime) -> dict | None:
    """Base, the three sources on top of it and their total; None when the day has no calories derived."""
    cur.execute(
        "SELECT metric, value, flags FROM derived_daily WHERE user_id = %s AND day = %s "
        "AND metric IN ('total_calories', 'basal_calories')",
        (user_id, day),
    )
    rows = {m: (v, f) for m, v, f in cur.fetchall()}
    start, end = _day_bounds_utc(day, tz)
    if len(rows) < 2 or now < start:
        return None
    flags = rows["total_calories"][1]
    bmr = rows["basal_calories"][0]
    bmr_min = bmr / 1440.0
    running = now < end
    stop = now.replace(second=0, microsecond=0) if running else end

    # The same three reads as `energy._tee_met`, over the same whole-day bounds.
    cur.execute("SELECT start_ts, end_ts FROM sleep_session WHERE user_id = %s AND end_ts >= %s AND start_ts < %s", (user_id, start, end))
    sleep_wins = cur.fetchall()
    cur.execute("SELECT start_ts, duration_s, calories FROM workout WHERE user_id = %s AND start_ts >= %s AND start_ts < %s", (user_id, start, end))
    workouts = [(s, s + timedelta(seconds=int(d or 0)), c) for s, d, c in cur.fetchall()]
    cur.execute(
        "SELECT date_trunc('minute', ts) m, SUM(value) FROM sample WHERE user_id = %s AND metric = 'steps_per_minute' "
        "AND value < 250 AND ts >= %s AND ts < %s GROUP BY m",
        (user_id, start, end),
    )
    steps_by_min = {r[0]: float(r[1]) for r in cur.fetchall()}

    def asleep(m: datetime) -> bool:
        return any(s <= m < e for s, e in sleep_wins)

    stride = flags["stride_m"]
    base_m = start.replace(second=0, microsecond=0)
    minutes = _day_minutes(start, stop)
    steps = movement = 0.0
    workout_min = 0
    for i in range(minutes):
        m = base_m + timedelta(minutes=i)
        if any(s <= m < e for s, e, _ in workouts):
            workout_min += 1
            continue
        extra = (_minute_met(m, steps_by_min, stride, asleep) - 1) * bmr_min
        if steps_by_min.get(m, 0.0) > 0:
            steps += extra
        else:
            movement += extra
    started = [w for w in workouts if w[0] < stop]
    workout_kcal = sum(float(c or 0) for _, _, c in started) - workout_min * bmr_min
    base = minutes * bmr_min
    total = base + steps + movement + workout_kcal
    return {
        "total": round(total),
        "base": round(base),
        "active": round(total - base),
        "parts": {"steps": round(steps), "movement": round(movement), "workouts": round(workout_kcal)},
        "workouts_n": len(started),
        "so_far": running,
        "until": int(stop.timestamp() * 1000) if running else None,
        # A running day's estimate for the whole day: the stored total, which walks the hours still
        # to come as quiet time (seated or asleep), so it is what the day reaches without more activity.
        "day_estimate": round(rows["total_calories"][0]) if running else None,
        "weight_kg": flags.get("weight_kg"),
        "caveats": flags.get("caveats", []),
    }
