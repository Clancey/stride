#!/usr/bin/env bash
#
# Publish docs/RUNBOOK.md into the public catalog repo.
#
#   tools/sync-runbook.sh [path-to-stride-catalog]
#
# WHY THIS EXISTS: this repository is private. The runbook is the one document a person needs when
# their console will not boot, and §5 covers a belt that will not stop - so it has to be readable by
# someone who is not us, from a phone, standing in front of a treadmill. Linking to it from the
# public README does not work: the link 404s for everyone.
#
# So the public copy is generated, not hand-written, because a hand-copied 500-line safety document
# drifts and nobody notices until it matters. Re-run this whenever RUNBOOK.md changes.
#
# The transform drops the parts that only make sense inside this repo - our build tooling, the
# release keystore, and the development machine's console address - and keeps every recovery step.

set -euo pipefail

cd "$(dirname "$0")/.."
SRC=docs/RUNBOOK.md
CATALOG="${1:-../stride-catalog}"
DEST="$CATALOG/docs/RUNBOOK.md"

[ -f "$SRC" ] || { echo "no $SRC here" >&2; exit 1; }
[ -d "$CATALOG/.git" ] || { echo "not a checkout: $CATALOG" >&2; exit 1; }

mkdir -p "$CATALOG/docs"

SRC="$SRC" DEST="$DEST" python3 - <<'PY'
import os, re, sys

src = open(os.environ['SRC']).read()
before = len(src.split('\n'))

def cut(text, start_marker, end_marker, replacement=''):
    """Replace [start_marker, end_marker) exactly once, loudly if it no longer matches."""
    try:
        i = text.index(start_marker)
        j = text.index(end_marker, i)
    except ValueError:
        sys.exit(f"sync-runbook: could not find {start_marker!r} .. {end_marker!r}.\n"
                 "RUNBOOK.md changed shape - update this script rather than letting the public "
                 "copy drift or leak internal tooling.")
    return text[:i] + replacement + text[j:]

# The framing assumes you are the person developing Stride against a spike plan.
src = cut(src,
          '> **Read this before you set Stride as the default HOME app.**',
          'The console has no physical Home or Back button.',
          """> **Read this before you set Stride as the default HOME app.** Prove the revert path *first*.
> If you cannot get back to iFit, do not proceed.

*Generated from Stride's source repository by `tools/sync-runbook.sh`, so it is readable by someone
standing in front of a console that will not boot. Tested on a NordicTrack Commercial 1750.*

""")

# The spike-ordering table is meaningless without the plan, and names risks that no longer apply to
# someone installing a released build.
src = cut(src,
          '### First session on the real console',
          'Two practical notes for a Wi-Fi ADB session:',
          """### Do the safe things first

The temptation on day one is to install Stride and set it as HOME, because that is the interesting
part. Do not. Setting HOME is the only step that can leave the console unusable, and it is the step
that gains the least - Stride runs perfectly well as an ordinary app you launch by hand, and that is
how you find out whether it starts reliably on *your* console.

Install it, use it for a few sessions, reboot the console a couple of times, and only then consider
making it HOME - after §0's persistence gate has passed *and* you have run the revert command in §1
successfully against the current iFit HOME.

""")

# Our build tooling: deploy.sh, the release keystore, SIGNING.md, and the dev console's address.
# None of it exists in the public repo, and the address is a home network detail.
src = cut(src,
          '**`tools/deploy.sh` does all of this for you**',
          '### A trap: your overlay is hidden over Settings')

src = src.replace('## 6. Permission grants used by the spike harness',
                  '## 6. Permission grants Stride needs')
src = src.replace('Collected here so a fresh flash can be brought back to a testable state quickly.\n'
                  '`tools/deploy.sh` runs all of these; they are spelled out here for when you '
                  'need one on its own.',
                  'Collected here so a console can be brought back to a working state quickly. The\n'
                  'installer applies these for you; they are spelled out for when you need one on '
                  'its own.')
src = src.replace('`StrideAppstoreService` (`docs/APPSTORE.md`) needs',
                  '`StrideAppstoreService` needs')
src = src.replace('Setting HOME is the only step in this repository that can leave the console '
                  'unusable', 'Setting HOME is the only step that can leave the console unusable')

src = re.sub(r'\n{3,}', '\n\n', src)

# Nothing internal may survive: a stale reference here is a 404 or a leak on a public page.
for bad, why in [
    ('github.com/Clancey/stride/', 'link to the private source repo'),
    ('SIGNING.md',                 'file that does not exist in the public repo'),
    ('deploy.sh',                  'tooling that does not exist in the public repo'),
    ('keystore.sh',                'release keystore tooling'),
    ('192.168.',                   'a development machine address'),
]:
    if bad in src:
        line = next(n for n, l in enumerate(src.split('\n'), 1) if bad in l)
        sys.exit(f"sync-runbook: {bad!r} ({why}) still present at line {line}. Refusing to publish.")

open(os.environ['DEST'], 'w').write(src)
print(f"{os.environ['DEST']}: {before} -> {len(src.split(chr(10)))} lines")
PY

echo "next: review the diff in $CATALOG, then commit it there."
