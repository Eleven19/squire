//| mvnDeps:
//| - org.virtuslab::scala-yaml:0.3.1
//| - com.lihaoyi::mainargs:0.7.6

/** Reference-repository manager for the `reference-repos` agent skill.
  *
  * This is a [[https://mill-build.org/ Mill script]] run via the skill's bundled
  * `mill` wrapper. It operates on a *target project* (not the skill folder itself):
  *
  *   - checkouts live under `.ref/<owner>/<repo>/`
  *   - state is tracked in `.ref/manifest.yaml`
  *   - `.ref/` is added to the target project's `.gitignore` when missing
  *
  * The target project root is resolved from `REF_REPOS_PROJECT_ROOT` when set
  * (the `ref-repos` launcher sets this from `git rev-parse --show-toplevel`),
  * otherwise from the current working directory.
  *
  * CLI parsing is handled by [[https://github.com/com-lihaoyi/mainargs mainargs]]
  * (`RefRepos` subcommands). Manifest serialization uses
  * [[https://github.com/VirtusLab/scala-yaml VirtusLab scala-yaml]] (`YamlCodec`).
  * The `refs` subcommand emits JSON via uPickle for agent consumption.
  *
  * Use `repair` to rebuild `.ref/manifest.yaml` from existing git checkouts when the
  * manifest is missing, corrupt, or out of sync with on-disk checkouts.
  *
  * SCM operations use `git` for clone/fetch/checkout; `gh api` is a fallback for
  * GitHub metadata when `git ls-remote --sort=-committerdate` fails.
  */
import mainargs.{arg, main, Flag, ParserForMethods}
import org.virtuslab.yaml.*
import upickle.default.*

import java.time.Instant

private val RefDirName = ".ref"
private val GitignoreMarker =
  "# Reference repositories for agent exploration (managed by reference-repos skill)"
private val GitignoreEntry = s"$GitignoreMarker\n.$RefDirName/\n"

private def manifestPath(root: os.Path): os.Path = root / RefDirName / "manifest.yaml"

private def repoCheckoutPath(root: os.Path, relative: String): os.Path =
  relative.split("/").filter(_.nonEmpty).foldLeft(root) { (base, segment) => base / segment }

private val ManifestVersion = 1

/** Classifies how a pin value should be checked out. */
enum RefKind:
  case Branch, Tag, Commit

/** A pinned ref recorded in the manifest after clone or update. */
case class RefPin(`type`: String, value: String, resolved_sha: Option[String] = None) derives YamlCodec

/** Non-exhaustive hint linking a checkout to published library coordinates. */
case class Artifact(
    group: Option[String] = None,
    artifact: Option[String] = None,
    name: Option[String] = None,
    note: Option[String] = None
) derives YamlCodec

/** Parsed repository identity before a manifest entry exists. */
case class RepoMeta(
    id: String,
    url: String,
    host: String,
    owner: String,
    name: String,
    path: String
)

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
    artifacts: List[Artifact] = Nil
) derives YamlCodec

/** Top-level manifest file: version plus all managed repos. */
case class Manifest(version: Int, repos: List[RepoEntry]) derives YamlCodec

/** A branch or tag candidate returned by `refs`. */
case class RecentRef(`type`: String, value: String, sha: Option[String])

/** JSON payload from the `refs` subcommand for agent-driven ref selection. */
case class RefsResponse(
    id: String,
    recent: List[RecentRef],
    custom: Boolean = true,
    prompt: Option[String] = None
)

given ReadWriter[RecentRef] = macroRW
given ReadWriter[RefsResponse] = macroRW

private def emptyManifest: Manifest = Manifest(ManifestVersion, List.empty[RepoEntry])

/** Empty YAML lists are often written as bare `artifacts:`; treat that as `[]` on read. */
private def sanitizeManifestYaml(raw: String): String =
  raw.replaceAll("(?m)^(\\s*)artifacts:\\s*$", "$1artifacts: []")

private def manifestToYaml(manifest: Manifest): String =
  sanitizeManifestYaml(manifest.asYaml)

/** Fields compared when reporting repair updates (timestamps excluded). */
private def repairComparable(
    e: RepoEntry
): (String, String, String, String, String, String, RefPin, List[Artifact]) =
  (e.id, e.url, e.host, e.owner, e.name, e.path, e.ref, e.artifacts)

