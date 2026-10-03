# Setup: Ridge on Denis's machines

The order that works: **hardware check → fork → server → key → app → profile → first sync.**
Budget about two hours the first time.

## 0 · Hard requirements (check before anything else)

| Need | Why | Status |
|---|---|---|
| **Amazfit Helio Strap**, paired in the Zepp app, firmware up to date | The only device the protocol supports (tested on fw 0.132.27.2) | ✓ |
| **Android 12+ phone** | There is no iOS app | ✓ |
| **Zepp account with email + password** | `keyfetch` signs in once to read the strap's auth key. Google/Apple-only sign-in won't work unless you add a password | ✓ |
| An always-on box with Docker, reachable over **HTTPS** | The server computes every daily number; the app refuses plain HTTP | see §2 |

What this Mac already has (checked 2026-10-03): `uv` ✓, Android SDK ✓ (`~/Library/Android/sdk`),
Java 24 (wrong one, the build wants **JDK 17**), **no Docker**, `adb` not on PATH.

```bash
brew install --cask orbstack
```

```bash
brew install --cask zulu@17
```

Then add to `~/.zshrc`:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
```

## 1 · The fork (done 2026-10-03)

Forked to [denisvodisek/ridge-helio-strap](https://github.com/denisvodisek/ridge-helio-strap).
In `~/Development/ridge-helio-strap`, `origin` is the fork and `upstream` is the original.
Pull upstream with `git fetch upstream && git rebase upstream/main` (CUSTOMISING §5).
The fork is public (GitHub forks of public repos can't be private), so never commit
anything personal: see CLAUDE.md "Never commit".

## 2 · Run the server

**Recommended: a small VPS + Tailscale.** It's health data, so it should not be on the
public internet at all. Tailscale gives the box a real HTTPS name inside your private
network, and the phone joins the same network. There are no open ports, no DNS records
and no Caddy.

1. Box: Hetzner CX22 (~€4/month) or any always-on machine with Docker. A Mac that sleeps
   works only while it's awake, because the app reads every screen from the server.
2. On the box:
   ```sh
   git clone https://github.com/denisvodisek/ridge-helio-strap.git && cd ridge-helio-strap/deploy
   cp .env.example .env            # set POSTGRES_PASSWORD (openssl rand -hex 24) and OWNER_TIMEZONE=Asia/Hong_Kong
   ./new-token.sh                  # prints the phone token ONCE: save it in your password manager
   docker compose up -d --build
   curl -fsS http://127.0.0.1:8766/healthz     # {"ok":true}
   ```
3. Install Tailscale on the box and the phone, enable HTTPS certificates in the Tailscale
   admin (DNS → HTTPS), then on the box:
   ```sh
   sudo tailscale serve --bg 8766
   ```
   The server is now at `https://<box-name>.<tailnet>.ts.net`, reachable only from your
   devices.

**Alternative: public HTTPS** with Caddy on a domain, exactly as `deploy/README.md` §4. Use it only if
you want to reach the server from devices you can't put on Tailscale. The token is then the
only lock on your health data.

`OWNER_TIMEZONE` decides where each day is cut. Set it right before the first sync; changing
it later needs a `rederive`.

## 3 · Get the strap's key

On the Mac:

```bash
cd ~/Development/ridge-helio-strap && uv run tools/keyfetch/keyfetch.py you@example.com --qr
```

It prints the strap's MAC, its 16-byte auth key and a QR code for the app. Signing in logs
the Zepp app out, which is fine. Then stop the Zepp app from grabbing the strap: revoke its
Nearby devices permission (or force-stop it). Turn it back on only for firmware updates.
Re-run `keyfetch` whenever the strap is re-paired (the app then shows handshake status `0x25`).
Details: `tools/keyfetch/README.md`.

## 4 · Build and install the app (your own signing key from day one)

You will customise the app, so **don't install upstream's release APK**. Android won't
upgrade an app signed with someone else's key with your build; you'd have to uninstall it
and lose the local cache. Make your own key once:

```bash
keytool -genkeypair -v -keystore ~/.android/ridge-release.jks -alias ridge -keyalg RSA -keysize 4096 -validity 36500
```

```bash
security add-generic-password -s ridge-release-keystore -a ridge -w
```

(The second command prompts for the same password; the Gradle build reads it from the
Keychain.) **Back up the `.jks` file and the password.** Lose them and no future build can
install over the app.

Build and install with the phone on USB (developer options → USB debugging):

```bash
cd ~/Development/ridge-helio-strap/android && ./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

Open Ridge: setup asks for the MAC and key (scan the QR), then the server URL and token
(press **Test**), then runs the first sync.

## 5 · Try the UI without a strap (optional, any time)

```bash
cd ~/Development/ridge-helio-strap && tools/demo-data/load.sh
```

Then `./gradlew :app:assembleDemo`, `adb reverse tcp:8767 tcp:8767`, install the demo APK.
"Ridge demo" installs next to the real app. This is the loop for UI changes.

## 6 · Set your profile (required, no screen for it yet)

Most metrics need date of birth, sex and height, plus a logged weight (Journal tab). The
non-exercise VO₂max also needs **SR-PA**, a self-reported 0–4 answer:

| SR-PA | Meaning (Jurca 2005) |
|---|---|
| 0 | Avoid walking or exertion |
| 1 | Walk for pleasure, little other activity |
| 2 | 10–60 min/week of moderate activity (e.g. golf, gardening, weights) |
| 3 | Aerobic exercise (run, swim, cycle) 1–3 h/week |
| 4 | Aerobic exercise over 3 h/week |

On the server box:

```bash
docker compose exec db psql -U strap -d strap -c "INSERT INTO profile (user_id, height_cm, sex, dob, srpa) VALUES ('00000000-0000-0000-0000-000000000001', 180, 'male', '1990-01-01', 2) ON CONFLICT (user_id) DO UPDATE SET height_cm = EXCLUDED.height_cm, sex = EXCLUDED.sex, dob = EXCLUDED.dob, srpa = EXCLUDED.srpa, updated_at = now()"
```

(Replace the values with yours.) Then log your weight in the app and recompute:

```bash
docker compose exec api python -m strap_server.rederive
```

A profile screen in the app is the first item on the roadmap.

## 7 · What to expect in the first weeks

Baselines need history, so the app fills in over time:

| After | What appears |
|---|---|
| First sync | HR, stress, steps per minute; sleep stages for synced nights |
| 1 night | Resting HR, HRV, SpO₂, respiratory rate |
| 5 days | Recovery score, "your usual" comparisons, live readiness |
| 7 nights | Sleep Regularity Index |
| 10–14 nights | Illness early-warning flag can fire |
| 28 days | ACWR (load spike) |
| ~90 days | Strain scale fully personal (90-day P95) |

Sync is manual: press Sync in the morning and whenever you want fresh numbers.

## Updating the server

```sh
cd ridge-helio-strap && git pull && cd deploy && docker compose up -d --build
docker compose exec api python -m strap_server.rederive    # only after a formula change
```

Backups land in `deploy/backups/` daily (14 kept). Copy them off the box too.
