# CLAUDE.md

@AGENTS.md

Shared repository guidance lives in `AGENTS.md` (imported above). Keep only
Claude-specific additions or overrides in this file.

## Claude-specific

- The `reference-repos` skill is also packaged as a Claude Code plugin in this
  marketplace. In this repo it auto-loads from `.claude/skills/reference-repos`
  (a symlink to the canonical `plugins/reference-repos/skills/reference-repos`).
- Install it elsewhere via: `/plugin marketplace add Eleven19/squire` then
  `/plugin install reference-repos@squire`.
