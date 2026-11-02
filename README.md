# Gold Watch — Android app (v1.0, versionCode 1)

WebView wrapper for the gold-signals bot dashboard at
https://gold-signals-bot-pjyn.onrender.com, plus a foreground
service polling the bot's public `/api/state` every 30s and
notifying on new signals and TP/SL outcomes.

Structure mirrors Crash Watch (`~/workspace/crash-watch-app/`):
WebView + foreground service + boot receiver, framework-only
(no AndroidX dependencies).

## Differences from Crash Watch

- **No key anywhere.** The gold `/api/state` endpoint is public —
  the web dashboard already polls it with no key. No
  `dashkey.properties`, no baked-in key, no secret ships in the APK.
- **Signal source is `/api/state`, not a ledger.** That endpoint
  only exposes the latest signal (`last_signal`: `ts`, `direction`,
  `entry`, `stop_loss`, `take_profit`) and carries no outcome
  field, so the app identifies a signal by its `ts` and judges its
  outcome from the live price in the same response (BUY: TP at
  mid >= take_profit, SL at mid <= stop_loss; reversed for SELL).
  R is computed from the signal's own levels (TP pays
  |TP-entry|/|entry-SL| R, SL costs -1.0R). The price must be fresh
  (age_s <= 120s) or the outcome check is skipped. Only the latest
  signal is watched; older signals' outcomes stay in the
  dashboard's history.
- **UI is only a centred Reload button** (Crash Watch v1.2 style) —
  no status text, no settings, no key field.

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
pushed. This project has no git remote by design (like Crash Watch).
Back up `goldwatch.keystore` somewhere safe: losing it means no
updates under the same package name.

## Files

- `app/src/main/java/com/megah/goldwatch/MainActivity.kt` — WebView + Reload
- `app/src/main/java/com/megah/goldwatch/WatchService.kt` — 30s poller + notifications
- `app/src/main/java/com/megah/goldwatch/BootReceiver.kt` — restart watcher on boot
- `app/src/main/java/com/megah/goldwatch/Prefs.kt` — seen-signal + outcome bookkeeping
