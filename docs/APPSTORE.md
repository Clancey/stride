# Stride app store — background updates on a console with no Play Store

`StrideAppstoreService` is the component that lets a treadmill console keep itself current. It
fetches a catalog over HTTPS, works out what is stale, downloads and verifies the artifacts, and
drives Android's `PackageInstaller`. Stride surfaces the result in its own launcher; there is no
separate store app.

The catalog and its APKs live in a public repository — **[`Clancey/stride-catalog`][catalog]** —
read directly as raw files. A static file in a public repo is the whole backend: nothing to run,
nothing to break, and every change to what a console will install is a reviewable commit.

[catalog]: https://github.com/Clancey/stride-catalog

```
                    ┌──────────────────────────────────────────┐
  every ~6h,        │  raw.githubusercontent.com/…/catalog.json │
  on boot,          └───────────────────┬──────────────────────┘
  on demand                             │ https
        │                               ▼
  AppstoreWorker ──► StrideAppstoreService ──► ApkDownloader ──► ApkVerifier ──► ApkInstaller
  (WorkManager)          (foreground)          sha256            signer cert     PackageInstaller
                              │                                                        │
                              └────────────► AppstoreState ◄───────────────────────────┘
                                                   │
                                          SpikeBridge → launcher "Updates" sheet
```

---

## 1. Why it exists

The console is sideload-only, non-GMS, and has no keyboard. Once Stride is the default launcher, the
only way to get a fixed build onto it is a laptop and a USB cable. That is fine for the person who
wrote it and unacceptable for anyone else, and it means a bug in the overlay — the thing that
supplies the only Back and Home the console has — is a physical trip to the machine.

So the update path is not a convenience feature. It is what makes shipping anything to this hardware
possible at all.

## 2. Safety

Two rules, both enforced in code and both covered by tests in `UpdatePlanTest`.

**Installs never happen while the belt is not idle.** The `PackageInstaller` confirmation is a
full-screen system activity. Raising it during a workout covers the workout surface, including the
stop control — the one thing `PLAN.md` §3.9 says must always be reachable. Downloads are unrestricted
(they draw nothing); the install step waits for `WorkoutSession.State.IDLE` and resumes automatically
when the session ends. `UpdatePlan.mayInstallNow` is the gate, and `InstallResultReceiver` re-checks
it as a backstop in case a session was committed before a workout started.

**Stride never updates itself unprompted.** A self-install kills the process, and with it the overlay.
`UpdatePlan.backgroundInstallable` excludes the self-update by construction; the only way to reach it
is an explicit tap, behind a dialog that says in as many words that the HUD, Back, and Home go away
until Stride comes back.

Beyond those: the service holds no reference to `MachineLink` or `GlassOsClient`. It reads workout
*state* to decide when to stay quiet. It cannot move anything.

## 3. Verification

Every artifact is checked twice before an install session is opened.

