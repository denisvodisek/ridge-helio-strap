# Our decisions (Denis's fork)

Numbered `DD1…` so they never collide with upstream's `D` numbers in `docs/DECISIONS.md`.
A `DD` that overrides an upstream `D` says so.

| # | Decision |
|---|---|
| DD1 | **Sync automatically (2026-10-03). Overrides D19.** The strap keeps respiratory rate for only ~3 days and spot SpO₂ for ~12 (spec/01 §9), so days without a sync lose inputs recovery and the illness flag depend on, and a manual sync is the biggest gap with Whoop. Step 1 (done): sync whenever the app comes to the front, if the last attempt (failed ones included) is over 15 min old (`sync/AutoSync.kt`). Step 2 (roadmap #3): a periodic WorkManager sync in the background with a `CompanionDeviceManager` association and a Settings toggle. Pull-to-refresh stays for an immediate sync. |
