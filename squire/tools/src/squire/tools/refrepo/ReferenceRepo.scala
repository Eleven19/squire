package squire.tools.refrepo

import kyo.*
import kyo.kernel.ArrowEffect

/** ArrowEffect representing operations on managed reference repositories under `.ref/`.
  *
  * Suspend an operation with the convenience helpers (`ensure`, `add`, `list`, …) and interpret the effect with
  * [[ReferenceRepoHandler.run]].
  */
sealed trait ReferenceRepo extends ArrowEffect[ReferenceRepo.Op, Id]

object ReferenceRepo:

    // Package-private so ReferenceRepoHandler can summon it without re-deriving.
    private[refrepo] given Tag[ReferenceRepo] = Tag.derive[ReferenceRepo]

    /** GADT of all suspended operations; the type parameter is the operation's result type. */
    sealed trait Op[A]

    /** Create `.ref/`, an empty manifest, and a `.gitignore` entry if absent. */
    case object Ensure extends Op[Unit]

    /** Clone and register a reference repository. */
    case class Add(url: String, ref: Maybe[String], artifacts: Chunk[String]) extends Op[RepoEntry]

    /** List all managed repositories from the manifest. */
    case object ListRepos extends Op[Chunk[RepoEntry]]

    /** Agent-friendly summary of available reference repos. */
    case object Context extends Op[ContextReport]

    /** List recent branches/tags for the given id or URL. */
    case class Refs(idOrUrl: String) extends Op[Chunk[RefOption]]

    /** Fetch and re-pin a managed repository. */
    case class Update(id: String, ref: Maybe[String]) extends Op[RepoEntry]

    /** Remove a managed checkout and its manifest entry. */
    case class Remove(id: String) extends Op[Unit]

    /** Delete all managed checkouts and clear the manifest. */
    case object Purge extends Op[Unit]

    /** Rebuild the manifest from git checkouts discovered under `.ref/`. */
    case object Repair extends Op[RepairReport]

    private inline def suspend[A](op: Op[A])(using Frame): A < ReferenceRepo =
        ArrowEffect.suspend[A](Tag[ReferenceRepo], op)

    def ensure(using Frame): Unit < ReferenceRepo =
        suspend(Ensure)

    def add(
        url: String,
        ref: Maybe[String] = Maybe.Absent,
        artifacts: Chunk[String] = Chunk.empty
    )(using Frame): RepoEntry < ReferenceRepo =
        suspend(Add(url, ref, artifacts))

    def list(using Frame): Chunk[RepoEntry] < ReferenceRepo =
        suspend(ListRepos)

    def context(using Frame): ContextReport < ReferenceRepo =
        suspend(Context)

    def refs(idOrUrl: String)(using Frame): Chunk[RefOption] < ReferenceRepo =
        suspend(Refs(idOrUrl))

    def update(id: String, ref: Maybe[String] = Maybe.Absent)(using Frame): RepoEntry < ReferenceRepo =
        suspend(Update(id, ref))

    def remove(id: String)(using Frame): Unit < ReferenceRepo =
        suspend(Remove(id))

    def purge(using Frame): Unit < ReferenceRepo =
        suspend(Purge)

    def repair(using Frame): RepairReport < ReferenceRepo =
        suspend(Repair)

end ReferenceRepo
