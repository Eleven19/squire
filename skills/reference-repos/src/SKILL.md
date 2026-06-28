---
name: reference-repos
description: >-
  Clone and manage local reference repositories under .ref/ for source exploration
  and published-library context. Maintains a YAML manifest with pinned refs and
  artifact hints. Use when the user asks to explore upstream source, add or update
  reference repos, look up how a dependency is implemented, find related published
  artifacts, or purge local reference checkouts.
---

# Reference Repositories

**Announce at start:** "I'm using the reference-repos skill."

Clone upstream repositories into `.ref/<owner>/<repo>` for read-only exploration.
Track pins and artifact hints in `.ref/manifest.yaml`. Never commit `.ref/` contents.

## Standalone tooling

The skill is self-contained under `.claude/skills/reference-repos/` in this project:

| File | Purpose |
|------|---------|
| `ref-repos` / `ref-repos.bat` | Launcher — resolves the target project, then runs Mill |
| `mill` / `mill.bat` | Bundled Mill wrapper (1.2.0-RC1, Temurin 25) |
| `build.mill.yaml` | Mill meta-build for the script |
| `scripts/ref-repos.scala` | Scala 3 Mill script (`mainargs` CLI, VirtusLab `scala-yaml` manifest I/O) |

Run from the **target project's git root** (or set `REF_REPOS_PROJECT_ROOT`):

```bash
.claude/skills/reference-repos/ref-repos <command> [args] [options]
```

Windows:

```bat
.claude\skills\reference-repos\ref-repos.bat <command> [args] [options]
```

Direct Mill invocation (skill folder must be cwd):

```bash
REF_REPOS_PROJECT_ROOT=/path/to/project \
  .claude/skills/reference-repos/mill scripts/ref-repos.scala <command> ...
```

## First-time setup

Always run `ensure` before the first clone in a project:

```bash
.claude/skills/reference-repos/ref-repos ensure
```

This creates `.ref/`, seeds `.ref/manifest.yaml`, and adds `.ref/` to `.gitignore` when missing:

```gitignore
# Reference repositories for agent exploration (managed by reference-repos skill)
.ref/
```

Verify ignore status when unsure:

```bash
git check-ignore -q .ref && echo "ignored"
```

## Layout

| Path | Purpose |
|------|---------|
| `.ref/<owner>/<repo>/` | Local checkout (`github.com/getkyo/kyo` → `.ref/getkyo/kyo`) |
| `.ref/manifest.yaml` | Managed repos, pinned ref, artifact hints |

**Owner resolution:** use the hosting platform's namespace.

- GitHub / Codeberg: `owner/repo`
- GitLab groups: full group path (`my-group/subgroup`) + repo name

## Manifest (hints, not exhaustive)

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

`artifacts` helps locate published libraries related to the checkout. Treat as **hints only** — search the repo when mapping symbols to modules.

## Commands

| Command | Purpose |
|---------|---------|
| `ensure` | Create `.ref/`, manifest, and gitignore entry |
| `list` | Show managed repos, pins, and artifact hints |
| `context` | Agent-friendly summary when user needs upstream source context |
| `add <url> [--ref VAL] [--artifacts g:a,name]` | Clone and register a repo |
| `refs <id-or-url>` | JSON: 4 most recent branches/tags + custom option |
| `update <id> [--ref VAL]` | Fetch and re-pin; without `--ref`, run `refs` and ask user |
| `remove <id>` | Delete one checkout and manifest entry |
| `purge --force` | Delete all managed checkouts and clear manifest |
| `repair [--dry-run] [--keep-orphans]` | Rebuild or create `manifest.yaml` from git checkouts under `.ref/` |

`<id>` is `owner/repo` (e.g. `getkyo/kyo`).

## Workflows

### User wants upstream source context

1. Run `ref-repos context` (or `list` if more detail needed).
2. If a relevant repo exists, read from `.ref/<owner>/<repo>/`.
3. If missing, offer to `add` it. Ask for URL and optional artifact hints from the user's dependency list.
4. When exploring published APIs, cross-check artifact hints first, then search the checkout.

### Add a reference repository

1. `ref-repos ensure`
2. Parse URL (`https://github.com/getkyo/kyo`, `git@github.com:getkyo/kyo.git`, GitLab/Codeberg equivalents).
3. **Ref selection:**
   - User supplied branch/tag/commit → `add <url> --ref <value>`
   - Otherwise → `refs <url>`, present the 4 most recent options **plus** "or provide your own", then `add` with chosen ref.
4. Pass known coordinates: `--artifacts "io.getkyo:kyo-core_3,io.getkyo:kyo-test-api_3"`
5. Confirm with `list`.

### Update a reference repository

1. If user gave a ref → `update <id> --ref <value>`
2. Otherwise → `refs <id>`, present 4 recent options + custom, then `update` with chosen ref.
3. Confirm new pin with `list`.

### Remove reference repositories

- One repo: confirm with user, then `remove <id>`
- All repos: confirm explicitly, then `purge --force`

### Repair a missing or stale manifest

When `.ref/` contains git checkouts but `manifest.yaml` is missing, corrupt, or out of
sync:

1. Run `ref-repos repair` to scan `.ref/` and rebuild the manifest from on-disk checkouts
   (reads `origin`, infers the current pin from `HEAD`, preserves artifact hints when ids match)
2. Use `repair --dry-run` to preview adds, updates, and removals without writing
3. Use `repair --keep-orphans` to retain manifest entries whose checkouts no longer exist

`ensure` is still the right first step for a brand-new project; `repair` is for recovery
when checkouts already exist.

## SCM tool selection

| Situation | Tool |
|-----------|------|
| Clone / fetch / checkout (default) | `git` |
| GitHub metadata (visibility, default branch) | `gh` when authenticated |
| User's project uses jj for their own work | still use `git` inside `.ref/` checkouts |

Do not colocate reference clones with jj bookmarks or project worktrees. `.ref/` is a separate read-only cache.

## Agent behavior

- Prefer existing `.ref/` checkouts over re-fetching remote docs when reading implementation details.
- Before adding duplicates, run `list`.
- After `add` or `update`, mention the local path and pinned ref to the user.
- When artifact hints are unknown, add them later by editing `.ref/manifest.yaml` or re-adding with `--artifacts`.
- Never stage or commit files under `.ref/`.
- Use this project's bundled skill tooling under `.claude/skills/reference-repos/` (do not duplicate scripts elsewhere).

## Examples

```bash
# Kyo upstream for dependency exploration
.claude/skills/reference-repos/ref-repos ensure
.claude/skills/reference-repos/ref-repos add https://github.com/getkyo/kyo --ref main \
  --artifacts "io.getkyo:kyo-core_3,io.getkyo:kyo-test-api_3"

# See what is available before answering "how does Kyo handle X?"
.claude/skills/reference-repos/ref-repos context

# Refresh pin after user picks a release tag
.claude/skills/reference-repos/ref-repos refs getkyo/kyo
.claude/skills/reference-repos/ref-repos update getkyo/kyo --ref v0.15.0

# Clean up one checkout
.claude/skills/reference-repos/ref-repos remove getkyo/kyo

# Rebuild manifest.yaml from existing .ref/ checkouts
.claude/skills/reference-repos/ref-repos repair
.claude/skills/reference-repos/ref-repos repair --dry-run
```
