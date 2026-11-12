# Gold Watch — Android app (v1.1.0, versionCode 3)

The 5m market-shift bell for a manual gold scalper. No dashboard,
no signals list — one bell screen plus a foreground service
polling the bot's public `/api/shift` every 30s and ringing (HIGH
importance notification WITH SOUND) on every new shift.

The shift rule lives on the bot (`app/shift.py` in
shahbzshah/trader) and mirrors the TradingView bell
(`shift-signal-5m.pine`): RSI(14) arms at >= 70 / <= 30, fires on
the first closed bar back inside the zone that closes in the
reversal direction. A shift is identified by its firing-bar epoch.

Structure mirrors Crash Watch (`~/workspace/crash-watch-app/`):
native Views + foreground service + boot receiver, framework-only
(no AndroidX dependencies).

## Differences from Crash Watch

- **No key anywhere.** The gold `/api/shift` endpoint is public —
  plain GET, no key, no `dashkey.properties`, no secret ships in
  the APK.
- **Bell source is `/api/shift`, not a ledger.** That endpoint
  returns the live RSI / arm state plus the last 20 shift events,
  newest first (`epoch`, `direction`, `price`, `rsi`). The app
  rings when an event arrives with an epoch newer than the last
  one seen; the first run only records the baseline, so history
  never spams.
- **UI is the bell only:** big status line (WAITING / ARMED SELL /
  ARMED BUY / latest SHIFT + time), last shift, recent shifts,
  last-checked time, and a "Test bell" button that posts a test
  notification through the shift channel.

## v1.0 → v1.1.0 (what was removed)

- The WebView dashboard, the Reload button, and the Phil+RUDY
  signal + TP/SL outcome watching are all gone. The bot still runs
  Phil+RUDY; the app simply stops showing it. The service,
  BootReceiver, notification-permission flow, package
  (`com.megah.goldwatch`) and keystore are unchanged, so v1.1.0
  updates over v1.0.1.

## Build

```sh
cd ~/workspace/gold-watch-app
GRADLE_USER_HOME=/home/hatch/.gradle \
JAVA_HOME=/home/hatch/workspace/.android-sdk/jdk17 \
ANDROID_HOME=/home/hatch/workspace/.android-sdk \
ANDROID_SDK_ROOT=/home/hatch/workspace/.android-sdk \
GRADLE_OPTS="-Djava.net.preferIPv4Stack=true" \
/home/hatch/workspace/.gradle-dist/gradle-8.7/bin/gradle assembleRelease --no-daemon
```

## Signing

Release signing uses `goldwatch.keystore` + `keystore.properties`
in this directory — LOCAL ONLY, gitignored, never committed, never
pushed. Back up `goldwatch.keystore` somewhere safe: losing it
means no updates under the same package name.

## Files

- `app/src/main/java/com/megah/goldwatch/MainActivity.kt` — bell screen
- `app/src/main/java/com/megah/goldwatch/WatchService.kt` — 30s poller + bell notifications
- `app/src/main/java/com/megah/goldwatch/BootReceiver.kt` — restart watcher on boot
- `app/src/main/java/com/megah/goldwatch/Prefs.kt` — seen-shift + bell-state bookkeeping
