# RingBell

Two Android apps in one Gradle project.

| App | Package | Role |
|---|---|---|
| **Ring Client** (App A) | `app.ringbell.client` | Asks for a name on first run. Every later launch rings the host. Shows a **Cancel ring** button until the host dismisses. |
| **Ring Host** (App B) | `app.ringbell.host` | Runs a background listener. Rings (looping alarm + vibration + full-screen alert) until you tap **Dismiss**. Shows who rang, plus history. |

## How they talk

No custom backend. Both apps use one [ntfy](https://ntfy.sh) topic as a message pipe:

- client posts `ring` / `cancel` messages
- host streams the topic, and posts `dismissed` back so the client can hide its Cancel button

Config lives in `gradle.properties` (`RING_SERVER`, `RING_TOPIC`, `RING_TOKEN`).
**Both apps must be built with the same values.** On public ntfy.sh the topic name is the only secret, so keep it long and random (a random one is already set). For a self-hosted server with auth, set `RING_SERVER` and `RING_TOKEN`.

Override without editing the file:

    ./gradlew :client:assembleDebug :host:assembleDebug -PRING_TOPIC=my-secret-topic

## Build

- **Android Studio**: open this folder, pick the `client` or `host` run configuration.
- **CLI** (JDK 17 + Android SDK 35): `./gradlew :client:assembleDebug :host:assembleDebug`
  APKs land in `client/build/outputs/apk/debug/` and `host/build/outputs/apk/debug/`.
- **GitHub Actions**: push to a repo's `main`; `.github/workflows/build.yml` uploads both APKs as an artifact.

## First run on the host phone

Open Ring Host once. It starts the listener and shows a setup card; allow:

1. Notifications
2. Full-screen alerts (so it can pop over the lock screen on Android 14+)
3. No battery restriction (so the connection survives Doze)

After that it restarts itself on reboot. Samsung / Xiaomi / etc. may also need "Unrestricted" battery and "Auto-start" in system settings.

## Behaviour notes

- A ring never expires. If every host is offline, the client says so ("All hosts are offline… queued", and the widget shows **Offline**) but still sends it; the next host to come online catches up and rings, whether that is 15 minutes or 15 days later. The client keeps the ring pending until a host dismisses it or you cancel.
- Online detection: when the client rings it also sends a `ping`; every online host answers with a `pong`. No pong within ~6 seconds means "all hosts offline". There is no background traffic, so it costs no battery and stays far below ntfy's daily message limits.
- ntfy only caches messages for a limited time (12 h on ntfy.sh, `cache-duration` on a self-hosted server). To outlive that, the client re-publishes its pending ring (same id, so hosts ignore the duplicate) hourly, when a host announces it just came online (`hello`). This needs the client's background watcher alive; on a self-hosted server you can also raise `cache-duration`.
- Dismissing a ring on one host also stops it on the other hosts (including ones that were offline and catch up later).
- Dismiss and cancel are idempotent; duplicate deliveries are ignored by ring id.
- The alarm uses the system default alarm sound on the alarm volume stream; if that stream is muted it is raised to ~70 %.
- The client only rings on a real launch (not on rotation), and the very first launch (name setup) does not ring.

## Signing

`keystore/ringbell.jks` is a fixed signing key used for the debug builds, so every CI build has the same signature and new APKs install over old ones without uninstalling. It is public in this repo and meant for personal sideloading only, not for publishing to a store.
