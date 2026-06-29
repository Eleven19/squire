package squire.cli.refrepo

import caseapp.*
import kyo.*
import squire.tools.refrepo.*

// -----------------------------------------------------------------------
// ensure
// -----------------------------------------------------------------------

final case class RefRepoEnsureOptions()

/** `squire ref repo ensure` — create `.ref/`, an empty manifest, and a `.gitignore` entry if absent. */
object RefRepoEnsureCommand extends KyoCommand[RefRepoEnsureOptions]:
    override def names = List(List("ref", "repo", "ensure"))
    run {
        RefRepoCli.runRef(ReferenceRepo.ensure).flatMap { _ =>
            Console.printLine("Ensured .ref/ layout")
        }
    }
end RefRepoEnsureCommand

// -----------------------------------------------------------------------
// add
// -----------------------------------------------------------------------

final case class RefRepoAddOptions(
    @Name("ref") ref: Option[String] = None,
    @Name("artifacts") artifacts: List[String] = Nil
)

/** `squire ref repo add <url> [--ref VAL] [--artifacts A]...` — clone and register a reference repository. */
object RefRepoAddCommand extends KyoCommand[RefRepoAddOptions]:
    override def names = List(List("ref", "repo", "add"))
    run { (opts, remainingArgs) =>
        remainingArgs.remaining.headOption match
            case None =>
                Abort.fail(new IllegalArgumentException("add requires a URL positional argument"))
            case Some(url) =>
                val maybeRef = opts.ref match
                    case Some(r) => Maybe.Present(r)
                    case None    => Maybe.Absent
                RefRepoCli.runRef(
                    ReferenceRepo.add(url, maybeRef, Chunk.from(opts.artifacts))
                ).flatMap { entry =>
                    Console.printLine(s"${entry.id}  ${entry.url}  ${entry.ref.value}")
                }
    }
end RefRepoAddCommand

// -----------------------------------------------------------------------
// list
// -----------------------------------------------------------------------

final case class RefRepoListOptions()

/** `squire ref repo list` — list all managed repositories from the manifest. */
object RefRepoListCommand extends KyoCommand[RefRepoListOptions]:
    override def names = List(List("ref", "repo", "list"))
    run {
        RefRepoCli.runRef(ReferenceRepo.list).flatMap { entries =>
            if entries.isEmpty then Console.printLine("(no repositories)")
            else
                Async.foreachDiscard(entries)(e =>
                    Console.printLine(s"${e.id}  ${e.url}  ${e.ref.value}")
                )
        }
    }
end RefRepoListCommand

// -----------------------------------------------------------------------
// context
// -----------------------------------------------------------------------

final case class RefRepoContextOptions()

/** `squire ref repo context` — print an agent-friendly summary of available reference repos. */
object RefRepoContextCommand extends KyoCommand[RefRepoContextOptions]:
    override def names = List(List("ref", "repo", "context"))
    run {
        RefRepoCli.runRef(ReferenceRepo.context).flatMap { report =>
            Console.printLine(report.summary)
        }
    }
end RefRepoContextCommand

// -----------------------------------------------------------------------
// refs
// -----------------------------------------------------------------------

final case class RefRepoRefsOptions(
    @Name("id") id: Option[String] = None
)

/** `squire ref repo refs <id|url>` — list recent branches and tags for a repo. */
object RefRepoRefsCommand extends KyoCommand[RefRepoRefsOptions]:
    override def names = List(List("ref", "repo", "refs"))
    run { (opts, remainingArgs) =>
        opts.id.orElse(remainingArgs.remaining.headOption) match
            case None =>
                Abort.fail(new IllegalArgumentException("refs requires an id or URL"))
            case Some(idOrUrl) =>
                RefRepoCli.runRef(ReferenceRepo.refs(idOrUrl)).flatMap { refOpts =>
                    if refOpts.isEmpty then Console.printLine("(no refs found)")
                    else
                        Async.foreachDiscard(refOpts)(r =>
                            Console.printLine(s"${r.`type`}  ${r.value}  ${r.sha.getOrElse("")}")
                        )
                }
    }
end RefRepoRefsCommand

// -----------------------------------------------------------------------
// update
// -----------------------------------------------------------------------

final case class RefRepoUpdateOptions(
    @Name("id") id: Option[String] = None,
    @Name("ref") ref: Option[String] = None
)

/** `squire ref repo update <id> --ref VAL` — fetch and re-pin a managed repository. */
object RefRepoUpdateCommand extends KyoCommand[RefRepoUpdateOptions]:
    override def names = List(List("ref", "repo", "update"))
    run { (opts, remainingArgs) =>
        opts.id.orElse(remainingArgs.remaining.headOption) match
            case None =>
                Abort.fail(new IllegalArgumentException("update requires an id"))
            case Some(id) =>
                val maybeRef = opts.ref match
                    case Some(r) => Maybe.Present(r)
                    case None    => Maybe.Absent
                RefRepoCli.runRef(ReferenceRepo.update(id, maybeRef)).flatMap { entry =>
                    Console.printLine(s"${entry.id}  ${entry.url}  ${entry.ref.value}")
                }
    }
end RefRepoUpdateCommand

// -----------------------------------------------------------------------
// remove
// -----------------------------------------------------------------------

final case class RefRepoRemoveOptions(
    @Name("id") id: Option[String] = None
)

/** `squire ref repo remove <id>` — remove a managed checkout and its manifest entry. */
object RefRepoRemoveCommand extends KyoCommand[RefRepoRemoveOptions]:
    override def names = List(List("ref", "repo", "remove"))
    run { (opts, remainingArgs) =>
        opts.id.orElse(remainingArgs.remaining.headOption) match
            case None =>
                Abort.fail(new IllegalArgumentException("remove requires an id"))
            case Some(id) =>
                RefRepoCli.runRef(ReferenceRepo.remove(id)).flatMap { _ =>
                    Console.printLine(s"Removed $id")
                }
    }
end RefRepoRemoveCommand

// -----------------------------------------------------------------------
// purge
// -----------------------------------------------------------------------

final case class RefRepoPurgeOptions()

/** `squire ref repo purge` — delete all managed checkouts and clear the manifest. */
object RefRepoPurgeCommand extends KyoCommand[RefRepoPurgeOptions]:
    override def names = List(List("ref", "repo", "purge"))
    run {
        RefRepoCli.runRef(ReferenceRepo.purge).flatMap { _ =>
            Console.printLine("Purged all reference repositories")
        }
    }
end RefRepoPurgeCommand

// -----------------------------------------------------------------------
// repair
// -----------------------------------------------------------------------

final case class RefRepoRepairOptions()

/** `squire ref repo repair` — rebuild the manifest from git checkouts discovered under `.ref/`. */
object RefRepoRepairCommand extends KyoCommand[RefRepoRepairOptions]:
    override def names = List(List("ref", "repo", "repair"))
    run {
        RefRepoCli.runRef(ReferenceRepo.repair).flatMap { report =>
            Console.printLine(
                s"Repair complete: added=${report.added} updated=${report.updated} removed=${report.removed} total=${report.total}"
            )
        }
    }
end RefRepoRepairCommand
