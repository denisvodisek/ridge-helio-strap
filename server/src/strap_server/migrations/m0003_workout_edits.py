"""0003 — strap workouts the owner edits or deletes (ours: docs/denis/SPEC.md S3).

`workout` stays the strap's own record, written only by ingest, so the next sync of the same
workout can't undo the owner's change. A deleted strap workout is hidden by its start; an
edited one is hidden the same way and lives on as a `session` with source 'strap'.
"""

STATEMENTS: tuple[str, ...] = (
    """CREATE TABLE workout_hidden (
        start_ts   TIMESTAMPTZ NOT NULL,
        user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, start_ts)
    )""",
    "ALTER TABLE session DROP CONSTRAINT session_source_check",
    "ALTER TABLE session ADD CONSTRAINT session_source_check CHECK (source IN ('ridge', 'suggested', 'strap'))",
)
