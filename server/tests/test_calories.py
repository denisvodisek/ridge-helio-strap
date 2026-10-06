"""Calories by source (SPEC S6): the parts add up to the stored total, and a running day stops at now."""

from __future__ import annotations

from datetime import timedelta

import psycopg
import pytest

from strap_server.derive import derive_day, derive_night
from strap_server.read.calories import calorie_card
from tests.derive import _seed

pytestmark = pytest.mark.db
TZ = "Asia/Kolkata"


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
        for start, end in _seed.nights():
            derive_night(cur, _seed.OWNER, TZ, start, end)
        for day in _seed.DAYS:
            derive_day(cur, _seed.OWNER, TZ, day)
    return test_dsn


def _stored(cur, day, metric):
    cur.execute("SELECT value FROM derived_daily WHERE user_id = %s AND day = %s AND metric = %s", (_seed.OWNER, day, metric))
    return cur.fetchone()[0]


def test_a_finished_day_splits_its_stored_total(seeded) -> None:
    with psycopg.connect(seeded) as conn, conn.cursor() as cur:
        for day in _seed.DAYS:
            card = calorie_card(cur, _seed.OWNER, TZ, day, _seed._local(day, 0, 0) + timedelta(days=2))
            parts = card["parts"]
            assert not card["so_far"] and card["until"] is None and card["day_estimate"] is None
            assert card["total"] == pytest.approx(_stored(cur, day, "total_calories"), abs=1)
            assert card["base"] == round(_stored(cur, day, "basal_calories"))
            assert card["base"] + sum(parts.values()) == pytest.approx(card["total"], abs=2)  # each part rounded on its own
            assert parts["steps"] > 0  # the seed walks every day


def test_the_workout_counts_the_straps_calories_less_its_resting_share(seeded) -> None:
    """The seed's one workout: 30 min, 200 kcal from the strap. Its base share is already in `base`."""
    day = _seed.DAYS[3]
    with psycopg.connect(seeded) as conn, conn.cursor() as cur:
        card = calorie_card(cur, _seed.OWNER, TZ, day, _seed._local(day, 0, 0) + timedelta(days=2))
        bmr = _stored(cur, day, "basal_calories")
    assert card["workouts_n"] == 1
    assert card["parts"]["workouts"] == round(200 - 30 * bmr / 1440)


def test_a_running_day_counts_only_up_to_now(seeded) -> None:
    day = _seed.DAYS[-1]
    noon = _seed._local(day, 12, 0)
    with psycopg.connect(seeded) as conn, conn.cursor() as cur:
        card = calorie_card(cur, _seed.OWNER, TZ, day, noon + timedelta(seconds=40))
        bmr = _stored(cur, day, "basal_calories")
        whole = _stored(cur, day, "total_calories")
        assert calorie_card(cur, _seed.OWNER, TZ, day, _seed._local(day, 0, 0) - timedelta(minutes=1)) is None  # not started yet
        assert calorie_card(cur, _seed.OWNER, TZ, day + timedelta(days=30), noon) is None  # nothing derived
    assert card["so_far"] and card["until"] == int(noon.timestamp() * 1000)
    assert card["base"] == round(bmr / 2)  # 720 of 1440 minutes
    assert card["total"] < whole  # the stored total counts the afternoon still to come
    assert card["day_estimate"] == round(whole)
