---
description: Clone and manage local reference repositories under .ref/ for source exploration.
name: reference-repos
---

Use the `reference-repos` skill to clone and manage upstream reference repositories
under `.ref/` for source exploration and published-library context.

Skill body + full command reference:
`plugins/reference-repos/skills/reference-repos/SKILL.md`.

Run the bundled CLI from the repo root:
`plugins/reference-repos/skills/reference-repos/ref-repos <command>` (`.bat` on Windows).

Follow the operational rules in `AGENTS.md`: run `ensure` first, add repos one at a time
(never in parallel), verify the branch pin with `list`, recover a corrupt manifest with
`repair`, and never commit anything under `.ref/`.
