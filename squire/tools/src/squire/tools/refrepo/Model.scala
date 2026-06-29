package squire.tools.refrepo

import kyo.*
import org.virtuslab.yaml.*

/** A pinned ref (branch, tag, or commit SHA) as stored in the manifest. */
case class RefPin(`type`: String, value: String, resolved_sha: Option[String] = None)
    derives Schema, CanEqual, YamlCodec

/** Non-exhaustive hint linking a checkout to published library coordinates. */
case class ArtifactHint(
    group: Option[String] = None,
    artifact: Option[String] = None,
    name: Option[String] = None,
    note: Option[String] = None
) derives Schema, CanEqual, YamlCodec

/** One managed reference checkout, as stored in `.ref/manifest.yaml`. */
case class RepoEntry(
    id: String,
    url: String,
    host: String,
    owner: String,
    name: String,
    path: String,
    ref: RefPin,
    cloned_at: String,
    last_updated: String,
    artifacts: List[ArtifactHint] = Nil
) derives Schema, CanEqual, YamlCodec

/** A branch or tag candidate returned by the `refs` op. */
case class RefOption(`type`: String, value: String, sha: Option[String])
    derives Schema, CanEqual

/** Agent-friendly summary from the `context` op. */
case class ContextReport(repos: Chunk[RepoEntry], summary: String)
    derives Schema, CanEqual

/** Summary report from the `repair` op. */
case class RepairReport(added: Int, updated: Int, removed: Int, total: Int)
    derives Schema, CanEqual
