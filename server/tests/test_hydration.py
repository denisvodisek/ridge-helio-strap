"""Water, supplements and the drink reminder (docs/denis/SPEC.md S8)."""

from __future__ import annotations

from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo

import httpx
import psycopg
import pytest
from pydantic import ValidationError

from strap_server import hydration, journal, profile
from strap_server.derive import derive_day, derive_night
from tests.derive import _seed

IST = ZoneInfo("Asia/Kolkata")


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
        for start, end in _seed.nights():
            derive_night(cur, _seed.OWNER, "Asia/Kolkata", start, end)
        for day in _seed.DAYS:
            derive_day(cur, _seed.OWNER, "Asia/Kolkata", day)
    return test_dsn


@pytest.fixture(autouse=True)
def _clear_weather_cache() -> None:
    hydration._MAX_CACHE.clear()


def _at(day: date, ml: float) -> journal.JournalIn:
    return journal.JournalIn(
        kind="water", ts=datetime(day.year, day.month, day.day, 9, 0, tzinfo=IST), amount=ml
    )


def test_average_waits_for_three_logged_days_and_then_means_them() -> None:
    thin = hydration.average_of([400, 500])
    assert thin["withheld"] == "water_average_learning" and thin["have"] == 2 and thin["need"] == 3
    assert "2 so far" in thin["line"]
    full = hydration.average_of([400, 500, 600])
    assert full["ml"] == 500 and full["days"] == 3
    assert "withheld" not in full


def test_a_cool_rest_day_does_not_remind() -> None:
    out = hydration.build_reminder(
        today_ml=0,
        average_ml=None,
        average_days=None,
        workouts=0,
        workout_kcal=None,
        workout_withheld=None,
        temp_max_c=29.9,
        heat_withheld=None,
    )
    assert out["show"] is False and out["heat"] is False and out["text"] is None and out["heat_note"] is None


def test_thirty_is_hot_and_the_reminder_sets_no_target() -> None:
    out = hydration.build_reminder(
        today_ml=300,
        average_ml=1800,
        average_days=10,
        workouts=1,
        workout_kcal=420,
        workout_withheld=None,
        temp_max_c=30,
        heat_withheld=None,
    )
    assert out["show"] is True and out["heat"] is True
    text = out["text"]
    assert text is not None
    assert "420 kcal" in text and "30°C" in text and "300 ml" in text and "1800 ml" in text
    assert "not a target" in text
    assert "more" not in text.split("reminder")[0]


def test_missing_weather_and_missing_calories_invent_neither() -> None:
    out = hydration.build_reminder(
        today_ml=0,
        average_ml=None,
        average_days=None,
        workouts=1,
        workout_kcal=None,
        workout_withheld="no_calorie_total",
        temp_max_c=35,
        heat_withheld="no_home",
    )
    assert out["show"] is True and out["heat"] is False and out["heat_note"]
    text = out["text"] or ""
    assert "kcal" not in text and "°C" not in text
    assert "isn't available" in text
    assert "Heat isn't included" in out["heat_note"]


def test_a_workout_without_a_positive_calorie_total_does_not_invent_one() -> None:
    out = hydration.build_reminder(
        today_ml=100,
        average_ml=None,
        average_days=None,
        workouts=1,
        workout_kcal=0,
        workout_withheld=None,
        temp_max_c=None,
        heat_withheld="weather_unavailable",
    )
    assert "kcal" not in (out["text"] or "")
    assert "You trained today." in (out["text"] or "")
    assert out["heat_note"] == hydration._HEAT_NOTES["weather_unavailable"]


def test_open_meteo_returns_the_day_high_and_a_failure_is_not_a_temperature() -> None:
    def ok(request: httpx.Request) -> httpx.Response:
        assert request.url.params["daily"] == "temperature_2m_max"
        assert request.url.params["timezone"] == "Asia/Singapore"
        return httpx.Response(200, json={"daily": {"time": ["2026-10-08"], "temperature_2m_max": [31.2]}})

    client = httpx.Client(transport=httpx.MockTransport(ok))
    assert hydration.fetch_day_max_c(1.3521, 103.8198, date(2026, 10, 8), "Asia/Singapore", client) == 31.2
    # The rounded home is cached, so a later failure does not wipe a high we already have.
    failed = httpx.Client(transport=httpx.MockTransport(lambda _request: httpx.Response(500)))
    assert hydration.fetch_day_max_c(1.3521, 103.82, date(2026, 10, 8), "Asia/Singapore", failed) == 31.2
    hydration._MAX_CACHE.clear()
    assert hydration.fetch_day_max_c(1.35, 103.82, date(2026, 10, 8), "Asia/Singapore", failed) is None


def test_water_and_supplement_limits() -> None:
    ts = datetime(2026, 3, 5, tzinfo=UTC)
    assert journal.JournalIn(kind="water", ts=ts, amount=1000).unit is None
    dose = journal.JournalIn(
        kind="supplement", ts=ts, amount=2000, unit="iu", name="  D3  ", notes="  none  "
    )
    assert dose.unit == "IU" and dose.name == "D3" and dose.notes == "none"
    for entry in (
        {"kind": "water", "amount": 5001},
        {"kind": "supplement", "amount": 10, "unit": "mg", "name": "   "},
        {"kind": "supplement", "amount": 10, "unit": "spoon", "name": "Zinc"},
        {"kind": "caffeine", "amount": 80, "notes": "jittery"},
    ):
        with pytest.raises(ValidationError):
            journal.JournalIn(ts=ts, **entry)