| Check | Where | Catches |
|---|---|---|
| `https` scheme, enforced in code | `CatalogManifest`, `ApkDownloader`, `setCatalogUrl` | a cleartext catalog or a redirect off TLS (the console's targetSdk still permits cleartext at the platform level) |
| SHA-256 over the downloaded bytes | `ApkDownloader` | corruption, truncation, a substituted file |
| declared size vs actual | `ApkDownloader` | a stream that ends early or runs long |
| package name + versionCode of the archive | `ApkVerifier` | a catalog entry that does not describe the file it points at |
| **signing certificate digest** | `ApkVerifier` | an impostor build |

The signer check is not redundant with Android's own. The platform refuses to *update* an installed
app with a differently-signed APK, which protects packages already on the console — but says nothing
about a *first* install, which is exactly where a compromised catalog could hand the machine an
impostor "Spotify". A failure deletes the staged file rather than leaving it for a later retry that
might skip the check.

Manifest signing (a detached signature over the JSON itself) is designed for but not implemented;
adding it is a new field, not a schema break.

## 4. The catalog

Schema v1, documented in full in the [catalog repository's README][catalog]:

```jsonc
{
  "schema": 1,
  "generated": "2026-08-14T00:00:00Z",
  "apps": [
    {
      "package": "io.stride.spikes",
      "role": "stride",          // "stride" | "app"
      "name": "Stride",
      "versionCode": 42,
      "versionName": "0.4.2",
      "minSdk": 26,
      "abis": ["arm64-v8a"],     // empty means universal
      "url": "https://raw.githubusercontent.com/Clancey/stride-catalog/main/apks/io.stride.spikes-42.apk",
      "sizeBytes": 31457280,
      "sha256": "…",
      "signerSha256": "…",
      "releaseNotesUrl": "https://github.com/Clancey/stride/releases/tag/v0.4.2"
    }
  ]
}
```

The parser rejects the **whole document**, never one entry, on: an unknown `schema`, a non-https
`url`, a missing or malformed digest, a non-positive `versionCode`/`sizeBytes`, an implausible
package name, a duplicate package, or a second `"role": "stride"`. Partial acceptance of a document
that decides what gets installed on a machine with a motor is not a behaviour worth having.

Rejecting an unknown `schema` outright is the deliberate one: a future field an old client silently
ignored could be the one that matters — a revocation flag, say. Refusing is the safe read.

Publishing is one command in the catalog repo:

```bash
tools/publish.sh app-release.apk --role stride --name Stride
```

It reads `versionCode`, `versionName`, `minSdk` and ABIs out of the APK rather than from arguments,
computes the digests, and rewrites `catalog.json`.

## 5. Update policy

`UpdatePlan` is pure — no `Context`, no I/O, no clock — so every rule below is a unit test rather
than something discovered on hardware.

- An update requires a **strictly greater** `versionCode`. Equal is a no-op; lower is a downgrade,
  which fails on a non-rooted device and, where it succeeds, is how you brick a launcher.
- Entries needing a newer Android, or with no ABI in common, are marked ineligible **and still
  shown** — with the reason. Silently dropping them makes a missing app look like a bug.
- Something merely *offered* and never installed is not a pending update and does not badge the
  launcher.
- Third-party updates install in the background. Stride's own does not.

### 5.1 Coming back after updating itself

Updating a package kills every process it owns, so Stride cannot watch its own install finish, and a
separate updater *process* would not change that — `android:process` does not survive a package
replacement. Only a separate *package* would, which means a second APK with its own install and its
own `REQUEST_INSTALL_PACKAGES` grant.

On a phone none of this matters. When Play updates a launcher the system kills it, leaves you on
whatever was foreground, and the next press of **Home** starts the new one. The Home button is the
recovery path, and Play never has the problem itself because `com.android.vending` is not the package
being replaced.

This console has no Home button, and Stride's overlay *is* the Home button — so it dies with the
process, and the rider is stranded in whatever app was behind Stride with no way back.

`PackageReplacedReceiver` performs that missing Home press. The platform restarts the process
specifically to deliver `ACTION_MY_PACKAGE_REPLACED`, and Stride uses it to re-arm the update worker,
restart `OverlayService`, and start `MainActivity`. Two things make it work:

- It listens for `MY_PACKAGE_REPLACED`, **not** `PACKAGE_REPLACED`. The latter fires for every app on
  the device, so acting on it would drag a rider out of Spotify the moment Spotify updated.
- Starting an activity from the background is banned since Android 10, but holding
  `SYSTEM_ALERT_WINDOW` is an exemption — and Stride already requires it for the overlay. The overlay
  is started *before* the activity: restore the way out before restoring the thing to get out of.

Verified on hardware: 1.0.3 → 1.0.4 with the console **not** set as HOME, confirming the relaunch
comes from this receiver and not from the launcher role.

## 6. Scheduling

`WorkManager`, ~6 hourly, network-constrained, enqueued as unique work with `KEEP`. Registered from
both `BootReceiver` and `MainActivity`: consoles are rarely rebooted (they are plugged in), and a
frequently-rebooted one would never reach a scheduled run if each boot reset the interval. `KEEP`
makes both call sites idempotent.

`WorkManager` rather than `AlarmManager` because it is the only mechanism that survives Doze and
app-standby on Android 8/9 without a battery-optimisation exemption we would rather not request.

## 7. Permissions

| Permission | Why | Grant |
|---|---|---|
| `REQUEST_INSTALL_PACKAGES` | API 26 replaced the global "unknown sources" toggle with this per-app one. It grants the right to *ask*, not to install silently. | `adb shell appops set io.stride.spikes REQUEST_INSTALL_PACKAGES allow`, or the in-app Fix button |
| `INTERNET`, `ACCESS_NETWORK_STATE` | fetch the catalog; honour the network constraint | automatic |
| `FOREGROUND_SERVICE_SPECIAL_USE` | the work must continue while the rider is inside Spotify and the Flutter engine is dead | automatic |

Silent installation would need the app to be device owner or `/system`-privileged. The console is
provisioned as neither, and getting there means a factory reset and a provisioning flow — so
user-confirmed installs are the design, not a stepping stone. It is documented here rather than
left as a flag someone might flip.

## 8. The setup checklist

`AppstoreBridge.setupChecklist()` computes, on the device, what still has to be true: install
permission, overlay permission, accessibility service, notification listener, default HOME, catalog
reachable. Rows are ordered by consequence, and each one carries the exact `adb` command where no
on-device grant exists — several of these genuinely cannot be granted from the console, and
pretending otherwise wastes the time of someone standing in front of a treadmill.

This is the "guide me through setting it up" half of the feature. The other half — installing
Stride's *first* copy, which Stride obviously cannot do itself — is the adb walkthrough in the
[catalog README][catalog], cross-linked from `RUNBOOK.md`.

## 9. Files

| File | Responsibility |
|---|---|
| `appstore/CatalogManifest.kt` | schema + strict parser (pure) |
| `appstore/UpdatePlan.kt` | classification, self-update rule, install gate (pure) |
| `appstore/ApkDownloader.kt` | fetch to app-private cache, SHA-256, no resume |
| `appstore/ApkVerifier.kt` | package / version / signer of the archive |
| `appstore/ApkInstaller.kt` | `PackageInstaller` session + `InstallResultReceiver` |
| `appstore/ConfirmQueue.kt` | one confirmation on screen at a time, and recoverable (pure) |
| `appstore/RelaunchPolicy.kt` | which broadcast means "you were just updated" (pure) |
| `appstore/PackageReplacedReceiver.kt` | brings the launcher and overlay back after a self-update |
| `appstore/AppstoreState.kt` | single in-process source of truth |
| `appstore/StrideAppstoreService.kt` | the pipeline, the safety gate, the catalog URL |
| `appstore/AppstoreWorker.kt` | the ~6h periodic check |
| `appstore/AppstoreBridge.kt` | method-channel surface + setup checklist |
| `lib/model/appstore.dart` | typed snapshot, tolerant of missing keys |
| `lib/screens/updates_sheet.dart` | the entire UI |

## 10. Deliberately not built

- **Silent / device-owner installs** — see §7.
- **Delta or patch updates** — full APK, verify, install, or fail loudly.
- **Download resume** — another place a half-written file can survive. These are tens of megabytes
  on a machine plugged into a wall.
- **Rollback** — a downgrade path is a launcher-bricking path.
- **A second store app** — Stride surfaces its own updates. On a console with no keyboard, one
  sideload is already one too many. A separate updater package is the only way to keep a process
  alive *through* Stride's own install, but §5.1 gets the same outcome without a second APK, second
  install, and second permission grant.
