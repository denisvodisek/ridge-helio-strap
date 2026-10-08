"""The journal: caffeine, alcohol, water, supplements and weight logged by the owner.

Caffeine, alcohol, water and supplements go to `manual_entry` (caffeine and alcohol are the
kinds the correlation and cut-off analytics read). Weight goes to `weight_log`, and because
BMR, calories and the Jurca VO2max read the weight in force on each day, logging one
re-derives every day from its date onward. Water and supplements do not: they are a log,
read back as written (docs/denis/SPEC.md S8).
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal
from uuid import UUID
from zoneinfo import ZoneInfo

from psycopg import Connection
from pydantic import BaseModel, Field, model_validator

from strap_server.rederive import rederive

# Units are fixed per kind so entries stay comparable. A supplement picks one of four.
UNITS = {"caffeine": "mg", "alcohol": "drinks", "weight": "kg", "water": "ml"}
WATER_MAX_ML = 5_000.0
SUPPLEMENT_LIMITS = {"mg": 50_000.0, "mcg": 100_000.0, "g": 100.0, "IU": 100_000.0}
# Names the journal offers before the owner has typed their own (S8). The unit is the
# usual one for that substance, not a dose: the dose is whatever they log.
DEFAULT_SUPPLEMENTS = (
    ("Magnesium", "mg"),
    ("D3", "IU"),
    ("K", "mcg"),
    ("B12", "mcg"),
    ("Biotin", "mcg"),
    ("Zinc", "mg"),
    ("Vitamin C", "mg"),
    ("Collagen", "g"),
    ("Omega-3", "mg"),
)
_MANUAL = ("caffeine", "alcohol", "water", "supplement")


def clean_supplement(
    name: str | None, amount: float, unit: str | None, notes: str | None
) -> tuple[str, float, str, str | None]:
    """A comparable dose: a name, a positive amount, and one of the four units."""
    cleaned = " ".join((name or "").split())
    if not cleaned or len(cleaned) > 80:
        raise ValueError("a supplement needs a name")
    canonical = {u.lower(): u for u in SUPPLEMENT_LIMITS}
    chosen = canonical.get((unit or "").lower())
    if chosen is None or amount > SUPPLEMENT_LIMITS[chosen]:
        raise ValueError("implausible supplement amount" if chosen else "unknown supplement unit")
    note = " ".join((notes or "").split()) or None
    if note is not None and len(note) > 500:
        raise ValueError("supplement note is too long")
    return cleaned, amount, chosen, note


class JournalIn(BaseModel):
    kind: Literal["caffeine", "alcohol", "weight", "water", "supplement"]
    ts: datetime  # when it happened (ISO 8601 with offset)
    amount: float = Field(gt=0)
    name: str | None = Field(default=None, max_length=80)
    unit: str | None = None  # supplements only; every other kind has a fixed unit
    notes: str | None = Field(default=None, max_length=500)  # a supplement's effect, if one was noticed

    @model_validator(mode="after")
    def plausible(self) -> JournalIn:
        if self.ts.tzinfo is None:
            raise ValueError("ts needs a UTC offset")
        if self.kind == "supplement":
            self.name, self.amount, self.unit, self.notes = clean_supplement(
                self.name, self.amount, self.unit, self.notes
            )
            return self
        if self.notes:
            raise ValueError("notes are only for a supplement")
        limits = {"caffeine": 2000.0, "alcohol": 30.0, "weight": 400.0, "water": WATER_MAX_ML}
        if self.amount > limits[self.kind] or (self.kind == "weight" and self.amount < 20):
            raise ValueError(f"implausible {self.kind} amount")
        return self


class SupplementIn(BaseModel):
    """An edit of one supplement entry: name, dose and effect. The time stays."""

    name: str = Field(max_length=80)
    amount: float = Field(gt=0)
    unit: str
    notes: str | None = Field(default=None, max_length=500)

    @model_validator(mode="after")
    def plausible(self) -> SupplementIn:
        self.name, self.amount, self.unit, self.notes = clean_supplement(
            self.name, self.amount, self.unit, self.notes
        )
        return self


def add(conn: Connection, conninfo: str | None, user_id: UUID, entry: JournalIn) -> dict:
    """Stores one entry. A weight re-derives from its local date onward (after commit)."""
    if entry.kind == "weight":
        conn.execute(
            "INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, %s, %s) ON CONFLICT (user_id, ts) DO UPDATE SET kg = EXCLUDED.kg",
            (user_id, entry.ts, entry.amount),
        )
        conn.commit()
        tz = conn.execute("SELECT timezone FROM app_user WHERE id = %s", (user_id,)).fetchone()[0]
        days = rederive(conninfo, user_id, since=entry.ts.astimezone(ZoneInfo(tz)).date(), log=lambda _: None)
        return {"id": f"weight:{int(entry.ts.timestamp() * 1000)}", "rederived_days": days}
    row = conn.execute(
        "INSERT INTO manual_entry (user_id, kind, ts, amount, unit, name, notes) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING id",
        (
            user_id,
            entry.kind,
            entry.ts,
            entry.amount,
            entry.unit or UNITS[entry.kind],
            entry.name,
            entry.notes,
        ),
    ).fetchone()
    return {"id": str(row[0]), "rederived_days": 0}


def entries(conn: Connection, user_id: UUID, first: datetime, last: datetime) -> list[dict]:
    """Every journal entry in [first, last), newest first, one shape for all kinds."""
    rows = conn.execute(
        "SELECT id::text, kind, ts, amount, unit, name, notes FROM manual_entry "
        "WHERE user_id = %(u)s AND ts >= %(a)s AND ts < %(b)s AND kind = ANY(%(k)s) "
        "UNION ALL SELECT 'weight:' || (extract(epoch FROM ts) * 1000)::bigint, 'weight', ts, kg, 'kg', NULL, NULL "
        "FROM weight_log WHERE user_id = %(u)s AND ts >= %(a)s AND ts < %(b)s ORDER BY 3 DESC",
        {"u": user_id, "a": first, "b": last, "k": list(_MANUAL)},
    ).fetchall()
    return [
        {
            "id": i,
            "kind": k,
            "ts": int(ts.timestamp() * 1000),
            "amount": a,
            "unit": u,
            "name": n,
            "notes": notes,
        }
        for i, k, ts, a, u, n, notes in rows
    ]


def update_supplement(conn: Connection, user_id: UUID, entry_id: str, patch: SupplementIn) -> bool:
    """Changes one supplement's name, dose or effect. False when that entry isn't theirs."""
    cur = conn.execute(
        "UPDATE manual_entry SET name = %s, amount = %s, unit = %s, notes = %s "
        "WHERE user_id = %s AND id::text = %s AND kind = 'supplement'",
        (patch.name, patch.amount, patch.unit, patch.notes, user_id, entry_id),
    )
    return cur.rowcount > 0


def delete(conn: Connection, user_id: UUID, entry_id: str) -> bool:
    if entry_id.startswith("weight:"):
        ms = int(entry_id.removeprefix("weight:"))
        cur = conn.execute(
            "DELETE FROM weight_log WHERE user_id = %s AND ts = to_timestamp(%s / 1000.0)", (user_id, ms)
        )
    else:
        cur = conn.execute(
            "DELETE FROM manual_entry WHERE user_id = %s AND id::text = %s AND kind = ANY(%s)",
            (user_id, entry_id, list(_MANUAL)),
        )
    return cur.rowcount > 0
