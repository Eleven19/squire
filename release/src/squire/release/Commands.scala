package squire.release

import kyo.*

/** Effectful command bodies for the release CLI: the impure edge that reads CHANGELOG.md, shells out to git and gh, and
  * writes notes. The pure logic lives in [[ConventionalCommit]], [[Version]], [[Changelog]], and [[Notes]]; this object
  * only wires it to the filesystem and process world. Side effects are deferred inside [[kyo.Sync]] so a command value
  * is inert until run.
  */
object Commands:

    /** The working directory the commands operate against (the repository root when invoked from it). */
    def root(using Frame): kyo.Path = kyo.Path(sys.props.getOrElse("user.dir", "."))

    /** The first positional argument, or an abort describing the missing `<version>`. */
    def version(remaining: Seq[String])(using Frame): String < Abort[String] =
        remaining.headOption match
            case Some(v) => v
            case None    => Abort.fail("missing <version> argument")

    private def readChangelog(using Frame): Changelog < (Async & Abort[String]) =
        Sync.defer(java.nio.file.Files.readString((root / "CHANGELOG.md").toJava)).map(Changelog.parse)

    /** Compute the next version from the commits since the previous tag (or 0.0.0 when there is no tag). */
    def computeNext(using Frame): SemVer < (Async & Abort[String]) =
        Notes.previousTag(root).map { prev =>
            val last = prev.flatMap(t => Version.parseTag(t)).getOrElse(SemVer(0, 0, 0))
            Notes.commitSubjects(root).map { subjects =>
                Kyo.foreach(subjects)(ConventionalCommit.parse).map(parsed => Version.nextVersion(last, parsed))
            }
        }

    /** Print the computed next version. */
    def next(using Frame): Unit < (Async & Abort[String]) =
        computeNext.map(v => Console.printLine(v.toString))

    /** Print a version string: the snapshot of the next version, or `git describe` for the current build. */
    def versionString(snapshot: Boolean)(using Frame): Unit < (Async & Abort[String]) =
        if snapshot then computeNext.map(v => Console.printLine(v.copy(prerelease = Maybe("SNAPSHOT")).toString))
        else gitDescribe.map(d => Console.printLine(d.getOrElse("0.0.0")))

    private def gitDescribe(using Frame): Maybe[String] < (Async & Abort[String]) =
        Abort.run[CommandException] {
            Command("git", "describe", "--tags", "--long").cwd(root).redirectErrorStream(true).textWithExitCode
        }.map {
            case Result.Success((out: String, code: ExitCode)) if code.isSuccess => Maybe(out.trim)
            case _                                                               => Maybe.empty
        }

    /** Validate that CHANGELOG.md carries a dated section for `v`. */
    def check(v: String)(using Frame): Unit < (Async & Abort[String]) =
        readChangelog.map(cl => Changelog.validate(cl, v)).map(_ => Console.printLine(s"CHANGELOG [$v] OK"))

    /** Build the release notes for `v` and write them under `out/`. */
    def notes(v: String)(using Frame): Unit < (Async & Abort[String]) =
        Notes.build(root, v).map { body =>
            val outDir = root / "out"
            Sync.defer {
                java.nio.file.Files.createDirectories(outDir.toJava)
                val target = outDir / s"release-notes-$v.md"
                java.nio.file.Files.writeString(target.toJava, body)
                target.toString
            }.map(Console.printLine)
        }

    /** Promote the Unreleased section to `explicit` (or the computed next version), dated `date` (or today). */
    def promote(explicit: Maybe[String], date: Maybe[String])(using Frame): Unit < (Async & Abort[String]) =
        val versionEff: String < (Async & Abort[String]) =
            explicit match
                case Maybe.Present(v) => v
                case Maybe.Absent     => computeNext.map(_.toString)
        versionEff.map { v =>
            readChangelog.map { cl =>
                val d        = date.getOrElse(java.time.LocalDate.now.toString)
                val promoted = Changelog.promote(cl, v, d)
                Sync.defer(java.nio.file.Files.writeString((root / "CHANGELOG.md").toJava, Changelog.render(promoted)))
                    .map(_ => Console.printLine(s"Promoted Unreleased -> [$v] - $d. Next: git tag v$v && git push origin v$v"))
            }
        }

    /** Resolve a binary for `v` (download from the GitHub release if not given) and run the smoke checks against it. */
    def smoke(v: String, bin: Maybe[String])(using Frame): Unit < (Async & Abort[String]) =
        resolveBin(v, bin).map { binPath =>
            Smoke.checks(root, binPath).map { checks =>
                Kyo.foreach(checks)(c => Console.printLine(s"[${if c.ok then "PASS" else "FAIL"}] ${c.name}: ${c.detail}")).map { _ =>
                    if checks.forall(_.ok) then () else Abort.fail("one or more smoke checks failed")
                }
            }
        }

    private def resolveBin(v: String, bin: Maybe[String])(using Frame): kyo.Path < (Async & Abort[String]) =
        val chosen: Maybe[String] = bin.orElse(Maybe.fromOption(sys.env.get("SQUIRE_BIN")))
        chosen match
            case Maybe.Present(p) => kyo.Path(p)
            case Maybe.Absent =>
                val asset = Smoke.platformAsset
                Sync.defer(java.nio.file.Files.createTempDirectory("squire-smoke-").toString).map { tmp =>
                    Abort.run[CommandException] {
                        Command("gh", "release", "download", s"v$v", "--pattern", asset, "--dir", tmp).cwd(root).textWithExitCode
                    }.map {
                        case Result.Success((_, code: ExitCode)) if code.isSuccess =>
                            val path = kyo.Path(tmp) / asset
                            Sync.defer {
                                if !sys.props.getOrElse("os.name", "").toLowerCase.contains("win") then
                                    java.nio.file.Files.setPosixFilePermissions(
                                        path.toJava,
                                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
                                    )
                                path
                            }
                        case other => Abort.fail(s"failed to download $asset for v$v: $other")
                    }
                }
end Commands
