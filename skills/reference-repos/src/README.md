# reference-repos

A standalone agent skill for cloning upstream repositories into a project-local
`.ref/` cache so humans and agents can explore source code and relate it to
published libraries.

The skill is self-contained: it ships its own [Mill](https://mill-build.org/)
wrapper and a Scala 3 script. Nothing is copied into target projects — you run
the launcher from any git repository you want to attach reference checkouts to.

## What it does

When you are working in a codebase and need to understand how a dependency is
implemented, this skill:

1. Clones the upstream repo under `.ref/<owner>/<repo>/` (read-only exploration)
2. Records the pinned branch, tag, or commit in `.ref/manifest.yaml`
3. Optionally stores **artifact hints** (Maven coordinates, etc.) to help locate
   related published libraries
4. Keeps `.ref/` out of version control by adding it to the target project's
   `.gitignore`

Example: `https://github.com/getkyo/kyo` is cloned to `.ref/getkyo/kyo`.

The manifest is the source of truth for what is managed. Artifact hints are
non-exhaustive — they help you find things faster, but you should still search
the checkout when mapping APIs to modules.

## Quick start

From the **git root of the project** you are exploring:

```bash
.claude/skills/reference-repos/ref-repos ensure
.claude/skills/reference-repos/ref-repos add https://github.com/getkyo/kyo --ref main \
  --artifacts "io.getkyo:kyo-core_3,io.getkyo:kyo-test-api_3"
.claude/skills/reference-repos/ref-repos context
```

On Windows, use `ref-repos.bat` instead of `ref-repos`.

`ensure` creates `.ref/`, seeds an empty manifest, and gitignores `.ref/` when
needed. Run it once per project before the first clone.

## How the launcher works

```
your-project/          skill folder (.claude/skills/reference-repos/)
├── .ref/              ├── ref-repos          # shell launcher
│   ├── manifest.yaml  ├── mill / mill.bat    # Mill 1.2.0-RC1 (Temurin 25)
│   └── getkyo/kyo/    ├── build.mill.yaml
└── .gitignore         └── scripts/ref-repos.scala
```

1. `ref-repos` resolves the target project root via `git rev-parse
   --show-toplevel` (or `REF_REPOS_PROJECT_ROOT` if set)
2. It exports that path and runs Mill from the skill directory
3. The Mill script mutates **the target project** (`.ref/`, manifest, gitignore)

You can also invoke Mill directly:

```bash
REF_REPOS_PROJECT_ROOT=/path/to/project \
  .claude/skills/reference-repos/mill scripts/ref-repos.scala list
```

## Commands

Subcommands are defined with [mainargs](https://github.com/com-lihaoyi/mainargs).
Run `<command> --help` for flags and positional arguments.

| Command | Description |
|---------|-------------|
| `ensure` | Create `.ref/`, manifest, and gitignore entry |
| `list` | Show managed repos, pins, and artifact hints |
| `context` | Short summary for agents exploring upstream source |
| `add <url> [--ref VAL] [--artifacts LIST]` | Clone and register a repo |
| `refs <id-or-url>` | JSON: four recent branches/tags plus a custom option |
| `update <id> [--ref VAL]` | Fetch and re-pin an existing checkout |
| `remove <id>` | Delete one checkout and its manifest entry |
| `purge --force` | Delete all checkouts and clear the manifest |
| `repair [--dry-run] [--keep-orphans]` | Rebuild or create `manifest.yaml` from git checkouts under `.ref/` |

`<id>` is `owner/repo` (e.g. `getkyo/kyo`). URLs, SSH URIs, and `owner/repo`
shorthands are accepted where a repository locator is expected.

### Choosing a ref

If you omit `--ref` on `add`, the script picks a sensible default (recent remote
head, then `HEAD` symref, then GitHub's default branch).

For interactive or agent-driven selection, run `refs` first. It returns JSON with
up to four recent branches/tags and a prompt to supply a custom value. The same
pattern applies to `update` when `--ref` is omitted.

### Repairing the manifest

If checkouts exist under `.ref/` but `manifest.yaml` is missing, corrupt, or stale,
run `repair`. It walks `.ref/` for git repositories, reads each `origin` remote,
infers the pinned ref from `HEAD`, and writes a fresh manifest. Existing artifact
hints and `cloned_at` timestamps are preserved when the managed id matches.

```bash
.claude/skills/reference-repos/ref-repos repair
.claude/skills/reference-repos/ref-repos repair --dry-run
.claude/skills/reference-repos/ref-repos repair --keep-orphans
```

### SCM tools

| Task | Tool |
|------|------|
| Clone, fetch, checkout | `git` |
| GitHub branch/tag metadata (fallback) | `gh api` when authenticated |

Reference checkouts are ordinary git working trees inside `.ref/`. They are
independent of jj bookmarks or project worktrees in the target repo.

## Manifest format

`.ref/manifest.yaml` is read and written with
[VirtusLab scala-yaml](https://github.com/VirtusLab/scala-yaml):

```yaml
version: 1
repos:
  - id: getkyo/kyo
    url: https://github.com/getkyo/kyo.git
    host: github.com
    owner: getkyo
    name: kyo
    path: .ref/getkyo/kyo
    ref:
      type: branch
      value: main
      resolved_sha: abc1234...
    cloned_at: "2026-06-17T12:00:00Z"
    last_updated: "2026-06-17T12:00:00Z"
    artifacts:
      - group: io.getkyo
        artifact: kyo-core_3
        note: core effect system
```

**Owner resolution** follows the hosting platform namespace:

- GitHub / Codeberg: `owner/repo`
- GitLab: full group path + repo name (`my-group/subgroup/project`)

## For agents

Agent-facing workflow detail lives in [`SKILL.md`](SKILL.md). Agents should
announce they are using the skill, prefer existing `.ref/` checkouts over remote
docs, and never commit contents under `.ref/`.

Typical agent flow when a user asks how a library works:

1. `ref-repos context` — see what is already available
2. Read from `.ref/<owner>/<repo>/` if present
3. Offer to `add` missing upstream repos, using `refs` when the pin is unclear

## Implementation

| Component | Role |
|-----------|------|
| `scripts/ref-repos.scala` | Mill script: CLI, manifest I/O, git/gh operations |
| `mainargs` | Subcommand parsing (`RefRepos` object) |
| `scala-yaml` | `YamlCodec` derivation for manifest types |
| `uPickle` | JSON output for the `refs` subcommand |
| `os-lib` | Process execution and filesystem helpers (bundled with Mill) |

See ScalaDoc on `scripts/ref-repos.scala` for implementation notes.

## Developing this skill

This directory is a colocated [Jujutsu (`jj`)](https://jj-vcs.github.io/jj/) and
git repository.

```bash
cd .claude/skills/reference-repos

jj status
jj diff
jj describe -m "describe your change"
jj new -m "start the next change"

jj bookmark create my-feature -r @
jj git push --bookmark my-feature   # after configuring a remote
```

Compile or run the script while iterating:

```bash
REF_REPOS_PROJECT_ROOT=/path/to/some/project ./mill scripts/ref-repos.scala list
```

Mill output and caches land in `out/` (gitignored).
