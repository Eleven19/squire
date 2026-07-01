---
name: release-management
description: Prepare, invoke, monitor, troubleshoot, and smoke-test squire releases driven by the release Mill module and the Release workflow.
---

# Release Management

Use for cutting a squire release: preparing the version and changelog, kicking off
the `Release` workflow, watching it, diagnosing failures, and smoke-testing the
result. The full reference lives in `docs/contributing/releasing.md`.

## Prep

1. Confirm the changelog is release-ready (fails on a missing `[Unreleased]` or bucket):

   ```bash
   ./mill release.run ready
   ```

2. Preview the next version from the commit history:

   ```bash
   ./mill release.run next
   ```

3. Promote `[Unreleased]` into a dated section (no argument derives the version):

   ```bash
   ./mill release.run promote
   ```

4. Confirm codegen is in sync:

   ```bash
   ./mill skills.generateAll
   git diff --exit-code dist .claude-plugin/marketplace.json
   ```

5. Review the assembled notes:

   ```bash
   ./mill release.run notes <version>
   ```

## Invoke

- Tag push (normal stable path), using the version `promote` printed:

  ```bash
  git tag v<version> && git push origin v<version>
  ```

- Or run the `Release` workflow via `workflow_dispatch` with `target` set to
  `release`, `central`, or `both`.

Tags carry a leading `v`; every other version value omits it.

## Monitor

```bash
gh run list --workflow=Release
gh run watch <id>
gh run view <id> --log-failed
```

## Troubleshoot

- Missing changelog section: the gate failed because `CHANGELOG.md` has no exact
  dated `## [<version>]` section. Fix the changelog, then re-check with
  `./mill release.run check <version>`.
- Native-image matrix failure: open the failing platform job and inspect its
  native-image output. If only one platform failed, the problem is
  platform-specific; if all failed at the same step, suspect the shared build
  logic.
- Maven Central auth: the `publish-central` job bridges org secrets
  `ELEVEN19_SONATYPE_USERNAME/PASSWORD` and `ELEVEN19_IO_PGP_SECRET_BASE64/PASSPHRASE`
  to the `MILL_*` env vars. A 401 or signing failure usually means a missing or
  stale secret.
- Version mismatch: the tag has a `v` prefix; the workflow version input and
  changelog heading do not. Confirm both forms agree.
- Codegen drift: regenerate and diff:

  ```bash
  ./mill skills.generateAll
  git diff --exit-code dist .claude-plugin/marketplace.json
  ```

## Smoke

```bash
./mill release.run smoke <version>
./mill release.run smoke <version> --bin path/to/squire
```

Read the per-check PASS/FAIL output. Any FAIL means the built binary did not
behave as expected; investigate before announcing the release.

## References

- the `release/` Mill module
- `CHANGELOG.md`
- `docs/contributing/commits.md`
- `docs/contributing/changelog.md`
- `docs/contributing/releasing.md`
- `.github/workflows/release.yml`
