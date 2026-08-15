#!/usr/bin/env bash
#
# Release notes for Stride, derived from what actually changed since the last release.
#
#   tools/changelog.sh notes [VERSION]           # notes for one release, as markdown
#   tools/changelog.sh notes --format plain       # ...as plain text, for the console dialog
#   tools/changelog.sh json [--limit N]           # recent releases as JSON, for catalog.json
#   tools/changelog.sh regenerate                 # rewrite CHANGELOG.md from every tag
#   tools/changelog.sh check                      # fail if CHANGELOG.md is out of date
#
# WHY THIS EXISTS: a console offers an update as a version number and a size. The rider is standing
# on a treadmill deciding whether to restart the launcher - which takes the overlay, Back and Home
# down with it - and until now had nothing to base that on. `releaseNotesUrl` pointed at a catalog
# release whose body read "Published by tools/publish.sh", and no console has a browser to follow
# the link with anyway. So the notes have to be text, and they have to travel in the catalog.
#
# TWO RENDERINGS, and the difference matters. GitHub gets markdown with PR links. The console gets
# plain text, because the update dialog is a Flutter AlertDialog on a TV: `**bold**` and
# `[text](url)` render as literal punctuation there, and a URL is a dead end on a device with no
# browser. Anything added here must survive to_plain() legibly.
#
# HAND-WRITTEN NOTES WIN. Drop a file at docs/release-notes/<version>.md and it becomes the notes
# for that version, verbatim. Otherwise the commit subjects since the previous tag are used, which
# works here only because this repo squash-merges with subjects written for a reader ("Call
# Connect, and don't claim a workout the treadmill hasn't started") rather than for a compiler
# ("fix: connect"). If that convention ever slips, write the file.
#
# NOTE ON HISTORY: consoles skip versions - the catalog went from versionCode 9 to 12, so a console
# on 1.0.8 never saw 1.0.9 or 1.0.10. `json` therefore emits a window of recent releases, not just
# the newest, and the console shows every entry above its own versionCode. That is what makes the
# dialog answer "what do I get", rather than "what did the last person to tag get".
#
# Requires full history: `actions/checkout` must run with fetch-depth: 0, or every release looks
# like it changed nothing.

set -euo pipefail

cd "$(dirname "$0")/.."

exec python3 - "$@" <<'PY'
import json
import os
import re
import subprocess
import sys

PUBSPEC = 'apps/spikes/pubspec.yaml'
OVERRIDE_DIR = 'docs/release-notes'
CHANGELOG = 'CHANGELOG.md'
REPO = 'Clancey/stride'

# How many past releases ride along in catalog.json. Every console that is behind needs to find its
# own version in this window or it sees a truncated story; eight covers a console that has not been
# switched on for months, and costs a few KB on a file that is fetched every six hours.
HISTORY_LIMIT = 8

USAGE = __doc__ or 'usage: tools/changelog.sh {notes|json|regenerate|check}'


def die(msg):
    sys.exit(f'changelog: {msg}')


def git(*args):
    """Run git, and fail loudly. A silent empty result here becomes a release that claims nothing
    changed, which is worse than no notes at all."""
    done = subprocess.run(['git', *args], capture_output=True, text=True)
    if done.returncode != 0:
        die(f'`git {" ".join(args)}` failed: {done.stderr.strip()}')
    return done.stdout


def version_key(tag):
    """v1.0.11 -> (1, 0, 11). Sorting tags as strings puts 1.0.11 before 1.0.6, which would make
    every release after the tenth compare against the wrong predecessor and emit the wrong notes."""
    return tuple(int(n) for n in re.findall(r'\d+', tag))


def releases():
    """Every v* tag, oldest first, with the date it was tagged."""
    out = git('for-each-ref', '--format=%(refname:short)\t%(creatordate:short)', 'refs/tags/v*')
    found = []
    for line in out.splitlines():
        if not line.strip():
            continue
        tag, _, date = line.partition('\t')
        found.append((tag, date.strip()))
    return sorted(found, key=lambda pair: version_key(pair[0]))


