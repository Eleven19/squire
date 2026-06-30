# AGENTS.md

Shared agent guidance for the **squire** repository. This is the single source of
truth for agent instructions. Harness-specific files (`CLAUDE.md`,
`.github/copilot-instructions.md`, etc.) import or point back here so every agent —
Claude Code, Codex, Cursor, GitHub Copilot, Windsurf, Devin — gets the same behavior.

## What this repo is

**squire** is a Claude Code plugin marketplace AND a harness-neutral skill bundle. The
same skills are exposed to every supported agent harness from one canonical source.

## Skills

Skill source of truth lives in `skills/<name>/`: `skill.yaml` (descriptor) + `src/`
(shared body) + optional `src-<harness>/` overlays (Mill cross-platform style; a
harness-specific file wins over the shared `src/` file on collision).

Run `./mill skills.generateAll` to (re)generate the committed product: per-harness
adapters under `dist/`, plus the root `.claude-plugin/marketplace.json` index. Generated
files carry a "do not edit" header — never hand-edit them; edit `skills/` source and
regenerate.

### reference-repos

Clone and manage local reference repositories under `.ref/` for source exploration and
published-library context, with a pinned YAML manifest (`.ref/manifest.yaml`) and
artifact hints.

**Canonical source:** `skills/reference-repos/src/SKILL.md` (read it for the full
command reference and workflows). Generated harness adapters live under `dist/`.

**How to run it (any harness):** the skill ships a self-contained launcher. From the
repo root:

```bash
skills/reference-repos/src/ref-repos <command> [args]   # mac/linux
skills/reference-repos/src/ref-repos.bat <command>      # windows
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
  the finished version into a real, committed location deliberately.

### Tool/skill artifact placement — OVERRIDE

This `.dev/` layout **overrides** any tool's or skill's default artifact location. In
particular, **superpowers** (and any brainstorming/planning/research skill) MUST write to
`.dev/`, NOT to a root `docs/` folder:

- design specs (superpowers brainstorming) → `.dev/<type>/<slug>/specs/`
- implementation plans (superpowers writing-plans) → `.dev/<type>/<slug>/plans/`
- research/analyses → `.dev/<type>/<slug>/research/`

Do not create or commit a `docs/superpowers/` tree. If a design/plan becomes a durable
deliverable, promote it into a real committed location on purpose.

## Principles — functional programming

squire is functional-first and leans on **kyo** as its standard library.

- Pure functions, immutable data, referential transparency, total functions; push side
  effects to the edges.
- Express effects with kyo's pending-effect types (`A < S`): `Async`, `Abort`, `Env`,
  `Scope`, `Emit`, etc. **Do not throw** — model failure with `Abort`/`Result`.
- Data as ADTs (enums / case classes); exhaustive pattern matching; `derives Schema,
  CanEqual` on protocol/data types.
- Composition over inheritance; small, single-purpose modules with explicit interfaces.
- kyo is the standard library; consult `.ref/getkyo/kyo` for idioms and exact signatures.

## Build — Mill

- Build tool: **Mill** (version pinned in `.mill-version`). Run `./mill <task>`.
- Config is declarative-YAML-first: modules in `build.mill.yaml` / `package.mill.yaml`;
  shared logic + code-gen in the `mill-build/` meta-build (`millbuild.*`).
- **JDK 25+ is the project minimum** (Java 25 LTS); `mill-jvm-version: temurin:25`, modules
  compile with `-release:25`.
- Scala 3.8.4. kyo resolved from `https://central.sonatype.com/repository/maven-snapshots/`,
  pinned to an exact dynver snapshot string (kyo's snapshot `maven-metadata.xml` can be
  stale — never rely on it to pick "latest").
- Common tasks: `./mill resolve _`, `./mill squire.cli.run greet`, `./mill squire.cli.run mcp`,
  `./mill __.test`.
- The generated `dist/` tree and `.claude-plugin/marketplace.json` are checked in and
  must stay in sync with `skills/` — regenerate with `./mill skills.generateAll`. CI
  (the `codegen` job in the `CI` workflow) fails on drift via `git diff --exit-code dist
  .claude-plugin/marketplace.json`.

## Conventions

- `.ref/`, `.omc/`, `.worktrees/`, `.dev/` are agent-scratch and gitignored. Do not commit them.
- Keep shared guidance HERE. Put only harness-specific overrides in that harness's file.
- Validate plugin/marketplace manifests before publishing:
  `claude plugin validate .` and `claude plugin validate ./dist/plugins/<name>`.
