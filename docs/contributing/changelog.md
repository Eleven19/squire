# Maintaining the Changelog

squire keeps a checked-in `CHANGELOG.md` that follows the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) structure. The changelog
is curated by hand and is the source of truth for release-note summaries. The
`release` Mill module reads it to gate releases and to build the GitHub Release
body.

See also [commits.md](commits.md) for how commit types map into the buckets below,
and [releasing.md](releasing.md) for the full release flow.

## Required structure

`CHANGELOG.md` always begins with an `## [Unreleased]` section, and that section
always carries the five buckets in this fixed order:

```markdown
## [Unreleased]

### Added

### Changed

### Fixed

### Documentation

### CI
```

Released versions sit below `[Unreleased]` and use a dated heading:

```markdown
## [1.2.3] - 2026-03-14
```

Git tags carry a leading `v` (for example `v1.2.3`), but changelog versions never
do (for example `1.2.3`).

## What goes in each bucket

The buckets line up one-to-one with the commit-type mapping in
[commits.md](commits.md).

### Added

New user-visible or operator-visible capabilities (corresponds to `feat`).
Examples: a new CLI command, a new packaging target, a new install path.

### Changed

Behavior changes, compatibility shifts, and notable refactors that alter how the
project works without being a pure fix (corresponds to `refactor`, `perf`,
`style`, `revert`, and any non-conventional commit). Examples: changed command
semantics, changed packaging behavior.

### Fixed

Defects and regressions (corresponds to `fix`). Examples: a parser edge case, an
asset-naming bug, a workflow fix.

### Documentation

Contributor, operator, and process docs (corresponds to `docs`). Examples: a new
release guide, a documented policy, an added diagram.

### CI

Automation, workflows, build pipeline, and repository governance (corresponds to
`ci`, `build`, `chore`, `test`). Examples: a new or changed GitHub Actions
workflow, changed release automation.

## Day-to-day flow

When a change is release-worthy, update `[Unreleased]` in the same PR as the
change itself. Add a concise, release-note-ready line under the matching bucket.
If a change is not notable enough to appear in release notes, no changelog edit is
needed.

```text
           +----------------------+
           | implement change     |
           +----------+-----------+
                      |
                      v
           +----------------------+
           | release-worthy?      |
           +----------+-----------+
                      |
            no        | yes
      no changelog    v
          edit   +----------------------+
                 | add entry under      |
                 | [Unreleased] bucket  |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 | merge PR             |
                 +----------------------+
```

## Preparing a release

When it is time to cut a release, promote `[Unreleased]` into a dated section with
the `release` module:

```bash
./mill release.run promote
```

With no version argument, `promote` derives the next version from the commits
since the last tag (see the bump policy in [commits.md](commits.md)). You can also
pass an explicit version and date:

```bash
./mill release.run promote 1.2.3 --date 2026-03-14
```

Promotion moves the `[Unreleased]` entries into `## [1.2.3] - YYYY-MM-DD` and
leaves a fresh, empty `[Unreleased]` section (with all five buckets) on top.

## Local verification

Confirm the changelog is well-formed and that a release section will satisfy the
release gate:

```bash
./mill release.run ready             # structure check: [Unreleased] present, all buckets
./mill release.run check <version>   # gate: a dated [version] section exists
./mill release.run notes <version>
```

`release.run check` fails if `CHANGELOG.md` is missing the exact dated section for the
given version; that is the same check the release workflow runs. `release.run
notes` prints the assembled release body (curated changelog section plus generated
commit notes) so you can review it before publishing.
