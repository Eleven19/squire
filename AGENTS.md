# AGENTS.md

Shared agent guidance for the **squire** repository. This is the single source of
truth for agent instructions. Harness-specific files (`CLAUDE.md`,
`.github/copilot-instructions.md`, etc.) import or point back here so every agent —
Claude Code, Codex, Cursor, GitHub Copilot, Windsurf, Devin — gets the same behavior.

## What this repo is

**squire** is a Claude Code plugin marketplace AND a harness-neutral skill bundle. The
same skills are exposed to every supported agent harness from one canonical source.

## Skills

### reference-repos

Clone and manage local reference repositories under `.ref/` for source exploration and
published-library context, with a pinned YAML manifest (`.ref/manifest.yaml`) and
artifact hints.

**Canonical source:** `plugins/reference-repos/skills/reference-repos/SKILL.md`
(read it for the full command reference and workflows). Every harness reaches it via a
symlink (`.agents/skills/`, `.claude/skills/`, `.cursor/skills/`, `.devin/skills/`) or
a pointer adapter (`.cursor/rules/`, `.github/prompts/`, `.windsurf/workflows/`).

**How to run it (any harness):** the skill ships a self-contained launcher. From the
repo root:

```bash
.agents/skills/reference-repos/ref-repos <command> [args]   # mac/linux
.agents/skills/reference-repos/ref-repos.bat <command>      # windows
```

The launcher resolves the project root via git, so it works from any subdirectory.
Common commands: `ensure`, `add <url> [--ref VAL]`, `list`, `context`,
`update <id>`, `remove <id>`, `repair`. Read the `SKILL.md` before driving it.

**Important operational notes (learned, apply them):**

- Run `ensure` once before the first `add` in a project.
- `add` writes `.ref/manifest.yaml` non-atomically — **add repos sequentially, never in
  parallel**, or concurrent writes corrupt the manifest (recover with `repair`).
- Some repos default-clone to a non-`main` branch (e.g. Dolt repos land on
  `__dolt_remote_info__`). Verify the pin with `list`; re-pin to the real default branch
  if wrong.
- Never stage or commit anything under `.ref/` — it is gitignored on purpose.

## Agent dev work product — `.dev/`

Store all agent-generated working artifacts (research, specs, plans) under `.dev/`.
**`.dev/` is gitignored** — it is local-only scratch and MUST NOT be committed. This
keeps agent instructions and in-progress thinking out of source control; only finished,
intentional deliverables (code, real docs) get committed elsewhere.

Layout — one folder per work effort, classified by size, each with fixed sub-folders:

```
.dev/
  <type>/                 # type ∈ { campaign | spree | spike }
    <slug>/               # kebab-case name of the effort
      research/           # findings, analyses, source notes
      specs/              # specifications, requirements, designs
      plans/              # implementation/execution plans
```

Effort types:

- **spike** — short, throwaway exploration or investigation (hours). Default for ad-hoc
  research.
- **spree** — a small-to-medium focused burst (a few related changes).
- **campaign** — a large, multi-phase initiative.

Rules:

- Put research in `research/`, specs in `specs/`, plans in `plans/` — never loose at the
  effort root.
- Never `git add` anything under `.dev/`. If a `.dev/` artifact deserves to be kept, copy
  the finished version into a real, committed location (e.g. `docs/`) deliberately.

## Conventions

- `.ref/`, `.omc/`, `.worktrees/`, `.dev/` are agent-scratch and gitignored. Do not commit them.
- Keep shared guidance HERE. Put only harness-specific overrides in that harness's file.
- Validate plugin/marketplace manifests before publishing:
  `claude plugin validate .` and `claude plugin validate ./plugins/<name>`.
