package millbuild

import org.virtuslab.yaml.*

/** One skill's shared, harness-neutral model. The shipped body is NOT precomputed —
  * it is resolved per harness by `resolvedBody`, which layers `src/` + `src-<harness>/`. */
final case class SkillModel(
    id: String,
    displayName: String,
    author: String,
    description: String,
    version: String,
    license: String,
    category: String,
    tags: Seq[String],
    harnesses: Seq[String],
    skillDir: os.Path
)

object Codegen {

  /** Parse skills/<id>/skill.yaml into the shared model. Does NOT read the body. */
  def model(skillDir: os.Path): SkillModel = {
    val y = YamlDoc.parse(os.read(skillDir / "skill.yaml"))
    SkillModel(
      id          = y.str("id"),
      displayName = y.str("displayName"),
      author      = y.str("author"),
      description = y.str("description"),
      version     = y.str("version"),
      license     = y.str("license"),
      category    = y.str("category"),
      tags        = y.list("tags"),
      harnesses   = y.list("harnesses"),
      skillDir    = skillDir
    )
  }

  /** Shipped body for one harness: union of `src/` and optional `src-<harness>/`,
    * keyed by relative path; the harness overlay WINS on collision. Returns
    * (relative path under the skill, ABSOLUTE source path) so callers can COPY
    * (preserving exec bits). harness="" → just `src/`. */
  def resolvedBody(skillDir: os.Path, harness: String): Seq[(os.SubPath, os.Path)] = {
    def filesUnder(dir: os.Path): Map[os.SubPath, os.Path] =
      if (!os.exists(dir)) Map.empty
      else os.walk(dir).filter(os.isFile).map(p => p.subRelativeTo(dir) -> p).toMap
    val shared  = filesUnder(skillDir / "src")
    val overlay = if (harness.isEmpty) Map.empty else filesUnder(skillDir / s"src-$harness")
    (shared ++ overlay).toSeq.sortBy(_._1.toString)
  }
}

/** Thin wrapper over a parsed top-level YAML mapping, exposing `str` and `list` accessors.
  * Used only by `Codegen.model`; not a general-purpose YAML API.
  *
  * Handles the subset of YAML that `skill.yaml` uses: top-level `key: scalar`,
  * folded `key: >-` blocks (scala-yaml resolves these to a scalar already),
  * and inline flow lists `key: [a, b, c]`.
  *
  * NOTE: Uses typed patterns + accessor methods rather than case-class unapply
  * to avoid TASTy version skew between scala-yaml and the meta-build's Scala. */
private[millbuild] final class YamlDoc private (mappings: Map[Node, Node]) {

  private def lookup(key: String): Node =
    mappings
      .collectFirst { case (k: Node.ScalarNode, v) if k.value == key => v }
      .getOrElse(throw new IllegalArgumentException(s"YAML key not found: $key"))

  def str(key: String): String = lookup(key) match {
    case s: Node.ScalarNode => s.value
    case other =>
      throw new IllegalArgumentException(s"Expected scalar for key '$key', got: $other")
  }

  def list(key: String): Seq[String] = lookup(key) match {
    case seq: Node.SequenceNode =>
      seq.nodes.collect { case s: Node.ScalarNode => s.value }.toSeq
    case other =>
      throw new IllegalArgumentException(s"Expected sequence for key '$key', got: $other")
  }
}

private[millbuild] object YamlDoc {
  // asNode, value, nodes, mappings are all 0-arity defs in scala-yaml (no parens)
  def parse(text: String): YamlDoc = text.asNode match {
    case Right(m: Node.MappingNode) => new YamlDoc(m.mappings)
    case Right(other) =>
      throw new IllegalArgumentException(s"Expected top-level YAML mapping, got: $other")
    case Left(err) =>
      throw new IllegalArgumentException(s"YAML parse error: $err")
  }
}