private def abort(msg: String): Nothing =
  System.err.println(msg)
  sys.exit(1)

private def nowIso: String = Instant.now().toString

private def run(cmd: Seq[String], cwd: os.Path = os.pwd): (String, String, Boolean, Int) =
  if cmd.isEmpty then abort("empty command")
  val process = os.proc(cmd.map(s => s: os.Shellable)*).call(cwd = cwd, check = false, stderr = os.Pipe)
  (process.out.text(), process.err.text(), process.exitCode == 0, process.exitCode)

private def runRequired(label: String, cmd: Seq[String], cwd: os.Path = os.pwd): String =
  val (out, err, ok, code) = run(cmd, cwd)
  if !ok then abort(s"$label failed (exit $code): ${err.trim}")
  out

/** Root of the target project where `.ref/` is created.
  *
  * Prefers `REF_REPOS_PROJECT_ROOT` so the skill's Mill wrapper can run from its
  * own directory while still mutating the user's repository.
  */
private def projectRoot: os.Path =
  sys.env.get("REF_REPOS_PROJECT_ROOT") match
    case Some(root) if root.nonEmpty => os.Path(root)
    case _ =>
      val (out, _, ok, _) = run(Seq("git", "rev-parse", "--show-toplevel"))
      if !ok then
        abort(
          "Not inside a git repository. Run from a project root or set REF_REPOS_PROJECT_ROOT."
        )
      os.Path(out.trim)

/** Reads `.ref/manifest.yaml`, returning an empty manifest on missing or invalid files. */
private def readManifest(root: os.Path): Manifest =
  val path = manifestPath(root)
  if !os.exists(path) then emptyManifest
  else
    os.read(path).as[Manifest] match
      case Right(manifest) => manifest
      case Left(_) =>
        sanitizeManifestYaml(os.read(path)).as[Manifest] match
          case Right(manifest) => manifest
          case Left(err) =>
            System.err.println(s"Failed to read manifest: $err")
            emptyManifest

/** Writes the manifest using VirtusLab scala-yaml (`asYaml`). */
private def writeManifest(root: os.Path, manifest: Manifest): Unit =
  val path = manifestPath(root)
  os.write.over(path, manifestToYaml(manifest), createFolders = true)

/** Normalizes a URL, SSH URI, or `owner/repo` shorthand into clone metadata.
  *
  * GitLab-style nested groups use the full group path as `owner`
  * (e.g. `my-group/subgroup/repo` → owner `my-group/subgroup`, name `repo`).
  */
private def parseRepoUrl(raw: String): RepoMeta =
  var url = raw.trim
  if !url.startsWith("git@") && !url.contains("://") then url = s"https://$url"

  val (host, segments) =
    if url.startsWith("git@") then
      val body = url.stripPrefix("git@")
      val parts = body.split(":", 2)
      if parts.length != 2 then abort(s"Invalid git@ URL: $raw")
      val segs = parts(1).stripSuffix(".git").split("/").toList
      (parts(0), segs)
    else
      val uri = java.net.URI.create(url)
      if uri.getHost == null then abort(s"Invalid repository URL: $raw")
      val segs = uri.getPath.stripPrefix("/").stripSuffix(".git").split("/").filter(_.nonEmpty).toList
      (uri.getHost, segs)

  if segments.length < 2 then abort(s"Could not parse owner/repo from: $raw")

  val name = segments.last
  val owner = segments.dropRight(1).mkString("/")
  val id = s"$owner/$name"
  val cloneUrl =
    if url.startsWith("git@") then url
    else
      val scheme =
        if url.contains("://") then java.net.URI.create(url).getScheme
        else "https"
      s"$scheme://$host/$owner/$name.git"

  RepoMeta(id, cloneUrl, host, owner, name, s"$RefDirName/$owner/$name")

/** Like `parseRepoUrl`, but returns `None` instead of aborting on invalid input. */
private def parseRepoUrlOpt(raw: String): Option[RepoMeta] =
  try Some(parseRepoUrl(raw))
  catch case _: Exception => None

private def isGitCheckout(path: os.Path): Boolean =
  os.exists(path / ".git")

