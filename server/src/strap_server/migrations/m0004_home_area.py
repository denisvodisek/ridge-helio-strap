"""0004 — a home area for the water reminder's heat limb (ours: docs/denis/SPEC.md S8, DD5).

Two columns on `profile`, null until the owner asks the phone to send a coarse location.
They are not inputs to any body formula. Writing them does not re-derive, and a later
profile save leaves them alone (that UPDATE names only the science columns).
"""

STATEMENTS: tuple[str, ...] = (
    "ALTER TABLE profile ADD COLUMN home_lat DOUBLE PRECISION",
    "ALTER TABLE profile ADD COLUMN home_lon DOUBLE PRECISION",
    "ALTER TABLE profile ADD CONSTRAINT profile_home_lat_check CHECK (home_lat IS NULL OR home_lat BETWEEN -90 AND 90)",
    "ALTER TABLE profile ADD CONSTRAINT profile_home_lon_check CHECK (home_lon IS NULL OR home_lon BETWEEN -180 AND 180)",
    "ALTER TABLE profile ADD CONSTRAINT profile_home_pair_check CHECK ((home_lat IS NULL) = (home_lon IS NULL))",
)