def pubspec_version(ref=None):
    """(versionName, versionCode) from the pubspec, at a tag or in the working tree."""
    text = git('show', f'{ref}:{PUBSPEC}') if ref else open(PUBSPEC).read()
    match = re.search(r'^version:\s*(\S+?)\+(\d+)\s*$', text, re.M)
    if not match:
        die(f'no `version: x.y.z+n` in {PUBSPEC}' + (f' at {ref}' if ref else ''))
    return match.group(1), int(match.group(2))


# Commits that are release bookkeeping rather than a change a rider would notice.
NOISE = re.compile(r'''^(
      (bump|release|chore|wip|fixup|squash|revert\ ")   # conventional bookkeeping prefixes
    | v?\d+\.\d+(\.\d+)?\b                              # "1.0.8", "v1.0.8 (versionCode 9)"
    | stride\ v?\d+\.\d+                                # "Stride 1.0.8"
    | merge\ (branch|pull\ request|remote)\b            # merge subjects that survive --no-merges
)''', re.I | re.X)


def subjects(start, end):
    """Commit subjects in (start, end], oldest first, with the bookkeeping dropped.

    start may be None, which means "everything up to end" - the first release."""
    span = f'{start}..{end}' if start else end
    out = git('log', '--no-merges', '--reverse', '--format=%s', span)
    return [s for s in (line.strip() for line in out.splitlines())
            if s and not NOISE.match(s)]


PR_REF = re.compile(r'\s*\(#(\d+)\)\s*$')


def generated_notes(start, end, markdown):
    """Notes assembled from commit subjects, because nobody wrote them by hand."""
    lines = []
    for subject in subjects(start, end):
        match = PR_REF.search(subject)
        text = PR_REF.sub('', subject).strip()
        if markdown:
            link = f' ([#{match.group(1)}](https://github.com/{REPO}/pull/{match.group(1)}))' if match else ''
            lines.append(f'- {text}{link}')
        else:
            # No PR number: it is a link the console cannot follow, and a bare "(#8)" reads as
            # noise to a rider who has never seen this repository.
            lines.append(f'\u2022 {text}')
    return '\n'.join(lines)


def to_plain(markdown):
    """Markdown down to something a TV dialog can render without showing its punctuation."""
    text = markdown
    text = re.sub(r'!\[[^\]]*\]\([^)]*\)', '', text)               # images
    text = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', text)           # links keep their words
    text = re.sub(r'^\s{0,3}#{1,6}\s*', '', text, flags=re.M)      # headings
    text = re.sub(r'^\s{0,3}[-*+]\s+', '\u2022 ', text, flags=re.M)  # bullets
    text = re.sub(r'\*\*([^*]+)\*\*', r'\1', text)                 # bold
    text = re.sub(r'(?<!\*)\*([^*\n]+)\*(?!\*)', r'\1', text)      # italics
    text = re.sub(r'`([^`]+)`', r'\1', text)                       # inline code
    text = re.sub(r'^\s{0,3}>\s?', '', text, flags=re.M)           # quotes
    text = re.sub(r'\n{3,}', '\n\n', text)
    return text.strip()


def override_path(version):
    return os.path.join(OVERRIDE_DIR, f'{version}.md')


def notes_for(version, start, end, markdown=True):
    """The notes for one release: the hand-written file if there is one, else the commits."""
    path = override_path(version)
    if os.path.exists(path):
        written = open(path).read().strip()
        if written:
            return written if markdown else to_plain(written)
    return generated_notes(start, end, markdown)


def resolve(version=None):
    """Locate a release in history: (version, code, date, start_ref, end_ref).

    Falls back to HEAD when the requested version has no tag yet, so this works on a
    workflow_dispatch build and while preparing a release locally."""
    history = releases()
    if version is None:
        version = pubspec_version()[0]

    tag = f'v{version}'
    tagged = [t for t, _ in history]

    if tag in tagged:
        index = tagged.index(tag)
        start = tagged[index - 1] if index else None
        date = dict(history)[tag]
        code = pubspec_version(tag)[1]
        return version, code, date, start, tag

    # Untagged: this is the release being prepared. Everything since the newest tag below it.
    below = [t for t in tagged if version_key(t) < version_key(tag)]
    start = below[-1] if below else None
    date = git('log', '-1', '--format=%cd', '--date=short', 'HEAD').strip()
    code = pubspec_version()[1]
    return version, code, date, start, 'HEAD'


