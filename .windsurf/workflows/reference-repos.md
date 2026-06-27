---
description: Clone and manage local reference repositories under .ref/ for source exploration.
---

# reference-repos

Use the shared repo skill to clone and manage upstream reference repositories under
`.ref/` for source exploration and published-library context.

Steps:

1. Read the skill body for the full command reference:
   `plugins/reference-repos/skills/reference-repos/SKILL.md`.
2. From the repo root, run the bundled CLI:
   `plugins/reference-repos/skills/reference-repos/ref-repos <command>` (`.bat` on
   Windows). The launcher resolves the project root via git.
3. Typical flow: `ensure` once, then `add <url> [--ref VAL]`, confirm with `list`.
4. Follow the operational rules in `AGENTS.md`: add repos sequentially (never in
   parallel — concurrent writes corrupt the manifest; recover with `repair`), verify
   the branch pin, and never commit anything under `.ref/`.
