"""The owner's profile: height, sex, date of birth and Jurca's self-reported activity level.

Energy, cardio load, strain, sleep need, VO2max and recovery's sleep part all read it, and
not only for the day it was set: date of birth sets every past day's age and so its sleep
need, height every day's BMI. Changing any field therefore re-derives the whole history, the
same way a weight re-derives from its date onward (``journal.py``). Weight itself is a
time series and stays in the journal; the profile only reports the latest one, so the
screen can say whether the one remaining input is there.
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from typing import Literal
from uuid import UUID

from psycopg import Connection
from pydantic import BaseModel, Field, model_validator

from strap_server.derive._common import _age
from strap_server.derive.srpa import SRPA_MAX, SRPA_MIN
from strap_server.rederive import rederive

FIELDS = ("height_cm", "sex", "dob", "srpa")


class ProfileIn(BaseModel):
    """The whole profile; a field left null is cleared (and withholds what needs it)."""

    height_cm: float | None = Field(default=None, ge=100, le=250)
    sex: Literal["male", "female"] | None = None
    dob: date | None = None
    srpa: int | None = Field(default=None, ge=SRPA_MIN, le=SRPA_MAX)

    @model_validator(mode="after")
    def plausible(self) -> ProfileIn:
        if self.dob is not None and (self.dob < date(1900, 1, 1) or _age(self.dob, datetime.now(UTC).date()) < 13):
            raise ValueError("implausible date of birth")
        return self


def get(conn: Connection, user_id: UUID) -> dict:
    """The stored profile (nulls where unset) and the latest logged weight, if any."""
    row = conn.execute("SELECT height_cm, sex, dob, srpa, updated_at FROM profile WHERE user_id = %s", (user_id,)).fetchone()
    weight = conn.execute("SELECT kg, ts FROM weight_log WHERE user_id = %s ORDER BY ts DESC LIMIT 1", (user_id,)).fetchone()
    height, sex, dob, srpa, updated = row or (None, None, None, None, None)
    return {
        "height_cm": None if height is None else float(height),
        "sex": sex,
        "dob": None if dob is None else dob.isoformat(),
        "srpa": srpa,
        "updated_at": None if updated is None else int(updated.timestamp() * 1000),
        "latest_weight": None if weight is None else {"kg": float(weight[0]), "ts": int(weight[1].timestamp() * 1000)},
    }


def put(conn: Connection, conninfo: str | None, user_id: UUID, profile: ProfileIn) -> dict:
    """Stores the profile; when anything changed, re-derives every day (after commit)."""
    before = get(conn, user_id)
    conn.execute(
        "INSERT INTO profile (user_id, height_cm, sex, dob, srpa) VALUES (%s, %s, %s, %s, %s) "
        "ON CONFLICT (user_id) DO UPDATE SET height_cm = EXCLUDED.height_cm, sex = EXCLUDED.sex, "
        "dob = EXCLUDED.dob, srpa = EXCLUDED.srpa, updated_at = now()",
        (user_id, profile.height_cm, profile.sex, profile.dob, profile.srpa),
    )
    conn.commit()
    after = get(conn, user_id)
    changed = any(before[f] != after[f] for f in FIELDS)
    days = rederive(conninfo, user_id, log=lambda _: None) if changed else 0
    return {**after, "rederived_days": days}
