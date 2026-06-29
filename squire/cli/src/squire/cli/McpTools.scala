package squire.cli

import kyo.*
import kyo.McpHandler.ToolOutcome
import squire.cli.refrepo.RefRepoCli
import squire.tools.refrepo.*

/** MCP input types for no-arg ref-repo-* tools. */
case class RefRepoAddIn(
    url: String,
    ref: Option[String] = None,
    artifacts: List[String] = Nil
) derives Schema, CanEqual

case class RefRepoIdIn(id: String) derives Schema, CanEqual

case class RefRepoUpdateIn(
    id: String,
    ref: Option[String] = None
) derives Schema, CanEqual

case class RefRepoRefsIn(idOrUrl: String) derives Schema, CanEqual

/** MCP tool handlers exposing [[squire.tools.refrepo.ReferenceRepo]] operations as `ref-repo-*` tools.
  *
  * Each tool maps a typed input to the corresponding [[ReferenceRepo]] op, runs it via
  * [[RefRepoCli.runRef]] (which resolves the project root and converts [[RefRepoError]] to
  * [[Throwable]]), and surfaces failures as `isError = true` tool results the model can see.
  * No-arg operations use [[Unit]] as the input type.
  */
object McpTools:

    /** Run a [[ReferenceRepo]] op, resolving the project root and converting [[RefRepoError]] to a
      * [[Throwable]] message.
      */
    private def runOp[A](op: A < ReferenceRepo)(using Frame): A < (Async & Abort[Throwable]) =
        RefRepoCli.runRef(op)

    /** Execute a typed op and return either a structured [[ToolOutcome.ok]] or [[ToolOutcome.error]]. */
    private def exec[A: Schema](op: A < ReferenceRepo)(using Frame): ToolOutcome < Async =
        Abort.run[Throwable](runOp(op)).map {
            case Result.Success(a) => ToolOutcome.ok(a)
            case Result.Failure(t) => ToolOutcome.error(t.getMessage)
            case Result.Panic(t)   => throw t
        }

    /** Execute a Unit op and return a text confirmation on success. */
    private def execUnit(op: Unit < ReferenceRepo, msg: String)(using Frame): ToolOutcome < Async =
        Abort.run[Throwable](runOp(op)).map {
            case Result.Success(_) => ToolOutcome.ok(McpContent.text(msg))
            case Result.Failure(t) => ToolOutcome.error(t.getMessage)
            case Result.Panic(t)   => throw t
        }

    val ensure: McpHandler[Unit, ToolOutcome, Nothing] =
        McpHandler.toolRaw[Unit](
            "ref-repo-ensure",
            "Create the .ref/ directory, an empty manifest, and a .gitignore entry if absent"
        ) { _ =>
            execUnit(ReferenceRepo.ensure, "Reference repository layout initialized.")
        }

    val add: McpHandler[RefRepoAddIn, ToolOutcome, Nothing] =
        McpHandler.toolRaw[RefRepoAddIn](
            "ref-repo-add",
            "Clone and register a reference repository under .ref/"
        ) { in =>
            exec(ReferenceRepo.add(in.url, Maybe.fromOption(in.ref), Chunk.from(in.artifacts)))
        }

    val list: McpHandler[Unit, ToolOutcome, Nothing] =
        McpHandler.toolRaw[Unit](
            "ref-repo-list",
            "List all managed reference repositories from the manifest"
        ) { _ =>
            exec(ReferenceRepo.list)
        }

    val context: McpHandler[Unit, ToolOutcome, Nothing] =
        McpHandler.toolRaw[Unit](
            "ref-repo-context",
            "Return an agent-friendly summary of available reference repositories"
        ) { _ =>
            exec(ReferenceRepo.context)
        }

    val refs: McpHandler[RefRepoRefsIn, ToolOutcome, Nothing] =
        McpHandler.toolRaw[RefRepoRefsIn](
            "ref-repo-refs",
            "List recent branches and tags for a repository by id or URL"
        ) { in =>
            exec(ReferenceRepo.refs(in.idOrUrl))
        }

    val update: McpHandler[RefRepoUpdateIn, ToolOutcome, Nothing] =
        McpHandler.toolRaw[RefRepoUpdateIn](
            "ref-repo-update",
            "Fetch and re-pin a managed repository to a new ref"
        ) { in =>
            exec(ReferenceRepo.update(in.id, Maybe.fromOption(in.ref)))
        }

    val remove: McpHandler[RefRepoIdIn, ToolOutcome, Nothing] =
        McpHandler.toolRaw[RefRepoIdIn](
            "ref-repo-remove",
            "Remove a managed checkout and its manifest entry"
        ) { in =>
            execUnit(ReferenceRepo.remove(in.id), s"Repository ${in.id} removed.")
        }

    val purge: McpHandler[Unit, ToolOutcome, Nothing] =
        McpHandler.toolRaw[Unit](
            "ref-repo-purge",
            "Delete all managed checkouts and clear the manifest"
        ) { _ =>
            execUnit(ReferenceRepo.purge, "All reference repositories purged.")
        }

    val repair: McpHandler[Unit, ToolOutcome, Nothing] =
        McpHandler.toolRaw[Unit](
            "ref-repo-repair",
            "Rebuild the manifest from git checkouts discovered under .ref/"
        ) { _ =>
            exec(ReferenceRepo.repair)
        }

    val all: Seq[McpHandler[?, ?, ?]] =
        Seq(ensure, add, list, context, refs, update, remove, purge, repair)

end McpTools
