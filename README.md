# squire

Your trusty helper who is anxious to see you succeed. "All in a knight's work!" — especially when you have your trusty squire.

**squire** is a [Claude Code plugin marketplace](https://docs.claude.com/en/docs/claude-code/plugins) **and a harness-neutral skill bundle** from Eleven19. The same skills work across Claude Code, Codex, Cursor, GitHub Copilot, Windsurf, and Devin — defined once, exposed to each harness natively.

## Install

### Claude Code (plugin marketplace)

```bash
/plugin marketplace add Eleven19/squire     # or:  /plugin marketplace add ./path/to/squire
/plugin install reference-repos@squire
```

### Any other harness

Vendor this repo (or its harness files) into your project. Each harness auto-discovers
its native entry point — no install step:

| Harness | Reads |
|---------|-------|
| Claude Code | `CLAUDE.md` (→ `@AGENTS.md`), `.claude/skills/`, plugin marketplace |
| Codex (OpenAI) | `AGENTS.md`, `.agents/skills/` (native Agent Skills) |
| Cursor | `AGENTS.md`, `.cursor/rules/*.mdc`, `.cursor/skills/` |
| GitHub Copilot | `AGENTS.md`, `.github/copilot-instructions.md`, `.github/prompts/` |
| Windsurf | `AGENTS.md`, `.windsurf/workflows/` |
| Devin | `AGENTS.md`, `.devin/skills/` |

## Skills

| Skill | Description |
|-------|-------------|
| [`reference-repos`](./plugins/reference-repos/skills/reference-repos) | Clone and manage local reference repositories under `.ref/` for source exploration and published-library context, with a pinned YAML manifest and artifact hints. |

## How one skill reaches every harness

There is **one canonical copy** of each skill; everything else is a symlink or a thin
pointer back to it — no duplicated content to drift.

```
AGENTS.md                                          # single source of truth for instructions
CLAUDE.md                                          # @AGENTS.md shim (Claude doesn't read AGENTS.md natively)
.claude-plugin/marketplace.json                    # Claude marketplace manifest

plugins/reference-repos/                            # the redistributable Claude plugin
  .claude-plugin/plugin.json
  skills/reference-repos/                           # ← CANONICAL skill (self-contained: SKILL.md + mill CLI)

.agents/skills/reference-repos   ─┐
.claude/skills/reference-repos   ─┤ symlinks → the canonical skill above
.cursor/skills/reference-repos   ─┤
.devin/skills/reference-repos    ─┘

.cursor/rules/reference-repos.mdc                  # thin pointer adapters
.github/copilot-instructions.md
.github/prompts/reference-repos.prompt.md
.windsurf/workflows/reference-repos.md
```

The canonical skill lives **inside the plugin** so it stays self-contained when Claude
copies the plugin to its install cache (an escaping symlink would break there). The
harness directories symlink *into* it, which every harness resolves when reading the
working tree.

> **Windows / symlinks:** the cross-harness wiring uses git symlinks, which only
> materialize when your clone has `core.symlinks=true`. Run `scripts/enable-symlinks.sh`
> (or `scripts\enable-symlinks.bat`), clone with `git clone -c core.symlinks=true`, or
> use the Claude plugin install (copies real files). See [`CONTRIBUTING.md`](./CONTRIBUTING.md#setup).

## Develop

```bash
claude plugin validate .                          # marketplace manifest
claude plugin validate ./plugins/reference-repos  # a plugin manifest
```

Adding a new cross-harness skill: drop the canonical `skills/<name>/` under a plugin,
add the marketplace entry, symlink it into `.agents/.claude/.cursor/.devin`, and add the
`.cursor/rules`, `.github/prompts`, `.windsurf/workflows` pointer adapters.