def cmd_notes(argv):
    markdown = True
    version = None
    while argv:
        arg = argv.pop(0)
        if arg == '--format':
            if not argv:
                die('--format needs md or plain')
            fmt = argv.pop(0)
            if fmt not in ('md', 'plain'):
                die(f'unknown format {fmt!r} (want md or plain)')
            markdown = fmt == 'md'
        elif arg.startswith('-'):
            die(f'unknown option {arg!r}')
        else:
            version = arg

    version, _, _, start, end = resolve(version)
    body = notes_for(version, start, end, markdown)
    if not body:
        # Real situation - a tag on the same commit as the last one - and the honest thing is to
        # say so rather than ship an empty dialog that looks broken.
        body = ('No code changes since the previous release; this is a rebuild.'
                if markdown else 'No changes since the previous release; this is a rebuild.')
    print(body)


def cmd_json(argv):
    limit = HISTORY_LIMIT
    while argv:
        arg = argv.pop(0)
        if arg == '--limit':
            if not argv:
                die('--limit needs a number')
            limit = int(argv.pop(0))
        else:
            die(f'unknown option {arg!r}')

    history = releases()
    tagged = [t for t, _ in history]

    # The release being built may not be tagged (workflow_dispatch), and a console still needs to
    # read about the build it is being offered.
    current = pubspec_version()[0]
    wanted = list(tagged)
    if f'v{current}' not in tagged:
        wanted.append(f'v{current}')

    entries = []
    for tag in wanted[-limit:]:
        version = tag.lstrip('v')
        version, code, date, start, end = resolve(version)
        body = notes_for(version, start, end, markdown=False)
        if not body:
            body = 'No changes since the previous release; this is a rebuild.'
        entries.append({
            'versionCode': code,
            'versionName': version,
            'date': date,
            'notes': body,
        })

    entries.sort(key=lambda e: e['versionCode'], reverse=True)
    print(json.dumps(entries, indent=2))


HEADER = f"""# Changelog

Every released version of Stride, newest first.

Generated by `tools/changelog.sh regenerate` from the tags in this repository, so it is a record of
what shipped rather than what was intended. Notes for a version come from
`{OVERRIDE_DIR}/<version>.md` when that file exists, and from the commit subjects since the previous
tag when it does not.
"""


def render_changelog():
    out = [HEADER]
    history = releases()
    tagged = [t for t, _ in history]
    for index in reversed(range(len(tagged))):
        tag = tagged[index]
        version = tag.lstrip('v')
        start = tagged[index - 1] if index else None
        code = pubspec_version(tag)[1]
        date = dict(history)[tag]
        body = notes_for(version, start, tag) or '_A rebuild; no code changes since the previous release._'
        out.append(f'\n## {version} — versionCode {code} — {date}\n\n{body}\n')
    return ''.join(out)


def cmd_regenerate(argv):
    if argv:
        die(f'unexpected argument {argv[0]!r}')
    text = render_changelog()
    open(CHANGELOG, 'w').write(text)
    print(f'{CHANGELOG}: {len(text.splitlines())} lines, {len(releases())} releases')


def cmd_check(argv):
    if argv:
        die(f'unexpected argument {argv[0]!r}')
    fresh = render_changelog()
    current = open(CHANGELOG).read() if os.path.exists(CHANGELOG) else ''
    if fresh != current:
        die(f'{CHANGELOG} is out of date. Run `tools/changelog.sh regenerate`.')
    print(f'{CHANGELOG} is up to date.')


COMMANDS = {
    'notes': cmd_notes,
    'json': cmd_json,
    'regenerate': cmd_regenerate,
    'check': cmd_check,
}

argv = sys.argv[1:]
if not argv or argv[0] in ('-h', '--help'):
    sys.exit('usage: tools/changelog.sh {notes|json|regenerate|check} [options]\n'
             '       see the comments at the top of tools/changelog.sh')
command = argv.pop(0)
if command not in COMMANDS:
    sys.exit(f'changelog: unknown command {command!r}. '
             f'Want one of: {", ".join(COMMANDS)}')
COMMANDS[command](argv)
PY