/** Finds git checkouts under `.ref/` and their path relative to `.ref` (e.g. `getkyo/kyo`). */
private def discoverCheckouts(refRoot: os.Path): List[(os.Path, String)] =
  def walk(current: os.Path, segments: List[String]): List[(os.Path, String)] =
    if isGitCheckout(current) then List((current, segments.mkString("/")))
    else if os.isDir(current) then
      os.list(current)
        .filter(os.isDir)
        .toList
        .flatMap(p => walk(p, segments :+ p.last))
    else Nil

  if os.exists(refRoot) then walk(refRoot, Nil) else Nil

/** Infers the current pin from a local checkout's HEAD. */
private def inferRefPin(checkout: os.Path): RefPin =
  val sha = resolvedSha(checkout)
  val (symref, _, symOk, _) = run(Seq("git", "symbolic-ref", "-q", "HEAD"), checkout)
  if symOk && symref.startsWith("refs/heads/") then
    RefPin("branch", symref.stripPrefix("refs/heads/").trim, Some(sha))
  else
    val (tag, _, tagOk, _) =
      run(Seq("git", "describe", "--tags", "--exact-match", "HEAD"), checkout)
    if tagOk && tag.trim.nonEmpty then RefPin("tag", tag.trim, Some(sha))
    else RefPin("commit", sha, Some(sha))

private def metaFromRelativePath(relPath: String): Option[RepoMeta] =
  val segments = relPath.split("/").filter(_.nonEmpty).toList
  if segments.length < 2 then None
  else
    val name = segments.last
    val owner = segments.dropRight(1).mkString("/")
    val id = s"$owner/$name"
    Some(
      RepoMeta(
        id = id,
        url = s"https://unknown/$owner/$name.git",
        host = "unknown",
        owner = owner,
        name = name,
        path = s"$RefDirName/$relPath"
      )
    )

/** Builds or refreshes a manifest entry from an on-disk checkout. */
private def entryFromCheckout(
    checkout: os.Path,
    relPath: String,
    existing: Option[RepoEntry]
): Option[RepoEntry] =
  val (remote, _, remoteOk, _) = run(Seq("git", "remote", "get-url", "origin"), checkout)
  val meta =
    if remoteOk && remote.trim.nonEmpty then parseRepoUrlOpt(remote.trim)
    else None
  val metaOrPath = meta.orElse(metaFromRelativePath(relPath))
  metaOrPath.map { m =>
    val path = s"$RefDirName/$relPath"
    val pin = inferRefPin(checkout)
    val ts = nowIso
    existing match
      case Some(entry) =>
        val lastUpdated = if entry.ref == pin then entry.last_updated else ts
        entry.copy(
          id = m.id,
          url = if m.host == "unknown" then entry.url else m.url,
          host = if m.host == "unknown" then entry.host else m.host,
          owner = m.owner,
          name = m.name,
          path = path,
          ref = pin,
          last_updated = lastUpdated
        )
      case None =>
        RepoEntry(
          id = m.id,
          url = m.url,
          host = m.host,
          owner = m.owner,
          name = m.name,
          path = path,
          ref = pin,
          cloned_at = ts,
          last_updated = ts,
          artifacts = Nil
        )
  }

/** Ensures `.ref/` exists and is gitignored in the target project. */
private def ensureLayout(root: os.Path): Unit =
  os.makeDir.all(root / RefDirName)
  val (_, _, ignored, _) = run(Seq("git", "check-ignore", "-q", RefDirName), cwd = root)
  if ignored then return

  val gitignore = root / ".gitignore"
  if os.exists(gitignore) && os.read(gitignore).contains(".ref/") then return

  val prefix = if os.exists(gitignore) && os.read(gitignore).nonEmpty then "\n" else ""
  os.write.append(gitignore, prefix + GitignoreEntry)
  println("Added .ref/ to .gitignore")

/** Determines whether `refValue` is a remote branch, tag, or raw commit SHA. */
private def refKind(url: String, refValue: String): RefKind =
  val (heads, _, okHeads, _) = run(Seq("git", "ls-remote", "--heads", url, refValue))
  if okHeads && heads.contains(s"refs/heads/$refValue") then RefKind.Branch
  else
    val (tags, _, okTags, _) = run(Seq("git", "ls-remote", "--tags", url, s"refs/tags/$refValue"))
    if okTags && tags.contains(s"refs/tags/$refValue") then RefKind.Tag
    else RefKind.Commit

