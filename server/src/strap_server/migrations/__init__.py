"""Ordered migrations. Append only; never edit one that has been applied anywhere."""

from strap_server.migrations import m0001_initial, m0002_sessions

MIGRATIONS: list[tuple[str, tuple[str, ...]]] = [
    ("0001_initial", m0001_initial.STATEMENTS),
    ("0002_sessions", m0002_sessions.STATEMENTS),
]