@pytest.mark.db
def test_water_total_average_and_the_workout_reminder(seeded) -> None:
    day = date(2026, 3, 4)  # the seeded strap workout
    with psycopg.connect(seeded) as conn:
        for logged, ml in ((date(2026, 3, 1), 400), (date(2026, 3, 2), 500), (date(2026, 3, 3), 600)):
            journal.add(conn, seeded, _seed.OWNER, _at(logged, ml))
        journal.add(conn, seeded, _seed.OWNER, _at(day, 250))
        journal.add(conn, seeded, _seed.OWNER, _at(day, 100))
        out = hydration.summary(
            conn, _seed.OWNER, "Asia/Kolkata", day, datetime(2026, 3, 4, 18, tzinfo=UTC), lambda *_a: 33
        )
    assert out["today_ml"] == 350
    assert out["average"]["ml"] == 500 and out["average"]["days"] == 3
    assert out["amounts_ml"] == [100, 200, 300, 400, 500, 750, 1000]
    assert out["reminder"]["heat_withheld"] == "no_home"
    assert out["reminder"]["show"] is True
    assert out["reminder"]["workout_kcal"] >= 1
    text = out["reminder"]["text"]
    assert text is not None and "kcal" in text and "°C" not in text and "350 ml" in text and "500 ml" in text


@pytest.mark.db
def test_home_area_does_not_rederive_and_a_profile_save_keeps_it(seeded) -> None:
    day = date(2026, 3, 1)  # no workout in the seed
    called = {}

    def day_max(lat, lon, _day, _tz):
        called["at"] = (lat, lon)
        return 22.0

    with psycopg.connect(seeded) as conn:
        before = conn.execute(
            "SELECT value FROM derived_daily WHERE metric = 'basal_calories' AND day = '2026-03-06'"
        ).fetchone()[0]
        hydration.set_home(conn, _seed.OWNER, hydration.HomeIn(lat=1.29, lon=103.85))
    with psycopg.connect(seeded) as conn:
        after = conn.execute(
            "SELECT value FROM derived_daily WHERE metric = 'basal_calories' AND day = '2026-03-06'"
        ).fetchone()[0]
        out = hydration.summary(
            conn, _seed.OWNER, "Asia/Kolkata", day, datetime(2026, 3, 1, 18, tzinfo=UTC), day_max
        )
        profile.put(
            conn,
            seeded,
            _seed.OWNER,
            profile.ProfileIn(
                height_cm=175,
                sex="male",
                dob=date(1990, 1, 1),
                srpa=2,
            ),
        )
        home = conn.execute(
            "SELECT home_lat, home_lon FROM profile WHERE user_id = %s", (_seed.OWNER,)
        ).fetchone()
    assert after == before
    assert called["at"] == (1.29, 103.85)
    assert out["home_set"] is True and out["reminder"]["show"] is False and out["reminder"]["heat"] is False
    assert home[0] == pytest.approx(1.29) and home[1] == pytest.approx(103.85)


@pytest.mark.db
def test_supplement_rename_dose_and_custom_chip(seeded) -> None:
    ts = datetime(2026, 3, 5, 8, 0, tzinfo=UTC)
    with psycopg.connect(seeded) as conn:
        added = journal.add(
            conn,
            seeded,
            _seed.OWNER,
            journal.JournalIn(
                kind="supplement",
                ts=ts,
                amount=200,
                unit="mg",
                name="  magnesium ",
                notes=" slept well ",
            ),
        )
        journal.add(
            conn,
            seeded,
            _seed.OWNER,
            journal.JournalIn(
                kind="supplement",
                ts=ts,
                amount=5,
                unit="g",
                name="Creatine",
            ),
        )
        assert journal.update_supplement(
            conn,
            _seed.OWNER,
            added["id"],
            journal.SupplementIn(
                name="Magnesium glycinate",
                amount=300,
                unit="MG",
                notes="",
            ),
        )
        listed = journal.entries(
            conn, _seed.OWNER, datetime(2026, 3, 5, tzinfo=UTC), datetime(2026, 3, 6, tzinfo=UTC)
        )
        out = hydration.summary(
            conn,
            _seed.OWNER,
            "Asia/Kolkata",
            date(2026, 3, 5),
            datetime(2026, 3, 5, 12, tzinfo=UTC),
            lambda *_a: None,
        )
        assert journal.delete(conn, _seed.OWNER, added["id"])
    entry = next(e for e in listed if e["id"] == added["id"])
    assert (
        entry["name"] == "Magnesium glycinate"
        and entry["amount"] == 300
        and entry["unit"] == "mg"
        and entry["notes"] is None
    )
    chips = {s["name"]: s for s in out["supplements"]}
    assert chips["Magnesium"]["custom"] is False and chips["Magnesium"]["last_amount"] is None
    assert chips["Creatine"]["custom"] is True and chips["Creatine"]["last_amount"] == 5
    assert chips["Magnesium glycinate"]["unit"] == "mg" and chips["Magnesium glycinate"]["last_amount"] == 300
