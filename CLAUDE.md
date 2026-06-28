# CLAUDE.md

@AGENTS.md

Shared repository guidance lives in `AGENTS.md` (imported above). Keep only
Claude-specific additions or overrides in this file.

## Claude-specific

- The `reference-repos` skill is packaged as a Claude Code plugin in this marketplace.
  The plugin is generated into `dist/plugins/reference-repos/` by `./mill skills.generateAll`.
- Install it elsewhere via: `/plugin marketplace add Eleven19/squire` then
  `/plugin install reference-repos@squire`.