private def resolvedSha(repoPath: os.Path): String =
  runRequired("resolve ref", Seq("git", "rev-parse", "HEAD"), repoPath).trim

/** Lists remote heads or tags, preferring committer-date sort with an unsorted fallback. */
private def lsRemoteLines(url: String, heads: Boolean): String =
  val mode = if heads then "--heads" else "--tags"
  val (sorted, _, okSorted, _) = run(Seq("git", "ls-remote", "--sort=-committerdate", mode, url))
  if okSorted && sorted.trim.nonEmpty then sorted
  else run(Seq("git", "ls-remote", mode, url))._1

/** Collects recent branches and tags from `git ls-remote` output. */
private def refsFromGit(url: String): List[RecentRef] =
  val refs = scala.collection.mutable.ListBuffer.empty[RecentRef]
  lsRemoteLines(url, heads = true).linesIterator.foreach { line =>
    line.split("\\s+").toList match
      case sha :: ref :: Nil if ref.startsWith("refs/heads/") =>
        refs += RecentRef("branch", ref.stripPrefix("refs/heads/"), Some(sha))
      case _ => ()
  }
  lsRemoteLines(url, heads = false).linesIterator.foreach { line =>
    line.split("\\s+").toList match
      case sha :: ref :: Nil =>
        val name = ref.stripPrefix("refs/tags/")
        if !name.endsWith("^{}") then refs += RecentRef("tag", name, Some(sha))
      case _ => ()
  }
  refs.toList

/** GitHub API fallback when sorted `git ls-remote` fails (some repos have broken refs). */
private def githubRefs(url: String): List[RecentRef] =
  val meta = parseRepoUrl(url)
  if meta.host != "github.com" then Nil
  else
    val branches = ghNames(s"repos/${meta.owner}/${meta.name}/branches").map(RecentRef("branch", _, None))
    val tags = ghNames(s"repos/${meta.owner}/${meta.name}/tags").map(RecentRef("tag", _, None))
    branches ++ tags

private def ghNames(path: String): List[String] =
  val (out, _, ok, _) = run(Seq("gh", "api", path, "--paginate", "-q", ".[].name"))
  if !ok then Nil else out.linesIterator.map(_.trim).filter(_.nonEmpty).toList

/** Returns up to `limit` recent refs for interactive or agent-driven selection. */
private def recentRefs(url: String, limit: Int = 4): List[RecentRef] =
  val refs = refsFromGit(url)
  val merged = if refs.nonEmpty then refs else githubRefs(url)
  merged.take(limit)

/** Picks a default branch when the user does not pass `--ref` to `add`. */
private def defaultRef(url: String): String =
  recentRefs(url, limit = 1).headOption.map(_.value).getOrElse {
    val (symref, _, ok, _) = run(Seq("git", "ls-remote", "--symref", url, "HEAD"))
    val branch = if ok then """ref:\s+refs/heads/(\S+)""".r.findFirstMatchIn(symref).map(_.group(1)) else None
    branch.getOrElse {
      val meta = parseRepoUrl(url)
      if meta.host == "github.com" then
        val (out, _, okGh, _) =
          run(Seq("gh", "api", s"repos/${meta.owner}/${meta.name}", "-q", ".default_branch"))
        val value = out.trim
        if okGh && value.nonEmpty then value else "main"
      else "main"
    }
  }

/** Clones into `.ref/<owner>/<repo>` and returns the resolved pin.
  *
  * Commits use a full clone plus `git checkout`; branches and tags use
  * `git clone -b … --single-branch`.
  */
private def cloneRepo(root: os.Path, meta: RepoMeta, refValue: String): RefPin =
  val dest = repoCheckoutPath(root, meta.path)
  val parent = dest / os.up
  os.makeDir.all(parent)
  if os.exists(dest) then abort(s"${meta.path} already exists. Use update or remove first.")

  val kind = refKind(meta.url, refValue)
  kind match
    case RefKind.Commit =>
      runRequired("clone", Seq("git", "clone", meta.url, dest.last), parent)
      runRequired("checkout", Seq("git", "checkout", refValue), dest)
    case _ =>
      runRequired(
        "clone",
        Seq("git", "clone", "--origin", "origin", "-b", refValue, "--single-branch", meta.url, dest.last),
        parent
      )

  val kindName = kind match
    case RefKind.Branch => "branch"
    case RefKind.Tag    => "tag"
    case RefKind.Commit => "commit"
  RefPin(kindName, refValue, Some(resolvedSha(dest)))

