"""The profile endpoint: read, replace, and the whole-history re-derive a change sets off."""

from __future__ import annotations

import hashlib
import uuid
from datetime import UTC, date, datetime

import psycopg
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from strap_server import api, config, profile
from strap_server.derive import derive_day, derive_night
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


def _basal(conn, day: date) -> float | None:
    row = conn.execute("SELECT value FROM derived_daily WHERE metric = 'basal_calories' AND day = %s", (day,)).fetchone()
    return row and row[0]


def test_get_returns_the_profile_and_the_latest_weight(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        out = profile.get(conn, _seed.OWNER)
    assert (out["height_cm"], out["sex"], out["dob"], out["srpa"]) == (175.0, "male", "1990-01-01", 2)
    assert out["latest_weight"]["kg"] == _seed.WEIGHT_KG


def test_a_changed_height_rederives_every_day(seeded) -> None:
    first, last = _seed.DAYS[0], _seed.DAYS[-1]
    with psycopg.connect(seeded) as conn:
        before = (_basal(conn, first), _basal(conn, last))
        out = profile.put(conn, seeded, _seed.OWNER, profile.ProfileIn(height_cm=185, sex="male", dob=date(1990, 1, 1), srpa=2))
    with psycopg.connect(seeded) as conn:
        after = (_basal(conn, first), _basal(conn, last))
    assert out["rederived_days"] >= len(_seed.DAYS)
    assert out["height_cm"] == 185.0
    # Mifflin-St Jeor: 6.25 kcal per cm, and the first day moves too (not only "from today").
    assert after == pytest.approx((before[0] + 62.5, before[1] + 62.5))


def test_an_unchanged_profile_does_not_rederive(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        out = profile.put(conn, seeded, _seed.OWNER, profile.ProfileIn(height_cm=175, sex="male", dob=date(1990, 1, 1), srpa=2))
    assert out["rederived_days"] == 0


def test_clearing_a_field_withholds_what_needs_it(seeded) -> None:
    day = _seed.DAYS[-1]
    with psycopg.connect(seeded) as conn:
        profile.put(conn, seeded, _seed.OWNER, profile.ProfileIn(height_cm=None, sex="male", dob=date(1990, 1, 1), srpa=2))
    with psycopg.connect(seeded) as conn:
        assert _basal(conn, day) is None  # no height, no BMR: withheld, never a default
        assert profile.get(conn, _seed.OWNER)["height_cm"] is None


def test_implausible_profiles_are_refused() -> None:
    for bad in ({"height_cm": 40}, {"height_cm": 300}, {"srpa": 5}, {"srpa": -1}, {"sex": "other"},
                {"dob": "1850-01-01"}, {"dob": datetime.now(UTC).date().isoformat()}):
        with pytest.raises(ValidationError):
            profile.ProfileIn(**bad)


def test_profile_over_http_on_a_fresh_server(db, test_dsn, monkeypatch) -> None:
    """A profile set before the first sync must create the owner row, like a weight does."""
    owner, token = uuid.uuid4(), "profile-test-token"
    monkeypatch.setenv("DEVICE_TOKEN_SHA256", hashlib.sha256(token.encode()).hexdigest())
    monkeypatch.setenv("OWNER_ID", str(owner))
    parts = dict(p.split("=", 1) for p in test_dsn.split())
    for key in ("host", "port", "dbname", "user", "password"):
        monkeypatch.setenv({"dbname": "POSTGRES_DB"}.get(key, f"POSTGRES_{key.upper()}"), parts[key])
    config.get_settings.cache_clear()
    try:
        client, auth = TestClient(api.app), {"Authorization": f"Bearer {token}"}
        assert client.get("/v1/profile").status_code == 401
        empty = client.get("/v1/profile", headers=auth).json()
        assert empty["height_cm"] is None and empty["latest_weight"] is None
        r = client.put("/v1/profile", json={"height_cm": 180, "sex": "female", "dob": "1988-05-04", "srpa": 3}, headers=auth)
        assert r.status_code == 200, r.text
        assert r.json()["rederived_days"] == 0  # no raw data yet: nothing to re-derive
        assert client.get("/v1/profile", headers=auth).json()["sex"] == "female"
        assert client.put("/v1/profile", json={"srpa": 9}, headers=auth).status_code == 422
        with psycopg.connect(test_dsn) as conn:
            conn.execute("DELETE FROM app_user WHERE id = %s", (owner,))
    finally:
        config.get_settings.cache_clear()
