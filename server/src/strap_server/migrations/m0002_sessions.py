"""0002 — workout sessions the owner starts, logs or confirms (ours: docs/denis/SPEC.md S3, S4).

Only the window and the sport are stored; every figure about a session is computed when it
is read, from the per-minute HR already in `sample`. Dismissed suggestions are remembered by
their start minute so the same "looks like a workout" isn't offered twice.
"""

STATEMENTS: tuple[str, ...] = (
    """CREATE TABLE session (
        id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        sport      TEXT        NOT NULL CHECK (sport IN ('tennis', 'treadmill', 'stairs', 'run', 'walk', 'ride', 'gym', 'swim', 'yoga', 'other')),
        start_ts   TIMESTAMPTZ NOT NULL,
        end_ts     TIMESTAMPTZ NOT NULL CHECK (end_ts > start_ts),
        source     TEXT        NOT NULL DEFAULT 'ridge' CHECK (source IN ('ridge', 'suggested')),
        notes      TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX session_user_start ON session (user_id, start_ts DESC)",
    """CREATE TABLE session_dismissal (
        start_ts   TIMESTAMPTZ NOT NULL,
        user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, start_ts)
    )""",
)