/** Fetches and re-checks out an existing managed clone. */
private def updateRepo(root: os.Path, entry: RepoEntry, refValue: String): RefPin =
  val dest = repoCheckoutPath(root, entry.path)
  if !os.exists(dest) then abort(s"Missing clone at ${entry.path}. Re-add the repository.")

  runRequired("fetch", Seq("git", "fetch", "--tags", "origin"), dest)
  refKind(entry.url, refValue) match
    case RefKind.Branch =>
      runRequired("checkout branch", Seq("git", "checkout", refValue), dest)
      runRequired("pull", Seq("git", "pull", "--ff-only", "origin", refValue), dest)
    case RefKind.Tag =>
      runRequired("checkout tag", Seq("git", "checkout", s"tags/$refValue"), dest)
    case RefKind.Commit =>
      runRequired("checkout commit", Seq("git", "checkout", refValue), dest)

  val kindName =
    refKind(entry.url, refValue) match
      case RefKind.Branch => "branch"
      case RefKind.Tag    => "tag"
      case RefKind.Commit => "commit"
  RefPin(kindName, refValue, Some(resolvedSha(dest)))

private def formatArtifact(a: Artifact): String =
  val base = (a.group, a.artifact) match
    case (Some(g), Some(art)) => s"$g:$art"
    case _                    => a.name.getOrElse("")
  val note = a.note.map(n => s" ($n)").getOrElse("")
  s"$base$note"

/** Parses `--artifacts "group:artifact,name,…"` into manifest hints. */
private def parseArtifacts(raw: Option[String]): List[Artifact] =
  raw.toList
    .flatMap(_.split(",").toList)
    .map(_.trim)
    .filter(_.nonEmpty)
    .map {
      case item if item.contains(":") =>
        val parts = item.split(":", 2)
        Artifact(group = Some(parts(0)), artifact = Some(parts(1)))
      case item => Artifact(name = Some(item))
    }

private def findRepo(manifest: Manifest, id: String): Option[RepoEntry] =
  manifest.repos.find(_.id == id)

private def cmdEnsure(root: os.Path): Unit =
  ensureLayout(root)
  val manifest = readManifest(root)
  if !os.exists(manifestPath(root)) then writeManifest(root, manifest)
  println(s"Reference layout ready at ${root / RefDirName}")

private def cmdList(root: os.Path): Unit =
  val manifest = readManifest(root)
  if manifest.repos.isEmpty then println("No reference repositories managed yet.")
  else
    manifest.repos.foreach { repo =>
      val artifacts =
        if repo.artifacts.isEmpty then "none"
        else repo.artifacts.map(formatArtifact).mkString(", ")
      val sha = repo.ref.resolved_sha.map(_.take(7)).map(s => s" @ $s").getOrElse("")
      println(s"${repo.id}: ${repo.path} (${repo.ref.value}$sha)")
      println(s"  url: ${repo.url}")
      println(s"  artifacts (hints): $artifacts")
    }

private def cmdAdd(root: os.Path, url: String, ref: Option[String], artifacts: Option[String]): Unit =
  ensureLayout(root)
  val meta = parseRepoUrl(url)
  val manifest = readManifest(root)
  if findRepo(manifest, meta.id).isDefined then abort(s"${meta.id} is already managed. Use update instead.")

  val refValue = ref.getOrElse(defaultRef(meta.url))
  val pin = cloneRepo(root, meta, refValue)
  val ts = nowIso
  val entry = RepoEntry(
    id = meta.id,
    url = meta.url,
    host = meta.host,
    owner = meta.owner,
    name = meta.name,
    path = meta.path,
    ref = pin,
    cloned_at = ts,
    last_updated = ts,
    artifacts = parseArtifacts(artifacts)
  )
  writeManifest(root, manifest.copy(repos = manifest.repos :+ entry))
  println(s"Cloned ${meta.id} to ${meta.path} at ${pin.`type`} $refValue")

