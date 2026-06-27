# GitHub Copilot instructions

This repository's shared agent guidance lives in [`AGENTS.md`](../AGENTS.md), which
Copilot reads natively. This file restates the essentials for Copilot.

## Skills

### reference-repos

Clone and manage local reference repositories under `.ref/` for source exploration and
published-library context.

- Skill body + full command reference:
  `plugins/reference-repos/skills/reference-repos/SKILL.md`.
- Run the bundled CLI from the repo root:
  `plugins/reference-repos/skills/reference-repos/ref-repos <command>` (`.bat` on
  Windows). The launcher resolves the project root via git.
- Invoke the prompt form with `/reference-repos` (see
  `.github/prompts/reference-repos.prompt.md`).

Operational rules (add repos sequentially, recover a corrupt manifest with `repair`,
verify branch pins, never commit `.ref/`) are in `AGENTS.md`.
