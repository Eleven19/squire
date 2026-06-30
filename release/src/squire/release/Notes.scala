package squire.release

import kyo.*

/** Assembles release notes by combining the curated CHANGELOG section for a version with a generated section grouped
  * from the conventional commits since the previous tag.
  *
  * Git operations use [[kyo.Command]] and surface failures as `Abort[String]`; the CHANGELOG read happens at the I/O
  * edge via [[kyo.Sync]]. The pure grouping logic in [[generated]] is effect-free and unit-tested.
  */
object Notes:

    /** Run `git <args>` in `cwd`; fail with a descriptive message on a non-zero exit or a command launch failure. */
    private def git(cwd: kyo.Path, args: String*)(using Frame): String < (Async & Abort[String]) =
        val all = "git" +: args
        Abort
            .run[CommandException] {
                Command(all*).cwd(cwd).redirectErrorStream(true).textWithExitCode
            }
            .flatMap {
                case Result.Success((out: String, code: ExitCode)) =>
                    if code.isSuccess then out
                    else Abort.fail(s"git ${args.mkString(" ")} failed (exit ${code.toInt}): ${out.trim}")
                case Result.Failure(e) => Abort.fail(s"git ${args.mkString(" ")} failed: $e")
                case Result.Panic(t)   => throw t
            }

    /** The most recent reachable tag, or Absent when the repository has no tags yet. */
    def previousTag(cwd: kyo.Path)(using Frame): Maybe[String] < (Async & Abort[String]) =
        Abort.run[String](git(cwd, "describe", "--tags", "--abbrev=0")).map {
            case Result.Success(out) if out.trim.nonEmpty => Maybe(out.trim)
            case _                                        => Maybe.empty
        }

    /** Commit subjects from the previous tag (exclusive) to HEAD; the full history when there is no previous tag. */
    def commitSubjects(cwd: kyo.Path)(using Frame): Chunk[String] < (Async & Abort[String]) =
        previousTag(cwd).map { prev =>
            val range = prev.map(t => s"$t..HEAD").getOrElse("HEAD")
            git(cwd, "log", "--format=%s", range).map { out =>
                Chunk.from(out.split("\n").iterator.map(_.trim).filter(_.nonEmpty).toSeq)
            }
        }

    /** Pure: group parsed commit subjects by changelog bucket; omit empty buckets. */
    def generated(subjects: Chunk[String])(using Frame): String < Any =
        Kyo.foreach(subjects)(s =>
            ConventionalCommit.parse(s).map(c => (ConventionalCommit.bucketOf(c), describe(s, c)))
        ).map { pairs =>
            val byBucket = pairs.groupBy(_._1)
            Changelog.Buckets.flatMap { bucket =>
                byBucket.get(bucket).map(_.map(_._2)).filter(_.nonEmpty) match
                    case Some(items) => Chunk(s"### $bucket", "") ++ items.map(i => s"- $i") ++ Chunk("")
                    case None        => Chunk.empty
            }.mkString("\n")
        }

    private def describe(raw: String, c: Maybe[ConventionalCommit]): String =
        c match
            case Maybe.Present(cc) => cc.description.head.toUpper.toString + cc.description.tail
            case Maybe.Absent      => raw

    /** Read CHANGELOG.md under `repoRoot`, validate it carries a dated section for `version`, and render the hybrid
      * body: the curated section followed by the generated commit-grouped section.
      */
    def build(repoRoot: kyo.Path, version: String)(using Frame): String < (Async & Abort[String]) =
        Sync.defer(java.nio.file.Files.readString((repoRoot / "CHANGELOG.md").toJava)).map { text =>
            Changelog.parse(text).map { cl =>
                Changelog.validate(cl, version).map { _ =>
                    Changelog.extractSection(cl, version) match
                        case Maybe.Present(section) =>
                            val curated = renderSection(section)
                            commitSubjects(repoRoot).map { subjects =>
                                generated(subjects).map { gen =>
                                    val genBlock = if gen.trim.isEmpty then "No additional generated notes." else gen
                                    s"## Changelog\n\n$curated\n\n---\n\n## Generated Notes\n\n$genBlock\n"
                                }
                            }
                        case Maybe.Absent =>
                            Abort.fail(s"CHANGELOG has no section [$version]")
                }
            }
        }

    private def renderSection(s: Section): String =
        s.buckets.filter(_.entries.nonEmpty).flatMap { b =>
            Chunk(s"### ${b.name}", "") ++ b.entries.map(e => s"- $e") ++ Chunk("")
        }.mkString("\n").trim
end Notes