/** Emits JSON listing the four most recent refs plus a custom-value prompt. */
private def cmdRefs(root: os.Path, idOrUrl: String): Unit =
  val manifest = readManifest(root)
  val url = findRepo(manifest, idOrUrl)
    .map(_.url)
    .getOrElse(parseRepoUrl(idOrUrl).url)
  val id = findRepo(manifest, idOrUrl).map(_.id).getOrElse(parseRepoUrl(idOrUrl).id)
  val recent = recentRefs(url, limit = 4)
  val response =
    if recent.isEmpty then RefsResponse(id, Nil)
    else
      RefsResponse(
        id,
        recent,
        prompt = Some(s"Pick 1-${recent.length}, or provide your own branch/tag/commit.")
      )
  println(write(response))

/** Re-pins a managed repo; without `--ref`, prints `refs` output and exits with guidance. */
private def cmdUpdate(root: os.Path, id: String, ref: Option[String]): Unit =
  val manifest = readManifest(root)
  val entry = findRepo(manifest, id).getOrElse(abort(s"Unknown repository: $id"))
  ref match
    case None =>
      cmdRefs(root, id)
      abort("No --ref provided. Ask the user to choose from recent refs or supply a custom value.")
    case Some(refValue) =>
      val pin = updateRepo(root, entry, refValue)
      val updated = entry.copy(ref = pin, last_updated = nowIso)
      val repos = manifest.repos.map(r => if r.id == id then updated else r)
      writeManifest(root, manifest.copy(repos = repos))
      val sha = pin.resolved_sha.map(_.take(7)).getOrElse("unknown")
      println(s"Updated $id to ${pin.`type`} $refValue ($sha)")

private def cmdRemove(root: os.Path, id: String): Unit =
  val manifest = readManifest(root)
  val entry = findRepo(manifest, id).getOrElse(abort(s"Unknown repository: $id"))
  val dest = repoCheckoutPath(root, entry.path)
  if os.exists(dest) then os.remove.all(dest)
  writeManifest(root, manifest.copy(repos = manifest.repos.filterNot(_.id == id)))
  println(s"Removed $id")

private def cmdPurge(root: os.Path, force: Boolean): Unit =
  val manifest = readManifest(root)
  if manifest.repos.isEmpty then abort("No managed repositories to purge.")
  if !force then abort("Refusing to purge without --force.")
  manifest.repos.foreach { entry =>
    val dest = repoCheckoutPath(root, entry.path)
    if os.exists(dest) then os.remove.all(dest)
  }
  writeManifest(root, emptyManifest)
  println("Purged all reference repositories.")

/** Summarizes managed repos and artifact hints for agent context gathering. */
private def cmdContext(root: os.Path): Unit =
  val manifest = readManifest(root)
  if manifest.repos.isEmpty then
    println(s"No reference repositories are available in $RefDirName/.")
    println("Use the reference-repos skill to clone upstream sources for exploration.")
  else
    println("Reference repositories available for source exploration:")
    manifest.repos.foreach { repo =>
      val sha = repo.ref.resolved_sha.map(_.take(7)).getOrElse("unknown")
      println(s"- ${repo.id} → ${repo.path} (${repo.ref.value} @ $sha)")
      if repo.artifacts.nonEmpty then
        println(s"  published hints: ${repo.artifacts.map(formatArtifact).mkString("; ")}")
    }
    println()
    println("Artifact hints are non-exhaustive. Search the checkout when locating symbols or modules.")

/** Rebuilds `.ref/manifest.yaml` from git checkouts discovered under `.ref/`.
  *
  * Adds entries for checkouts missing from the manifest, refreshes pins and URLs
  * for existing entries, and drops manifest rows whose checkouts are gone unless
  * `--keep-orphans` is set. Preserves artifact hints and `cloned_at` when the
  * repo id matches.
  */
