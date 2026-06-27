# Contributing to squire

## Setup

squire wires one canonical skill into every harness using **git symlinks**
(`.agents/`, `.claude/`, `.cursor/`, `.devin/` → `plugins/<plugin>/skills/<skill>`).
Those symlinks only materialize when your clone has `core.symlinks=true`.

### macOS / Linux

Symlinks work by default. Nothing to do. If you cloned with symlinks disabled:

```sh
./scripts/enable-symlinks.sh
```

### Windows

Git on Windows often auto-detects `core.symlinks=false`, leaving the symlink files
as plain text holding their target path. Fix it one of two ways:

```sh
# Best: clone with symlinks enabled from the start
git clone -c core.symlinks=true https://github.com/Eleven19/squire

# Or, in an existing clone (run from an elevated shell or with Developer Mode on):
scripts\enable-symlinks.bat
```

Requirements for Windows symlinks: Git installed with symlink support, plus either
**Developer Mode** enabled or an **elevated (admin)** shell.

> `core.symlinks` is git client config, auto-detected from the filesystem at clone
> time. It cannot be forced from the repository (not via `.gitattributes` or a tracked
> `.gitconfig` — git ignores repo-level gitconfig). The scripts above set it per clone.

Alternatively, Claude Code users can skip symlinks entirely and install the plugin from
the marketplace, which copies real files:

```
/plugin marketplace add Eleven19/squire
/plugin install reference-repos@squire
```

## Validate

Before publishing manifest changes:

```sh
claude plugin validate .                          # marketplace
claude plugin validate ./plugins/reference-repos  # a plugin
```

## Adding a cross-harness skill

1. Add the canonical skill under `plugins/<plugin>/skills/<name>/` (self-contained:
   `SKILL.md` + any tooling).
2. Register it in `.claude-plugin/marketplace.json` and the plugin's
   `.claude-plugin/plugin.json`.
3. Symlink it into the harness dirs:
   `.agents/skills/`, `.claude/skills/`, `.cursor/skills/`, `.devin/skills/`.
4. Add the thin pointer adapters: `.cursor/rules/<name>.mdc`,
   `.github/prompts/<name>.prompt.md`, `.windsurf/workflows/<name>.md`.
5. Document it in `AGENTS.md`.
