package squire.tools.refrepo

import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Loop

import java.nio.file.{Files, Path as NioPath}

/** Interprets [[ReferenceRepo]] operations against a project root on disk.
  *
  * Git operations use [[kyo.Command]]; manifest I/O uses Java NIO directly. File I/O errors propagate as panics; git
  * failures are surfaced as [[RefRepoError.GitFailed]].
  */
object ReferenceRepoHandler:

    // Private case class; keeps URL-parsing logic out of the domain model.
    private case class RepoMeta(
        id: String,
        url: String,
        host: String,
        owner: String,
        name: String,
        path: String
    )

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /** Runs a [[ReferenceRepo]] program against the given project root, producing a computation that may [[Abort]] with
      * [[RefRepoError]] or use [[Async]].
      */
    def run[A, S](root: NioPath)(v: A < (ReferenceRepo & S))(using Frame): A < (S & Async & Abort[RefRepoError]) =
        ArrowEffect.handleLoop(summon[Tag[ReferenceRepo]], v) {
            [C] =>
                (op, cont) =>
                    val result: C < (Async & Abort[RefRepoError]) = op match
                        case ReferenceRepo.Ensure    => opEnsure(root)
                        case a: ReferenceRepo.Add    => opAdd(root, a.url, a.ref, a.artifacts)
                        case ReferenceRepo.ListRepos => opList(root)
                        case ReferenceRepo.Context   => opContext(root)
                        case r: ReferenceRepo.Refs   => opRefs(root, r.idOrUrl)
                        case u: ReferenceRepo.Update => opUpdate(root, u.id, u.ref)
                        case r: ReferenceRepo.Remove => opRemove(root, r.id)
                        case ReferenceRepo.Purge     => opPurge(root)
                        case ReferenceRepo.Repair    => opRepair(root)
                    result.map(c => Loop.continue(cont(c)))
        }

    // -----------------------------------------------------------------------
    // Git helper
    // -----------------------------------------------------------------------

    /** Run `git <args>` in `cwd`; fail with [[RefRepoError.GitFailed]] on non-zero exit. */
    private def git(cwd: NioPath, args: String*)(using Frame): String < (Async & Abort[RefRepoError]) =
        val allArgs = "git" +: args
        Abort
            .run[CommandException] {
                Command(allArgs*).cwd(kyo.Path.of(cwd.toAbsolutePath)).redirectErrorStream(true).textWithExitCode
            }
            .flatMap {
                case Result.Success(v) =>
                    v match
                        case (out: String, code: ExitCode) =>
                            if code.isSuccess then out
                            else Abort.fail(RefRepoError.GitFailed(allArgs.toList, code.toInt, out.trim))
                case Result.Failure(e) =>
                    Abort.fail(RefRepoError.GitFailed(allArgs.toList, -1, e.toString))
                case Result.Panic(t) =>
                    throw t
            }

    // -----------------------------------------------------------------------
    // URL parsing (pure)
    // -----------------------------------------------------------------------

    private def parseRepoUrl(raw: String): Either[String, RepoMeta] =
        try
            var url = raw.trim
            if url.startsWith("file://") then
                // Local file URL: use as-is; derive owner/name from last two path segments.
                val path = url.stripPrefix("file://").stripSuffix(".git").stripSuffix("/")
                val segs = path.split("/").filter(_.nonEmpty).toList
                if segs.length < 1 then Left(s"Cannot parse repo name from file:// URL: $raw")
                else
                    val name  = segs.last
                    val owner = if segs.length >= 2 then segs(segs.length - 2) else "local"
                    Right(RepoMeta(s"$owner/$name", url, "localhost", owner, name, s".ref/$owner/$name"))
            else
                if !url.startsWith("git@") && !url.contains("://") then url = s"https://github.com/$url"
                if url.startsWith("git@") then
                    val body  = url.stripPrefix("git@")
                    val colon = body.indexOf(':')
                    if colon < 0 then Left(s"Invalid git@ URL: $raw")
                    else
                        val host = body.substring(0, colon)
                        val path = body.substring(colon + 1).stripSuffix(".git")
                        val segs = path.split("/").filter(_.nonEmpty).toList
                        if segs.length < 2 then Left(s"Cannot parse owner/repo from: $raw")
                        else
                            val name  = segs.last
                            val owner = segs.dropRight(1).mkString("/")
                            Right(RepoMeta(s"$owner/$name", url, host, owner, name, s".ref/$owner/$name"))
                else
                    val uri  = java.net.URI.create(url)
                    val host = Option(uri.getHost).getOrElse("")
                    if host.isEmpty then Left(s"Invalid URL (no host): $raw")
                    else
                        val path = uri.getPath.stripPrefix("/").stripSuffix(".git")
                        val segs = path.split("/").filter(_.nonEmpty).toList
                        if segs.length < 2 then Left(s"Cannot parse owner/repo from: $raw")
                        else
                            val name     = segs.last
                            val owner    = segs.dropRight(1).mkString("/")
                            val cloneUrl = s"${uri.getScheme}://$host/$owner/$name.git"
                            Right(RepoMeta(s"$owner/$name", cloneUrl, host, owner, name, s".ref/$owner/$name"))
        catch case e: Exception => Left(s"Cannot parse URL '$raw': ${e.getMessage}")

    /** Derive [[RepoMeta]] from a relative path like `owner/name` under `.ref/`. */
    private def pathToMeta(relPath: String): Option[RepoMeta] =
        val segs = relPath.split("/").filter(_.nonEmpty).toList
        if segs.length < 2 then None
        else
            val name  = segs.last
            val owner = segs.dropRight(1).mkString("/")
            Some(
                RepoMeta(s"$owner/$name", s"https://unknown/$owner/$name.git", "unknown", owner, name, s".ref/$relPath")
            )

    /** URL from manifest entry or fresh parse; falls back to raw string on error. */
    private def resolveUrl(idOrUrl: String, manifest: ManifestFile): String =
        manifest.repos
            .find(e => e.id == idOrUrl || e.url == idOrUrl)
            .map(_.url)
            .getOrElse {
                parseRepoUrl(idOrUrl) match
                    case Right(m) => m.url
                    case Left(_)  => idOrUrl
            }

    /** `"group:artifact"` or bare name → [[ArtifactHint]]. */
    private def parseArtifacts(raw: Chunk[String]): List[ArtifactHint] =
        raw.toList.map(_.trim).filter(_.nonEmpty).map {
            case s if s.contains(":") =>
                val parts = s.split(":", 2)
                ArtifactHint(group = Some(parts(0)), artifact = Some(parts(1)))
            case s => ArtifactHint(name = Some(s))
        }

    // -----------------------------------------------------------------------
    // Git helpers for clone / update
    // -----------------------------------------------------------------------

    private def determineDefaultRef(root: NioPath, url: String)(using Frame): String < (Async & Abort[RefRepoError]) =
        Abort
            .run[RefRepoError] {
                git(root, "ls-remote", "--symref", url, "HEAD")
            }
            .map {
                case Result.Success(out) =>
                    out.linesIterator
                        .find(_.startsWith("ref: refs/heads/"))
                        .map(_.stripPrefix("ref: refs/heads/").split("\\s").head)
                        .getOrElse("main")
                case _ => "main"
            }

    /** Classify `refValue` against the remote URL as "branch", "tag", or "commit".
      *
      * Mirrors the port source's `refKind`: branch check wins, then tag, else commit. Both ls-remote calls are wrapped
      * in [[Abort.run]] so network failures fall through to the "commit" default rather than propagating an error.
      */
    private def classifyRef(root: NioPath, url: String, refValue: String)(using
        Frame
    ): String < (Async & Abort[RefRepoError]) =
        Abort
            .run[RefRepoError] {
                git(root, "ls-remote", "--heads", url, refValue)
            }
            .flatMap { headsResult =>
                val isBranch = headsResult match
                    case Result.Success(out) => out.linesIterator.exists(_.contains(s"refs/heads/$refValue"))
                    case _                   => false
                if isBranch then "branch"
                else
                    Abort
                        .run[RefRepoError] {
                            git(root, "ls-remote", "--tags", url, s"refs/tags/$refValue")
                        }
                        .map { tagsResult =>
                            val isTag = tagsResult match
                                case Result.Success(out) => out.linesIterator.exists(_.contains(s"refs/tags/$refValue"))
                                case _                   => false
                            if isTag then "tag" else "commit"
                        }
            }

    private def cloneRepo(root: NioPath, meta: RepoMeta, refValue: String)(using
        Frame
    ): RefPin < (Async & Abort[RefRepoError]) =
        val dest   = ManifestFile.checkoutPath(root, meta.path)
        val parent = dest.getParent
        Files.createDirectories(parent)
        classifyRef(root, meta.url, refValue).flatMap { kindName =>
            val cloneArgs: Seq[String] =
                if kindName != "commit" then
                    Seq(
                        "clone",
                        "--origin",
                        "origin",
                        "-b",
                        refValue,
                        "--single-branch",
                        meta.url,
                        dest.getFileName.toString
                    )
                else Seq("clone", meta.url, dest.getFileName.toString)
            git(parent, cloneArgs*).flatMap { _ =>
                val checkoutStep: Unit < (Async & Abort[RefRepoError]) =
                    if kindName == "commit" then git(dest, "checkout", refValue).map(_ => ())
                    else ()
                checkoutStep.flatMap { _ =>
                    git(dest, "rev-parse", "HEAD").map { sha =>
                        RefPin(kindName, refValue, Some(sha.trim))
                    }
                }
            }
        }

    private def inferRefPin(checkout: NioPath)(using Frame): RefPin < (Async & Abort[RefRepoError]) =
        git(checkout, "rev-parse", "HEAD").flatMap { sha0 =>
            val sha = sha0.trim
            Abort
                .run[RefRepoError] {
                    git(checkout, "symbolic-ref", "-q", "HEAD")
                }
                .flatMap {
                    case Result.Success(symref) if symref.trim.startsWith("refs/heads/") =>
                        RefPin("branch", symref.trim.stripPrefix("refs/heads/").trim, Some(sha))
                    case _ =>
                        // Detached HEAD — try an exact tag match before falling back to commit.
                        // Port source parity: `git describe --tags --exact-match HEAD`.
                        Abort
                            .run[RefRepoError] {
                                git(checkout, "describe", "--tags", "--exact-match", "HEAD")
                            }
                            .map {
                                case Result.Success(tag) if tag.trim.nonEmpty =>
                                    RefPin("tag", tag.trim, Some(sha))
                                case _ =>
                                    RefPin("commit", sha, Some(sha))
                            }
                }
        }

    // -----------------------------------------------------------------------
    // Repair helpers
    // -----------------------------------------------------------------------

    /** Walk `.ref/` and return each git checkout with its path relative to `.ref/`. */
    private def discoverCheckouts(refDir: NioPath): List[(NioPath, String)] =
        val result = scala.collection.mutable.ListBuffer[(NioPath, String)]()
        def walk(dir: NioPath, segs: List[String]): Unit =
            if !Files.isDirectory(dir) then ()
            else if Files.isDirectory(dir.resolve(".git")) then result += ((dir, segs.mkString("/")))
            else
                val stream = Files.newDirectoryStream(dir)
                try
                    stream.forEach { p =>
                        if Files.isDirectory(p) then walk(p, segs :+ p.getFileName.toString)
                    }
                finally stream.close()
        if Files.exists(refDir) then walk(refDir, Nil)
        result.toList

    private def entryFromCheckout(
        checkout: NioPath,
        relPath: String,
        existing: Option[RepoEntry]
    )(using Frame): RepoEntry < (Async & Abort[RefRepoError]) =
        Abort
            .run[RefRepoError] {
                git(checkout, "remote", "get-url", "origin")
            }
            .flatMap { remoteResult =>
                val urlOpt = remoteResult match
                    case Result.Success(url) if url.trim.nonEmpty => Some(url.trim)
                    case _                                        => None
                val metaOpt = urlOpt
                    .flatMap(u => parseRepoUrl(u).toOption)
                    .orElse(pathToMeta(relPath))
                metaOpt match
                    case None =>
                        Abort.fail(
                            RefRepoError.GitFailed(
                                List("remote", "get-url", "origin"),
                                -1,
                                s"Cannot determine repo metadata from $relPath"
                            )
                        )
                    case Some(m) =>
                        inferRefPin(checkout).map { pin =>
                            val ts   = ManifestFile.nowIso
                            val path = s".ref/$relPath"
                            existing match
                                case Some(e) =>
                                    val lastUpdated = if e.ref == pin then e.last_updated else ts
                                    e.copy(
                                        id = m.id,
                                        url = if m.host == "localhost" then e.url else m.url,
                                        host = if m.host == "localhost" then e.host else m.host,
                                        owner = m.owner,
                                        name = m.name,
                                        path = path,
                                        ref = pin,
                                        last_updated = lastUpdated
                                    )
                                case None =>
                                    RepoEntry(m.id, m.url, m.host, m.owner, m.name, path, pin, ts, ts)
                        }
            }

    /** Process each checkout sequentially, skipping those that fail. */
    private def processCheckouts(
        items: List[(NioPath, String)],
        priorByPath: Map[String, RepoEntry],
        priorById: Map[String, RepoEntry],
        acc: List[RepoEntry]
    )(using Frame): List[RepoEntry] < (Async & Abort[RefRepoError]) =
        items match
            case Nil => acc
            case (checkout, relPath) :: rest =>
                val path = s".ref/$relPath"
                val existing = priorByPath.get(path).orElse {
                    // Try to match by id even if the path changed.
                    pathToMeta(relPath).flatMap(m => priorById.get(m.id))
                }
                Abort
                    .run[RefRepoError] {
                        entryFromCheckout(checkout, relPath, existing)
                    }
                    .flatMap {
                        case Result.Success(entry) => processCheckouts(rest, priorByPath, priorById, acc :+ entry)
                        case _                     => processCheckouts(rest, priorByPath, priorById, acc)
                    }

    // -----------------------------------------------------------------------
    // Per-operation implementations
    // -----------------------------------------------------------------------

    private def opEnsure(root: NioPath)(using Frame): Unit < (Async & Abort[RefRepoError]) =
        ManifestFile.ensureLayout(root)
        if !Files.exists(ManifestFile.manifestPath(root)) then ManifestFile.write(root, ManifestFile.empty)

    private def opAdd(
        root: NioPath,
        rawUrl: String,
        ref: Maybe[String],
        artifacts: Chunk[String]
    )(using Frame): RepoEntry < (Async & Abort[RefRepoError]) =
        ManifestFile.ensureLayout(root)
        parseRepoUrl(rawUrl) match
            case Left(err) => Abort.fail(RefRepoError.GitFailed(Nil, -1, err))
            case Right(meta) =>
                ManifestFile.read(root).flatMap { manifest =>
                    if manifest.repos.exists(_.id == meta.id) then Abort.fail(RefRepoError.AlreadyExists(meta.id))
                    else
                        val refComputed: String < (Async & Abort[RefRepoError]) = ref match
                            case Maybe.Present(r) => r
                            case Maybe.Absent     => determineDefaultRef(root, meta.url)
                        refComputed.flatMap { refValue =>
                            cloneRepo(root, meta, refValue).map { pin =>
                                val ts = ManifestFile.nowIso
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
                                ManifestFile.write(root, manifest.copy(repos = manifest.repos :+ entry))
                                entry
                            }
                        }
                }

    private def opList(root: NioPath)(using Frame): Chunk[RepoEntry] < (Async & Abort[RefRepoError]) =
        ManifestFile.read(root).map(m => Chunk.from(m.repos))

    private def opContext(root: NioPath)(using Frame): ContextReport < (Async & Abort[RefRepoError]) =
        ManifestFile.read(root).map { m =>
            val repos = Chunk.from(m.repos)
            val summary =
                if m.repos.isEmpty then s"No reference repositories available. Use add to clone upstream sources."
                else
                    val lines = m.repos.map { r =>
                        val sha = r.ref.resolved_sha.map(_.take(7)).getOrElse("unknown")
                        s"- ${r.id} → ${r.path} (${r.ref.value} @ $sha)"
                    }
                    "Reference repositories:\n" + lines.mkString("\n")
            ContextReport(repos, summary)
        }

    private def opRefs(root: NioPath, idOrUrl: String)(using Frame): Chunk[RefOption] < (Async & Abort[RefRepoError]) =
        ManifestFile.read(root).flatMap { manifest =>
            val url = resolveUrl(idOrUrl, manifest)
            Abort
                .run[RefRepoError] {
                    git(root, "ls-remote", "--sort=-committerdate", "--heads", url)
                }
                .flatMap { branchRes =>
                    Abort
                        .run[RefRepoError] {
                            git(root, "ls-remote", "--sort=-committerdate", "--tags", url)
                        }
                        .map { tagRes =>
                            val branches = branchRes match
                                case Result.Success(out) =>
                                    Chunk.from(out.linesIterator.flatMap { line =>
                                        line.split("\\s+").toList match
                                            case sha :: ref :: Nil if ref.startsWith("refs/heads/") =>
                                                Some(RefOption("branch", ref.stripPrefix("refs/heads/"), Some(sha)))
                                            case _ => None
                                    }.toList)
                                case _ => Chunk.empty[RefOption]
                            val tags = tagRes match
                                case Result.Success(out) =>
                                    Chunk.from(out.linesIterator.flatMap { line =>
                                        line.split("\\s+").toList match
                                            case sha :: ref :: Nil if ref.startsWith("refs/tags/") =>
                                                val name = ref.stripPrefix("refs/tags/")
                                                if name.endsWith("^{}") then None
                                                else Some(RefOption("tag", name, Some(sha)))
                                            case _ => None
                                    }.toList)
                                case _ => Chunk.empty[RefOption]
                            (branches ++ tags).take(4)
                        }
                }
        }

    private def opUpdate(
        root: NioPath,
        id: String,
        ref: Maybe[String]
    )(using Frame): RepoEntry < (Async & Abort[RefRepoError]) =
        ManifestFile.read(root).flatMap { manifest =>
            manifest.repos.find(_.id == id) match
                case None => Abort.fail(RefRepoError.RepoNotFound(id))
                case Some(entry) =>
                    ref match
                        case Maybe.Absent =>
                            Abort.fail(
                                RefRepoError.GitFailed(List("update", id), -1, s"No --ref provided for update of $id")
                            )
                        case Maybe.Present(refValue) =>
                            val dest = ManifestFile.checkoutPath(root, entry.path)
                            git(dest, "fetch", "--tags", "origin").flatMap { _ =>
                                // Classify the ref before checkout so the pin type is accurate.
                                // Port source parity: mirrors updateRepo's refKind dispatch.
                                classifyRef(root, entry.url, refValue).flatMap { kindName =>
                                    val checkoutStep: Unit < (Async & Abort[RefRepoError]) = kindName match
                                        case "branch" =>
                                            git(dest, "checkout", refValue).flatMap { _ =>
                                                Abort
                                                    .run[RefRepoError] {
                                                        git(dest, "pull", "--ff-only", "origin", refValue)
                                                    }
                                                    .map(_ => ())
                                            }
                                        case "tag" =>
                                            git(dest, "checkout", s"tags/$refValue").map(_ => ())
                                        case _ => // commit sha
                                            git(dest, "checkout", refValue).map(_ => ())
                                    checkoutStep.flatMap { _ =>
                                        git(dest, "rev-parse", "HEAD").map { sha =>
                                            val pin = RefPin(kindName, refValue, Some(sha.trim))
                                            saveUpdatedEntry(manifest, id, entry, pin, root)
                                        }
                                    }
                                }
                            }
        }

    private def saveUpdatedEntry(
        manifest: ManifestFile,
        id: String,
        entry: RepoEntry,
        pin: RefPin,
        root: NioPath
    ): RepoEntry =
        val updated  = entry.copy(ref = pin, last_updated = ManifestFile.nowIso)
        val newRepos = manifest.repos.map(r => if r.id == id then updated else r)
        ManifestFile.write(root, manifest.copy(repos = newRepos))
        updated

    private def opRemove(root: NioPath, id: String)(using Frame): Unit < (Async & Abort[RefRepoError]) =
        ManifestFile.read(root).flatMap { manifest =>
            manifest.repos.find(_.id == id) match
                case None => Abort.fail(RefRepoError.RepoNotFound(id))
                case Some(entry) =>
                    val dest = ManifestFile.checkoutPath(root, entry.path)
                    if Files.exists(dest) then deleteRecursive(dest)
                    ManifestFile.write(root, manifest.copy(repos = manifest.repos.filterNot(_.id == id)))
        }

    private def opPurge(root: NioPath)(using Frame): Unit < (Async & Abort[RefRepoError]) =
        ManifestFile.read(root).map { manifest =>
            manifest.repos.foreach { entry =>
                val dest = ManifestFile.checkoutPath(root, entry.path)
                if Files.exists(dest) then deleteRecursive(dest)
            }
            ManifestFile.write(root, ManifestFile.empty)
        }

    private def opRepair(root: NioPath)(using Frame): RepairReport < (Async & Abort[RefRepoError]) =
        ManifestFile.ensureLayout(root)
        val refDir = root.resolve(ManifestFile.RefDir)
        ManifestFile.read(root).flatMap { prior =>
            val priorById   = prior.repos.map(r => r.id -> r).toMap
            val priorByPath = prior.repos.map(r => r.path -> r).toMap
            val checkouts   = discoverCheckouts(refDir)
            processCheckouts(checkouts, priorByPath, priorById, Nil).map { rebuilt =>
                val rebuiltIds = rebuilt.map(_.id).toSet
                val orphans    = prior.repos.filterNot(r => rebuiltIds.contains(r.id))
                val added      = rebuilt.count(m => !priorById.contains(m.id))
                val updated    = rebuilt.count(m => priorById.get(m.id).exists(p => p.ref != m.ref || p.path != m.path))
                ManifestFile.write(root, ManifestFile(ManifestFile.Version, rebuilt.sortBy(_.id)))
                RepairReport(added, updated, orphans.length, rebuilt.length)
            }
        }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private def deleteRecursive(path: NioPath): Unit =
        if Files.isDirectory(path) then
            val stream = Files.newDirectoryStream(path)
            try stream.forEach(deleteRecursive)
            finally stream.close()
        Files.deleteIfExists(path)

end ReferenceRepoHandler
