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
      "releaseNotesUrl": "https://github.com/Clancey/stride/releases/tag/v0.4.2",
      "releaseNotes": [        // newest first; see §4.1
        {
          "versionCode": 42,
          "versionName": "0.4.2",
          "date": "2026-08-14",
          "notes": "Plain text. No markdown, no links."
        }
      ]
    }
  ]
}
```

The parser rejects the **whole document**, never one entry, on: an unknown `schema`, a non-https
`url`, a missing or malformed digest, a non-positive `versionCode`/`sizeBytes`, an implausible
package name, a duplicate package, a second `"role": "stride"`, or a malformed `bundles` array
(§11). Partial acceptance of a document
that decides what gets installed on a machine with a motor is not a behaviour worth having.

`releaseNotes` is the one exception, and deliberately so: a malformed note is skipped and the rest of
the document still parses. Notes change what a rider is *told*, never what is installed, so refusing
the catalog over some prose would take the console's only update path down to protect a paragraph.

Rejecting an unknown `schema` outright is the deliberate one: a future field an old client silently
ignored could be the one that matters — a revocation flag, say. Refusing is the safe read.

Publishing is one command in the catalog repo:

```bash
tools/publish.sh app-release.apk --role stride --name Stride
```

It reads `versionCode`, `versionName`, `minSdk` and ABIs out of the APK rather than from arguments,
computes the digests, and rewrites `catalog.json`.

### 4.1 Release notes travel in the catalog, not behind a link

`releaseNotesUrl` was there first and is still emitted, but nothing on the console can follow it:
this device has no browser. A rider deciding whether to restart the launcher — which takes the
overlay, Back and Home down with it — was being shown a version number and a size, and nothing else.

So the text itself rides in the entry, and the update dialog renders it in place. Two consequences:

- **Plain text, not markdown.** The dialog is a Flutter `AlertDialog` on a television. `**bold**`
  and `[text](url)` would render as literal punctuation, and a URL goes nowhere. `tools/changelog.sh`
  flattens markdown before anything is written to the catalog; bullets arrive as literal `•`.
- **A window of versions, not just the newest.** Consoles skip releases — the catalog went from
  versionCode 9 straight to 12, so a console sitting on 9 was never told what 10 or 11 changed.
  The entry carries the last several releases and `CatalogEntry.notesNewerThan` narrows that to the
  ones a given console has not run. A console already on the newest version is told nothing, and a
  first install gets only the newest entry — a backlog it never lived through is noise.

Notes are generated in this repo, by `tools/changelog.sh`, and written into the catalog by the
release workflow. See §12.

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

On top of that, `MainActivity` calls `StrideAppstoreService.checkOnStart()` every launch, gated by
`UpdatePlan.shouldCheckOnStart` at 30 minutes. Otherwise a console that is only woken up to be used
could go a long time between periodic runs and greet the rider with stale state. The timestamp lives
in app-private prefs, not `AppstoreState`, because the in-process state always reads as "never
checked" after a restart — which is exactly when this runs. It is stamped when a check *begins*, so
a console with no network does not retry on every single launch.

When that guard says "too soon", the service restores the last catalog from prefs instead of doing
nothing. Persisting only the timestamp was a half-fix: a restart inside the 30 minutes suppressed the
check *and* left `AppstoreState` empty, so the store read "not checked yet" and the header badge
showed nothing while an update was genuinely pending — hidden until the rider happened to tap **Check
now**. Suppressing the network call is right; forgetting what it returned is not.

Only the catalog bytes are cached, and only after they parse — storing the raw body first would let
one bad deploy poison every subsequent start with a catalog we already know we cannot read. The plan
is recomputed on restore rather than stored, because what is installed changes between processes (by
our own installer, by adb, by an uninstall) and a stale plan would offer an update for something
already updated. A restore reports the *original* check time, so the launcher says "checked 5 minutes
ago" rather than claiming a check just happened, and it never overwrites a live result.

The header badge reads `AppstoreStatus.actionableCount` — pending updates *plus* offerable bundles —
rather than `pendingCount`. `pendingCount` counts only updates to already-installed apps, so a
console with Google Play still missing would show no badge at all while the one thing worth doing
sat behind an unlabelled icon.

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

### 7.1 The install prompt has to be delivered to a runtime receiver

This firmware will not deliver `PackageInstaller`'s status broadcast to a **manifest-declared**
receiver. On hardware, every install hung forever with no dialog, no error, and nothing in Stride's
own logs; `logcat` was the only place the reason appeared:

```
I/ActivityManager: App 10139/io.stride.spikes targets O+, restricted
D/BroadcastQueue: suppress to start process of staticReceiver for package:io.stride.spikes
V/BroadcastQueue: Skipping delivery of ordered broadcast ... INSTALL_STATUS
```

It suppresses the delivery even though Stride is `TOP` with foreground services running, and even
when the intent names the receiver as an explicit component. `STATUS_PENDING_USER_ACTION` therefore
never arrived, and the confirmation activity was never started — a completely silent failure.

Two things follow, and both are load-bearing:

- `InstallResultReceiver` is registered **at runtime** by `StrideAppstoreService` (which is always
  alive), not in the manifest. The manifest `<receiver>` is removed, and must stay removed: keeping
  both would double-deliver on any device where the static path *does* work, raising two dialogs for
  one install.
- The status `PendingIntent` is addressed by **action + `setPackage`**, not by component. A
  component-explicit broadcast does not match a runtime registration at all.

If an install ever hangs with no prompt again, this is the first thing to check.

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
| `appstore/BundlePlan.kt` | ordered multi-package installs, and what to install next (pure) — §11 |
| `appstore/RelaunchPolicy.kt` | which broadcast means "you were just updated" (pure) |
| `appstore/PackageReplacedReceiver.kt` | brings the launcher and overlay back after a self-update |
| `appstore/AppstoreState.kt` | single in-process source of truth |
| `appstore/StrideAppstoreService.kt` | the pipeline, the safety gate, the catalog URL |
| `appstore/AppstoreWorker.kt` | the ~6h periodic check |
| `appstore/AppstoreBridge.kt` | method-channel surface + setup checklist |
| `lib/model/appstore.dart` | typed snapshot, tolerant of missing keys |
| `lib/screens/updates_sheet.dart` | the entire UI |
| `lib/widgets/bundle_row.dart` | the one-tap bundle row, shared by the sheet and the Store tab |
| `tools/changelog.sh` | release notes from git history, in markdown and plain text — §12 |

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

## 11. Google Play, and bundles

This console ships as AOSP with no Google apps. That is not a cosmetic gap: an app that calls Play
Services for sign-in, DRM or billing does not degrade on a device without it, it crashes on launch.
Netflix, Spotify and most of what a rider actually wants to run on a treadmill are in that category.

For a long time Stride treated that as permanent — catalog entries carried `requiresGms`, and an app
that needed Play was shown greyed out with "this console cannot run it". That was wrong, and the
runbook that disproved it is the origin of this section.

### 11.1 It works, unprivileged

The received wisdom is that Play Services must be a *system* app: signed into `/system/priv-app`,
holding privileged permissions. Stride's eligibility check enforced that, testing `FLAG_SYSTEM`.

On this hardware it is not true. Sideloading the Google packages as ordinary user apps, in the right
order, produces a Play Store that signs in and installs apps. Verified on the console, API 33,
arm64-v8a, dpi 160. `hasUsableGms()` therefore checks that the package is **present and enabled**,
and nothing more. A stricter check was rejecting a configuration that demonstrably works.

### 11.2 The order matters, and that is the whole problem

Four packages, and getting the sequence wrong fails:

| # | Package | Note |
|---|---|---|
| 1 | `com.google.android.gsf.login` | Google Services Framework Login |
| 2 | `com.google.android.gsf` | must be the **v13** artifact; v14 fails on SDK 33 |
| 3 | `com.google.android.gms` | Play Services — a **split** install, base + `config.mdpi` |
| 4 | `com.android.vending` | the Play Store itself |

Two traps in that table are worth stating plainly, because both look like a corrupt download:

- **GMS Core cannot be installed from its base APK alone.** Its manifest declares
  `requiredSplitTypes`, so a lone `adb install` is rejected. It needs every part in one
  `PackageInstaller` session — which is exactly what the catalog's `splits` support already does.
  `config.mdpi` is the split this console's dpi=160 needs.
- **Stopping halfway is worse than not starting.** A Play Store with no GSF underneath it opens onto
  a sign-in loop, which reads to a rider as "Stride installed a broken app".

That sequence is knowledge about *the artifacts*, not about the launcher, so it lives in the catalog
next to them. A console can learn a corrected order from a catalog update rather than an app update.

### 11.3 Bundles

A `bundles` array, added to the catalog alongside `apps`:

```jsonc
"bundles": [
  {
    "id": "google-play",
    "name": "Google Play",
    "detail": "The Play Store and the three services it needs, installed in order",
    "restartRequired": true,
    "packages": [
      "com.google.android.gsf.login",
      "com.google.android.gsf",
      "com.google.android.gms",
      "com.android.vending"
    ]
  }
]
```

This is a **new optional top-level key, not a schema bump** — deliberately. §4 says the parser
rejects an unknown `schema` wholesale, so bumping it would stop every fielded console from seeing
any update, including the one that would teach it about bundles. An old client ignores `bundles` and
keeps working. That constraint governs every future catalog change.

Parsing is still strict about the array's *contents*: every member must resolve to a real entry in
the same catalog, no bundle may be empty or list a package twice, no two bundles may share an id,
and no package may appear in two bundles. `tools/verify.sh` checks the same things before a publish,
because a bundle naming a missing package would take the whole catalog down for everyone.

### 11.4 One button

`BundlePlan` is pure and decides what happens next; `StrideAppstoreService.advanceBundle()` runs it.
The design point is that **the runner installs exactly one member per call**, and recomputes the next
one from what is installed *right now* rather than from a stored cursor.

That is what makes a run resumable. Each install raises its own system confirmation, and between two
of them a rider can dismiss a dialog, start a workout, or leave for an hour and let the process get
killed. Coming back to a fresh reading of the device means a resumed run picks up at the first real
gap instead of reinstalling what already landed — and the button says "Finish installing" rather
than "Install", so it is clear nothing was lost. A failure stops the sequence rather than carrying
on, since the later members depend on the earlier ones and continuing only buries the error that
mattered.

Bundle members are hidden from the ordinary store and update lists, in both the Kotlin plan
(`backgroundInstallable`, `pendingCount`) and the Dart model (`_standalone`). Four rows saying
"Install" in an order the rider cannot see is precisely the trap the bundle exists to close. They
are also excluded from background auto-install for the same reason: sequence is the point.

The row appears only while something is missing, and disappears when the bundle is complete.

### 11.5 The step Stride cannot take

Play Services needs a reboot to start working properly, and Stride cannot reboot an unrooted
console. So `restartRequired` exists purely so the UI can *say so*: when the last member lands the
row stops offering an install and asks for a power cycle, with a dismiss. Silently finishing would
leave a rider with a Play Store that looks installed and behaves broken.

### 11.6 "This device isn't Play Protect certified"

Installing the bundle is not the last step. The first sign-in reports:

> This device isn't Play Protect certified.

Nothing is broken, and no ordering mistake causes it. Google certifies a **build**, by fingerprint,
when a manufacturer submits it for testing. NordicTrack never submitted this one — the console was
never meant to run Play at all — so no install can satisfy the check.

Google's own escape hatch is per-device rather than per-build: register the console's **GSF device
id** against a Google account at <https://www.google.com/android/uncertified> and that account may
use Play on that device. This is a supported route for exactly this situation, not a bypass; nothing
is patched, and Play Protect keeps working normally afterwards.

**Getting the id.** Stride reports it in **Diagnostics → ENV Environment**, under
`-- Google Play certification --`. The two usual ways to read it are both dead ends here: a device-id
app comes from the Play Store, which is the thing that does not work yet, and the `sqlite3` route
into GSF's database needs root. `PlayCertification` reads it with one query against GSF's `gservices`
provider, which costs the read-only `READ_GSERVICES` permission and nothing else.

**Paste the decimal form.** The page wants decimal. Nearly every device-id app displays hex, and
pasting hex is the most common reason this step appears not to work — the page rejects it without
explaining why. The ENV screen prints the decimal first and labels the hex as a cross-check only.
Two related traps the code already handles:

- GSF stores the id in a signed 64-bit column, so roughly half of all ids come back **negative**.
  `-1` is not an error and is not `-1` to Google; it is `18446744073709551615`.
- GSF writes **0** before it has first reached Google. Registering a zero id looks like it worked
  and does nothing, so it is reported as "no id yet — give it a network and a reboot" instead.

**After registering.** Force stop and clear data on Play Services and the Play Store, then reboot.
Propagation is not instant; a few minutes is normal.

This is per **account and device**, so every tester repeats it on their own console. That is the
reason the id is surfaced in the app at all rather than left as an `adb` recipe in this file: a
tester with a treadmill and no laptop cannot run `adb`.
### 11.7 Licensing

Re-hosting Google's binaries is redistribution of someone else's software, and the same question the
third-party APKs raise (§4), only more visible. The mechanism here is neutral — a bundle is an
ordered install, and the catalog is a list of URLs. What a *published* catalog points at is a
separate decision, and this document does not claim the default one is settled.

## 12. Release notes, and where they come from

Every released version gets notes automatically. Nothing has to be written by hand for a release to
describe itself, and anything written by hand wins.

### 12.1 The generator

`tools/changelog.sh` derives the notes for a version from git:

```bash
tools/changelog.sh notes                  # this version, as markdown
tools/changelog.sh notes --format plain   # ...flattened, the way the console gets it
tools/changelog.sh json                   # recent releases, for catalog.json
tools/changelog.sh regenerate             # rewrite CHANGELOG.md from every tag
tools/changelog.sh check                  # fail if CHANGELOG.md is stale
```

The source of a version's notes is, in order:

1. `docs/release-notes/<version>.md`, verbatim, if it exists.
2. Otherwise the commit subjects since the previous `v*` tag, with `(#N)` turned into a PR link.

The fallback is only tolerable because this repo squash-merges with subjects written for a reader —
*"Call Connect, and don't claim a workout the treadmill hasn't started"* — rather than for a parser.
Bookkeeping commits (`Bump…`, `Stride 1.0.8`, merges) are dropped. If the convention ever slips, or
a release deserves the explanation that 1.0.6 and 1.0.7 got, write the file.

### 12.2 What a tag now produces

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds and signs the APK as before,
and additionally:

| Output | Where |
|---|---|
| `CHANGELOG.md` entry | this repo, committed to the default branch |
| GitHub release with real notes | `Clancey/stride` — the step v1.0.8 and v1.0.11 never got |
| `releaseNotes` in the catalog entry | `Clancey/stride-catalog/catalog.json` — what the console reads |
| Release body on `stride-N` | `Clancey/stride-catalog`, replacing the publisher's placeholder |

Notes are assembled *before* the build, so a release that cannot describe itself fails in seconds
rather than after ten minutes of Gradle. The changelog commit happens in a fresh clone, never in the
build workspace — that workspace holds the decrypted release keystore, and no step that runs
`git commit` should be one `git add -A` away from publishing it.

The changelog push is the one non-fatal step: by then the APK is published and consoles can see it,
so a rejected push is a paragraph to re-run, not a reason to show a red release. The run summary says
how.

### 12.3 Writing notes for a release by hand

Before tagging:

```bash
$EDITOR docs/release-notes/1.0.12.md     # the version, without the leading v
tools/changelog.sh notes --format plain  # read it the way the console will
```

Write prose, not a commit list — the generator already covers the commit list. Keep in mind where it
lands: a dialog on a television, read by someone standing on a treadmill deciding whether to restart
the launcher mid-session. Markdown is fine in the file (GitHub gets it intact) and is flattened for
the console, but a link is worth nothing on a device with no browser, so never make one load-bearing.
