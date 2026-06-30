# Releasing

This document covers how squire ships. All release logic lives in the `release`
Mill module; the GitHub Actions workflow is a thin orchestration layer over it.

See also [commits.md](commits.md) for the commit convention that drives versions,
and [changelog.md](changelog.md) for the curated changelog the release gate
requires.

## The `release` module

The `release` module exposes the release commands used both locally and in CI:

```bash
./mill release.run next                       # preview the next version from commits
./mill release.run version                     # show the current version
./mill release.run check <v>                   # gate: exact dated CHANGELOG section exists
./mill release.run notes <v>                   # assemble the release body
./mill release.run promote [<v>] [--date YYYY-MM-DD]   # promote [Unreleased]
./mill release.run smoke <v> [--bin PATH]      # smoke-test a built binary
```

`check` is also available directly as `./mill release.check <v>`.

## Two publish paths

A release can target either or both of:

1. GitHub Release: native images for four platforms, plus the assembly jar and
   the launcher scripts, attached to a GitHub Release.
2. Maven Central: the `squire-core`, `squire-tools`, and `squire-cli` library
   artifacts.

## Entrypoints

There are two ways to start a release:

- Push a `v<version>` tag, for example `git tag v1.2.3 && git push origin v1.2.3`.
  This is the normal stable-release path.
- Run the `Release` workflow via `workflow_dispatch` and set `target` to one of
  `release`, `central`, or `both`. Use this to publish only one path, or to let
  the workflow create the tag for you.

## Version rules

The repository uses two related version forms:

- Git tags always carry a leading `v` (for example `v1.2.3`).
- Every other version value (workflow inputs, changelog headings, `release`
  command arguments) omits the `v` (for example `1.2.3`).

Preview and promote versions from the commit history rather than hand-picking
them:

```bash
./mill release.run next        # what the next version would be
./mill release.run promote     # promote [Unreleased] using that derived version
```

`next` and `promote` both apply the bump policy from [commits.md](commits.md) to
the commits since the last tag.

## The changelog gate

The release workflow refuses to publish unless `CHANGELOG.md` contains an exact
dated `## [<version>]` section for the release. It does not fall back to
generated-only notes. Verify this locally before tagging:

```bash
./mill release.check <version>
```

## Release notes

The notes published to the GitHub Release are a hybrid: the curated changelog
section for the version, followed by generated notes derived from the commit
range. The workflow produces them with:

```bash
./mill release.run notes <version>
```

The workflow writes that output to `out/release-notes-<version>.md` and feeds it
to `gh release create --notes-file out/release-notes-<version>.md`. Run
`release.run notes` yourself first to review the body before it ships.

> Note: 0.1.0 has not shipped yet. Its generated notes draw from history that
> predates the Conventional Commits convention, so those commits bucket under
> Changed. This is expected for the first release only; later releases draw from
> conventional history.

## Codegen precondition

Before tagging, the generated `dist/` tree and `.claude-plugin/marketplace.json`
must be in sync with `skills/`. Regenerate and confirm there is no drift:

```bash
./mill skills.generateAll
git diff --exit-code dist .claude-plugin/marketplace.json
```

If the diff is not clean, commit the regenerated output before releasing. CI
fails on drift, so an out-of-sync tree will block the release.

## Smoke testing

After a release builds, smoke-test the produced binary:

```bash
./mill release.run smoke <version>
./mill release.run smoke <version> --bin path/to/squire   # test a specific binary
```

The command runs a set of checks against the binary and reports PASS or FAIL per
check.

## Maven Central auth

The `publish-central` job authenticates to Maven Central using org-level secrets
bridged to the `MILL_*` env vars that Mill reads:

| Org secret | Mill env var |
|------------|--------------|
| `ELEVEN19_SONATYPE_USERNAME` | `MILL_SONATYPE_USERNAME` |
| `ELEVEN19_SONATYPE_PASSWORD` | `MILL_SONATYPE_PASSWORD` |
| `ELEVEN19_IO_PGP_SECRET_BASE64` | `MILL_PGP_SECRET_BASE64` |
| `ELEVEN19_IO_PGP_PASSPHRASE` | `MILL_PGP_PASSPHRASE` |

The exact bridge is documented in the comments of
`.github/workflows/release.yml`.
