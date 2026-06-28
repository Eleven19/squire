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

Vendor this repo (or its generated harness files) into your project. Each harness
auto-discovers its native entry point from the `dist/` tree — no extra install step:

| Harness | Generated file(s) in `dist/` |
|---------|------------------------------|
| Claude Code | `plugins/reference-repos/` (plugin), root marketplace index |
| Codex (OpenAI) | `agents/skills/reference-repos/` |
| Cursor | `cursor/rules/reference-repos.mdc` |
| GitHub Copilot | `github/copilot-instructions.md`, `github/prompts/reference-repos.prompt.md` |
| Windsurf | `windsurf/workflows/reference-repos.md` |
| Devin | `agents/skills/reference-repos/` (shared neutral Agent Skills copy) |

## Skills

| Skill | Description |
|-------|-------------|
| [`reference-repos`](./skills/reference-repos) | Clone and manage local reference repositories under `.ref/` for source exploration and published-library context, with a pinned YAML manifest and artifact hints. |

## How one skill reaches every harness

Each skill has **one source of truth** in `skills/<name>/`; `./mill skills.generateAll`
produces all the harness-specific wiring and commits it under `dist/`.

```
AGENTS.md                                          # single source of truth for instructions
CLAUDE.md                                          # @AGENTS.md shim (Claude doesn't read AGENTS.md natively)
.claude-plugin/marketplace.json                    # Claude marketplace index (generated)

skills/reference-repos/                            # ← CANONICAL skill source
  skill.yaml                                       #   descriptor
  src/                                             #   shared body (SKILL.md, launchers, scripts)
  src-<harness>/                                   #   optional harness overlays (wins on collision)

dist/                                              # generated product — committed, do not hand-edit
  agents/skills/reference-repos/                   #   Codex / Agent Skills adapter
  cursor/rules/reference-repos.mdc                 #   Cursor rules adapter
  github/copilot-instructions.md                   #   GitHub Copilot adapter
  github/prompts/reference-repos.prompt.md
  windsurf/workflows/reference-repos.md            #   Windsurf adapter
  plugins/reference-repos/                         #   redistributable Claude plugin
    .claude-plugin/plugin.json
    skills/reference-repos/                        #   plugin-embedded skill copy
```

Generated files carry a "do not edit" header. To change a skill, edit `skills/` source
and run `./mill skills.generateAll` to regenerate `dist/`.

## Develop

```bash
./mill skills.generateAll                         # regenerate dist/ and marketplace index
claude plugin validate .                          # marketplace manifest
claude plugin validate ./dist/plugins/reference-repos  # a plugin manifest
```

Adding a new cross-harness skill: author `skills/<name>/skill.yaml` + `src/`, wire it
into the Mill codegen, then run `./mill skills.generateAll` — the harness adapters and
marketplace entry are generated into `dist/` automatically.
