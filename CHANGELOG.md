# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This
project uses git tags like `v0.1.0`; changelog versions omit the leading `v`. From 0.1.0
onward, commits follow [Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added

### Changed

### Fixed

### Documentation

### CI

## [0.1.0] - 2026-06-30

### Added

- Mill 1.2.0-RC1 build foundation with squire-core, squire-tools, and squire-cli modules (kyo, Scala 3.8.4).
- reference-repos skill: ReferenceRepo ArrowEffect tool (git via kyo.Command, YAML manifest), the `squire ref repo` CLI adapter, and the `ref-repo-*` MCP tools.
- Cross-harness codegen subsystem generating per-harness adapters under `dist/` and the marketplace index.
- CLI packaging and publication: fat assembly jar, GraalVM native images for four platforms, launcher scripts, and decoupled GitHub Release plus Maven Central publish paths.
- `squire info` command with human and `--json` output.
- Conventional-commit-driven release tooling: the `release` Mill module (`./mill release.run next|version|check|notes|promote|smoke`) producing Keep a Changelog notes and SemVer versions.

### Changed

- Merged squire-mcp into squire-cli as subcommands.
- Retired symlink-based harness wiring in favor of the codegen model.

### Fixed

### Documentation

- Documented the shared-tool pattern (CONTRIBUTING) and the tool catalog (README).
- Added contributing guides for commit conventions, changelog maintenance, and releasing.

### CI

- Changelog-gated GitHub release notes (curated changelog section plus generated commit notes).
