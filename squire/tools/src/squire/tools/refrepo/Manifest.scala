package squire.tools.refrepo

import kyo.*
import org.virtuslab.yaml.*

import java.nio.file.{Files, Path as NioPath, StandardOpenOption}
import java.time.Instant

/** Top-level `.ref/manifest.yaml` document; version-tagged for future evolution. */
case class ManifestFile(version: Int, repos: List[RepoEntry]) derives YamlCodec

object ManifestFile:
    val Version = 1
    val RefDir  = ".ref"
    val empty   = ManifestFile(Version, Nil)

    private val GitignoreMarker =
        "# Reference repositories for agent exploration (managed by reference-repos skill)"
    private val GitignoreEntry = s"$GitignoreMarker\n.ref/\n"

    /** Absolute path of `.ref/manifest.yaml` under the given project root. */
    def manifestPath(root: NioPath): NioPath =
        root.resolve(RefDir).resolve("manifest.yaml")

    /** Absolute path of a checkout given its relative path (e.g. `.ref/owner/name`). */
    def checkoutPath(root: NioPath, relPath: String): NioPath =
        relPath.split("/").filter(_.nonEmpty).foldLeft(root)(_.resolve(_))

    def nowIso: String = Instant.now().toString

    /** Normalises empty YAML list fields (`repos`, `artifacts`) so they round-trip cleanly.
      *
      * scala-yaml 0.3.x writes `List.empty` as a bare key with no value, which YAML
      * interprets as null.  A bare key must be converted to `[]` so the decoder can
      * construct a `List` from it.
      *
      * A custom [[org.virtuslab.yaml.YamlEncoder]] for [[List]] was investigated as a
      * replacement: scala-yaml 0.3.2's encoder produces a [[org.virtuslab.yaml.Node.SequenceNode]]
      * with no children for an empty list, but the presenter has no flow-sequence output
      * mode — it always emits a bare key followed by nothing.  The regex post-process is
      * therefore the only viable fix with scala-yaml 0.3.2.
      *
      * Indentation assumption pinned to scala-yaml 0.3.2 block-sequence style (2-space
      * child indent).  If the library changes this, the regex lookahead `\\1[ \\t]+-`
      * must be revisited.
      *
      * However a non-empty sequence is ALSO written as a bare key followed by block-sequence
      * items on subsequent lines:
      *
      *   repos:          ← bare key, but followed by "- id: …" on the next line
      *   - id: foo
      *
      * We must only replace a bare key when it is NOT followed by a same-level sequence item.
      * The regex captures the field's leading indent in group 1 and uses `\1` inside a
      * negative lookahead so we skip any line that opens a block sequence at the same depth:
      *
      *   `(?m)^(\\s*)(repos|artifacts):\\s*(?=\\n(?!\\1-)|\\z)`
      *
      * Also handles the explicit YAML null forms `null` and `~`.
      */
    private def sanitize(raw: String): String =
        // Replace explicit `null` or `~` values (e.g. `repos: null`)
        val s1 = raw.replaceAll("(?m)^(\\s*)(repos|artifacts): (?:null|~)\\s*$", "$1$2: []")
        // Replace bare keys (no value) when NOT followed by a CHILD sequence item.
        //
        // A child item is any line indented STRICTLY MORE than the field itself (group 1),
        // i.e. it matches `\1[ \t]+-`.  A sibling or unrelated dash at a shallower or equal
        // level is NOT a child and should trigger the replacement.
        //
        // scala-yaml writes block sequences with 2-space indent, so `repos:` at col 0
        // produces `  - id:` (2 spaces) — which `(?!\\1[ \\t]+-)` correctly identifies as a
        // child and skips.  A different repo's `- id:` at col 0 after `  artifacts:` (col 2)
        // has shallower indent than the field, so `(?!  [ \\t]+-)` SUCCEEDS and the empty
        // `artifacts:` is correctly replaced.
        s1.replaceAll("(?m)^(\\s*)(repos|artifacts):\\s*(?=\\n(?!\\1[ \\t]+-)|\\z)", "$1$2: []")

    /** Read `.ref/manifest.yaml`; return [[empty]] if the file is absent. Fails with
      * [[RefRepoError.ManifestParse]] if the file exists but cannot be decoded.
      */
    def read(root: NioPath)(using Frame): ManifestFile < Abort[RefRepoError] =
        val path = manifestPath(root)
        if !Files.exists(path) then empty
        else
            val raw = Files.readString(path)
            raw.as[ManifestFile] match
                case Right(m) => m
                case Left(_)  =>
                    sanitize(raw).as[ManifestFile] match
                        case Right(m)  => m
                        case Left(err) => Abort.fail(RefRepoError.ManifestParse(err.toString))

    /** Atomically overwrite `.ref/manifest.yaml`, creating parent dirs as needed. */
    def write(root: NioPath, m: ManifestFile): Unit =
        val path = manifestPath(root)
        Files.createDirectories(path.getParent)
        Files.writeString(path, sanitize(m.asYaml))

    /** Create `.ref/` and append `.ref/` to `.gitignore` if not already present. */
    def ensureLayout(root: NioPath): Unit =
        Files.createDirectories(root.resolve(RefDir))
        val gi      = root.resolve(".gitignore")
        val current = if Files.exists(gi) then Files.readString(gi) else ""
        if !current.contains(".ref/") then
            val prefix = if current.nonEmpty then "\n" else ""
            Files.writeString(gi, prefix + GitignoreEntry, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

end ManifestFile
