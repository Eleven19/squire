package squire.release

import caseapp.Name
import caseapp.core.app.{Command, CommandsEntryPoint}
import kyo.*

final case class NoOpts()
final case class VersionOptions(@Name("snapshot") snapshot: Boolean = false)
final case class PromoteOptions(@Name("date") date: Option[String] = None)
final case class SmokeOptions(@Name("bin") bin: Option[String] = None)

/** Adapt a command body's `Abort[String]` to the `Abort[Throwable]` the KyoApp runner expects, so a failed command
  * surfaces as a non-zero process exit. Mirrors the conversion in `squire.cli.refrepo.RefRepoCli`.
  */
private def cli[A](eff: A < (Async & Abort[String]))(using Frame): A < (Async & Abort[Throwable]) =
    Abort.run[String](eff).map {
        case Result.Success(a)   => a
        case Result.Failure(msg) => Abort.fail[Throwable](new RuntimeException(msg))
        case Result.Panic(t)     => throw t
    }

/** `release next`:print the next version computed from the conventional commits since the previous tag. */
object NextCommand extends KyoCommand[NoOpts]:
    override def name = "next"
    run { (_, _) => cli(Commands.next) }
end NextCommand

/** `release version [--snapshot]`:print the build version (`git describe`) or the next snapshot version. */
object VersionCommand extends KyoCommand[VersionOptions]:
    override def name = "version"
    run { (opts, _) => cli(Commands.versionString(opts.snapshot)) }
end VersionCommand

/** `release check <version>`:fail unless CHANGELOG.md has a dated section for the version. */
object CheckCommand extends KyoCommand[NoOpts]:
    override def name = "check"
    run { (_, remaining) => cli(Commands.version(remaining.remaining).map(Commands.check)) }
end CheckCommand

/** `release notes <version>`:write assembled release notes to `out/release-notes-<version>.md`. */
object NotesCommand extends KyoCommand[NoOpts]:
    override def name = "notes"
    run { (_, remaining) => cli(Commands.version(remaining.remaining).map(Commands.notes)) }
end NotesCommand

/** `release promote [<version>] [--date YYYY-MM-DD]`:stamp the Unreleased section as a dated release. */
object PromoteCommand extends KyoCommand[PromoteOptions]:
    override def name = "promote"
    run { (opts, remaining) =>
        cli(Commands.promote(Maybe.fromOption(remaining.remaining.headOption), Maybe.fromOption(opts.date)))
    }
end PromoteCommand

/** `release smoke <version> [--bin PATH]`:run smoke checks against a built (or downloaded) squire binary. */
object SmokeCommand extends KyoCommand[SmokeOptions]:
    override def name = "smoke"
    run { (opts, remaining) =>
        cli(Commands.version(remaining.remaining).map(v => Commands.smoke(v, Maybe.fromOption(opts.bin))))
    }
end SmokeCommand

/** `release`:the changelog and release tooling entrypoint. */
object Main extends CommandsEntryPoint:
    def progName: String = "release"
    override def description = "squire release + changelog tooling"
    def commands: Seq[Command[?]] =
        Seq(NextCommand, VersionCommand, CheckCommand, NotesCommand, PromoteCommand, SmokeCommand)
end Main
