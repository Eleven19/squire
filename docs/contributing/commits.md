# Commit Conventions

squire uses [Conventional Commits](https://www.conventionalcommits.org/). Commit
messages are not just history: the `release` Mill module reads them to bucket
changelog entries and to compute the next SemVer version. Writing them correctly
keeps release notes and version bumps accurate.

See also [changelog.md](changelog.md) for how commits map into `CHANGELOG.md`, and
[releasing.md](releasing.md) for the full release flow.

## Format

```text
type(scope)!: description
```

- `type` (required): one of the types listed below.
- `scope` (optional): the area touched, in parentheses, for example `(cli)` or
  `(release)`.
- `!` (optional): marks a breaking change (see below).
- `description` (required): a short, imperative summary of the change.

Examples:

```text
feat(cli): add info command
fix(release): correct changelog date parsing
docs: document commit conventions
refactor(core)!: rename the public Repo type
```

## Types

| Type | Use for |
|------|---------|
| `feat` | a new user-visible or operator-visible capability |
| `fix` | a defect or regression fix |
| `docs` | documentation only |
| `ci` | CI workflows and automation |
| `build` | build system, packaging, dependencies |
| `chore` | maintenance that does not touch product behavior |
| `test` | adding or adjusting tests |
| `refactor` | behavior-preserving restructuring |
| `perf` | performance changes |
| `style` | formatting and whitespace |
| `revert` | reverting a previous commit |

## Type to changelog bucket

The release tooling routes each commit type into one of the five fixed changelog
buckets:

| Type | Bucket |
|------|--------|
| `feat` | Added |
| `fix` | Fixed |
| `docs` | Documentation |
| `ci`, `build`, `chore`, `test` | CI |
| `refactor`, `perf`, `style`, `revert` | Changed |

The bucket order is fixed: Added, Changed, Fixed, Documentation, CI. See
[changelog.md](changelog.md) for what belongs in each.

## Breaking changes

A commit is breaking if either:

- the type (or `type(scope)`) is followed by a trailing `!`, for example
  `feat(core)!: drop the legacy flag`, or
- the commit body contains a `BREAKING CHANGE:` footer.

Breaking changes drive the version bump (see below) regardless of the underlying
type.

## Bump policy

The next version is derived from the commits since the last release tag. The rule
depends on whether the current version is pre-1.0 or stable.

While `0.x` (pre-1.0):

- breaking change or `feat` results in a minor bump
- anything else results in a patch bump

At `>=1.0` (stable):

- breaking change results in a major bump
- `feat` results in a minor bump
- anything else results in a patch bump

If the commit range is empty, or contains no Conventional Commits, the tooling
falls back to a patch bump.

Preview the computed version at any time:

```bash
./mill release.run next
```

## Non-conventional commits are tolerated

The tooling does not reject commits that do not follow the convention. A
non-conventional commit is bucketed under Changed and counts as a patch-level
change for the bump policy. Conventional Commits are still strongly preferred:
they are the only way to land in Added, Fixed, Documentation, or CI, and the only
way to request a minor or major bump.
