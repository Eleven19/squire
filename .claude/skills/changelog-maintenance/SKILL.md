---
name: changelog-maintenance
description: Maintain the checked-in Keep a Changelog file for release-worthy changes in the squire repo.
---

# Changelog Maintenance

Use whenever a change is user-visible, operator-visible, or affects release automation, docs,
or CI in a way that should appear in release notes.

## Required checks

1. Update `CHANGELOG.md` under `## [Unreleased]`.
2. Use one of: `Added`, `Changed`, `Fixed`, `Documentation`, `CI`.
3. Keep wording concise and release-note ready.
4. Write the commit as a Conventional Commit (see `docs/contributing/commits.md`).
5. Verify against `docs/contributing/changelog.md`.

## Verification

```bash
rg -n "^## \[Unreleased\]" CHANGELOG.md
./mill release.run check <version>   # only when a release section exists
```