private def cmdRepair(root: os.Path, dryRun: Boolean, keepOrphans: Boolean): Unit =
  ensureLayout(root)
  val refRoot = root / RefDirName
  val prior = readManifest(root)
  val priorByPath = prior.repos.map(r => r.path -> r).toMap
  val priorById = prior.repos.map(r => r.id -> r).toMap

  val checkouts = discoverCheckouts(refRoot)
  if checkouts.isEmpty && prior.repos.isEmpty then
    if !dryRun then writeManifest(root, emptyManifest)
    println(s"No git checkouts under $RefDirName/; wrote empty manifest.")
    return

  val rebuilt =
    checkouts.flatMap { case (checkout, relPath) =>
      val path = s"$RefDirName/$relPath"
      val existing = priorByPath.get(path).orElse {
        entryFromCheckout(checkout, relPath, None).flatMap(fresh => priorById.get(fresh.id))
      }
      entryFromCheckout(checkout, relPath, existing)
    }

  val rebuiltIds = rebuilt.map(_.id).toSet
  val orphans = prior.repos.filterNot(r => rebuiltIds.contains(r.id))
  val merged =
    if keepOrphans then (rebuilt ++ orphans.filterNot(o => rebuiltIds.contains(o.id))).distinctBy(_.id)
    else rebuilt

  val added = merged.filter(m => !priorById.contains(m.id))
  val updated = merged.filter(m => priorById.get(m.id).exists(p => repairComparable(p) != repairComparable(m)))
  val removed = if keepOrphans then Nil else orphans

  if dryRun then
    println("Dry run — no files written.")
  else
    writeManifest(root, Manifest(ManifestVersion, merged.sortBy(_.id)))

  println(
    s"Repair summary: ${merged.length} repo(s) in manifest " +
      s"(${added.length} added, ${updated.length} updated, ${removed.length} removed)."
  )
  added.foreach(e => println(s"  + ${e.id} → ${e.path} (${e.ref.`type`} ${e.ref.value})"))
  updated.foreach(e => println(s"  ~ ${e.id} → ${e.path} (${e.ref.`type`} ${e.ref.value})"))
  removed.foreach(e => println(s"  - ${e.id} (checkout missing)"))
  checkouts.filter { case (_, rel) => !merged.exists(_.path == s"$RefDirName/$rel") }.foreach {
    case (_, rel) => println(s"  ! skipped $RefDirName/$rel (not a valid git checkout with origin)")
  }

/** CLI entry points parsed by mainargs; each `@main` method maps to a subcommand. */
object RefRepos:
  @main(doc = "Create .ref/, manifest, and gitignore entry")
  def ensure(): Unit =
    cmdEnsure(projectRoot)

  @main(doc = "Show managed repos, pins, and artifact hints")
  def list(): Unit =
    cmdList(projectRoot)

  @main(doc = "Agent-friendly summary of available reference repos")
  def context(): Unit =
    cmdContext(projectRoot)

  @main(doc = "Clone and register a reference repository")
  def add(
      @arg(positional = true, doc = "Repository URL or owner/repo")
      repoUrl: String,
      @arg(doc = "Branch, tag, or commit to pin")
      ref: Option[String] = None,
      @arg(doc = "Comma-separated artifact hints (group:artifact or name)")
      artifacts: Option[String] = None
  ): Unit =
    cmdAdd(projectRoot, repoUrl, ref, artifacts)

  @main(doc = "List recent branches/tags for ref selection (JSON)")
  def refs(
      @arg(positional = true, doc = "Managed id or repository URL")
      target: String
  ): Unit =
    cmdRefs(projectRoot, target)

  @main(doc = "Fetch and re-pin a managed reference repository")
  def update(
      @arg(positional = true, doc = "Managed owner/repo id")
      id: String,
      @arg(doc = "Branch, tag, or commit to pin")
      ref: Option[String] = None
  ): Unit =
    cmdUpdate(projectRoot, id, ref)

  @main(doc = "Delete one managed checkout and manifest entry")
  def remove(
      @arg(positional = true, doc = "Managed owner/repo id")
      id: String
  ): Unit =
    cmdRemove(projectRoot, id)

  @main(doc = "Delete all managed checkouts and clear the manifest")
  def purge(
      @arg(doc = "Confirm destructive operation")
      force: Flag
  ): Unit =
    cmdPurge(projectRoot, force.value)

  @main(doc = "Rebuild manifest.yaml from git checkouts under .ref/")
  def repair(
      @arg(doc = "Show planned changes without writing manifest.yaml")
      dryRun: Flag = Flag(false),
      @arg(doc = "Keep manifest entries whose checkouts are missing on disk")
      keepOrphans: Flag = Flag(false)
  ): Unit =
    cmdRepair(projectRoot, dryRun.value, keepOrphans.value)

/** Mill script entry point; forwards argv to mainargs subcommand dispatch. */
def main(args: String*): Unit =
  ParserForMethods(RefRepos).runOrExit(args.toArray)
