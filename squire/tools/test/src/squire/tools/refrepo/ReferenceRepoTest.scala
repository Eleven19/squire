package squire.tools.refrepo

import kyo.*
import kyo.test.Test

import java.nio.file.{Files, Path as NioPath}
import scala.jdk.CollectionConverters.*

class ReferenceRepoTest extends Test[Any]:

    // Git operations are I/O-bound and must run sequentially to avoid fixture conflicts.
    override def config = super.config.sequential

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    /** Run a git command in `dir`; throws on non-zero exit. */
    private def runGit(dir: NioPath, args: String*): Unit =
        val allArgs = ("git" +: args.toSeq).asJava
        val pb      = new java.lang.ProcessBuilder(allArgs)
        pb.directory(dir.toFile)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out  = new String(proc.getInputStream.readAllBytes())
        val code = proc.waitFor()
        if code != 0 then
            throw new RuntimeException(s"git ${args.mkString(" ")} failed (exit $code):\n$out")

    /** Create a non-bare git repo with one commit on branch `main`. */
    private def initSource(): NioPath =
        val dir = Files.createTempDirectory("squire-ref-src-")
        runGit(dir, "init")
        // Pin the default branch to `main` regardless of git config.
        runGit(dir, "symbolic-ref", "HEAD", "refs/heads/main")
        runGit(dir, "config", "user.email", "test@squire.test")
        runGit(dir, "config", "user.name", "Squire Test")
        Files.writeString(dir.resolve("README.md"), "# test\n")
        runGit(dir, "add", "README.md")
        runGit(dir, "commit", "-m", "init")
        dir

    /** Create an empty directory that acts as the project root (git-initialised). */
    private def initProject(): NioPath =
        val dir = Files.createTempDirectory("squire-ref-proj-")
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@squire.test")
        runGit(dir, "config", "user.name", "Squire Test")
        dir

    /** Run a git command in `dir` and return its stdout; throws on non-zero exit. */
    private def captureGit(dir: NioPath, args: String*): String =
        val allArgs = ("git" +: args.toSeq).asJava
        val pb      = new java.lang.ProcessBuilder(allArgs)
        pb.directory(dir.toFile)
        val proc = pb.start()
        val out  = new String(proc.getInputStream.readAllBytes())
        val code = proc.waitFor()
        if code != 0 then throw new RuntimeException(s"git ${args.mkString(" ")} failed (exit $code)")
        out

    /** Source repo with a lightweight tag `v0.1` pinned to the initial commit.
      * Returns `(dir, commitSha, "v0.1")`.
      */
    private def initSourceWithTag(): (NioPath, String, String) =
        val dir = initSource()
        val sha = captureGit(dir, "rev-parse", "HEAD").trim
        runGit(dir, "tag", "v0.1")
        (dir, sha, "v0.1")

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    "ReferenceRepo" - {

        "ensure" - {

            "creates .ref/ directory and empty manifest" in {
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure
                    }
                }.map {
                    case Result.Failure(e) => fail(s"Expected success, got error: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(_) =>
                        assert(Files.isDirectory(root.resolve(".ref")))
                        assert(Files.exists(root.resolve(".ref/manifest.yaml")))
                }
            }

            "adds .ref/ to .gitignore" in {
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) { ReferenceRepo.ensure }
                }.map { _ =>
                    val gi = root.resolve(".gitignore")
                    assert(Files.exists(gi))
                    assert(Files.readString(gi).contains(".ref/"))
                }
            }

        }

        "add" - {

            "clones a file:// repo and records it in the manifest" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main"))
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"add failed: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(entry) =>
                        assert(entry.ref.`type` == "branch")
                        assert(entry.ref.value == "main")
                        assert(entry.ref.resolved_sha.isDefined)
                        assert(Files.isDirectory(ManifestFile.checkoutPath(root, entry.path)))
                        assert(Files.exists(ManifestFile.manifestPath(root)))
                }
            }

            "fails with AlreadyExists when the same repo is added twice" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { _ =>
                                ReferenceRepo.add(s"file://$src", Maybe.Present("main"))
                            }
                        }
                    }
                }.map {
                    case Result.Success(_) => fail("Expected AlreadyExists error")
                    case Result.Panic(t)   => throw t
                    case Result.Failure(e) => assert(e.isInstanceOf[RefRepoError.AlreadyExists])
                }
            }

            "fails with GitFailed on an invalid URL" in {
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add("file:///nonexistent/squire/test/repo/path", Maybe.Present("main"))
                        }
                    }
                }.map {
                    case Result.Success(_) => fail("Expected GitFailed error")
                    case Result.Panic(t)   => throw t
                    case Result.Failure(e) => assert(e.isInstanceOf[RefRepoError.GitFailed])
                }
            }

        }

        "list" - {

            "returns empty Chunk before any repos are added" in {
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ => ReferenceRepo.list }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"$e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(repos) => assert(repos.isEmpty)
                }
            }

            "reflects repos after add" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { _ =>
                                ReferenceRepo.list
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"$e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(repos) =>
                        assert(repos.size == 1)
                        assert(repos.head.ref.value == "main")
                }
            }

        }

        "remove" - {

            "deletes checkout and removes manifest entry" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { entry =>
                                ReferenceRepo.remove(entry.id).flatMap { _ =>
                                    ReferenceRepo.list
                                }
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"$e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(repos) => assert(repos.isEmpty)
                }
            }

            "fails with RepoNotFound for an unknown id" in {
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.remove("no/such-repo")
                        }
                    }
                }.map {
                    case Result.Success(_) => fail("Expected RepoNotFound")
                    case Result.Panic(t)   => throw t
                    case Result.Failure(e) => assert(e.isInstanceOf[RefRepoError.RepoNotFound])
                }
            }

        }

        "purge" - {

            "removes all checkouts and clears the manifest" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { entry =>
                                ReferenceRepo.purge.flatMap { _ =>
                                    ReferenceRepo.list.map(repos => (repos, entry))
                                }
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"$e")
                    case Result.Panic(t)   => throw t
                    case Result.Success((repos, entry)) =>
                        assert(repos.isEmpty)
                        assert(!Files.exists(ManifestFile.checkoutPath(root, entry.path)))
                }
            }

        }

        "repair" - {

            "rebuilds manifest from on-disk checkouts" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { entry =>
                                // Corrupt the manifest so repair has to rebuild it.
                                Files.delete(ManifestFile.manifestPath(root))
                                ReferenceRepo.repair.flatMap { report =>
                                    ReferenceRepo.list.map(repos => (report, repos, entry))
                                }
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"$e")
                    case Result.Panic(t)   => throw t
                    case Result.Success((report, repos, entry)) =>
                        assert(report.total == 1)
                        assert(report.added == 1)
                        assert(repos.size == 1)
                        assert(repos.head.id == entry.id)
                }
            }

        }

        "context" - {

            "returns a summary listing available repos" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { _ =>
                                ReferenceRepo.context
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"$e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(ctx) =>
                        assert(ctx.repos.size == 1)
                        assert(ctx.summary.nonEmpty)
                }
            }

        }

        "update" - {

            "records type=branch when updated to a branch name" in {
                val (src, _, _) = initSourceWithTag()
                val root        = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { entry =>
                                ReferenceRepo.update(entry.id, Maybe.Present("main"))
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"update to branch failed: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(entry) =>
                        assert(entry.ref.`type` == "branch")
                        assert(entry.ref.value == "main")
                        assert(entry.ref.resolved_sha.isDefined)
                }
            }

            "records type=tag when updated to a tag name" in {
                val (src, _, tagName) = initSourceWithTag()
                val root              = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { entry =>
                                ReferenceRepo.update(entry.id, Maybe.Present(tagName))
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"update to tag failed: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(entry) =>
                        assert(entry.ref.`type` == "tag")
                        assert(entry.ref.value == tagName)
                        assert(entry.ref.resolved_sha.isDefined)
                }
            }

            "records type=commit when updated to a commit sha" in {
                val (src, commitSha, _) = initSourceWithTag()
                val root                = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src", Maybe.Present("main")).flatMap { entry =>
                                ReferenceRepo.update(entry.id, Maybe.Present(commitSha))
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"update to commit sha failed: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(entry) =>
                        assert(entry.ref.`type` == "commit")
                        assert(entry.ref.value == commitSha)
                        assert(entry.ref.resolved_sha.isDefined)
                }
            }

        }

        "refs" - {

            "returns branches and tags for a file:// source repo" in {
                val (src, _, tagName) = initSourceWithTag()
                val root              = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.refs(s"file://$src")
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"refs failed: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(refs) =>
                        assert(refs.exists(r => r.`type` == "branch" && r.value == "main"))
                        assert(refs.exists(r => r.`type` == "tag" && r.value == tagName))
                }
            }

        }

        "multi-repo" - {

            "list returns two entries after adding two repos and manifest re-reads cleanly" in {
                val src1 = initSource()
                val src2 = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(s"file://$src1", Maybe.Present("main")).flatMap { _ =>
                                ReferenceRepo.add(s"file://$src2", Maybe.Present("main")).flatMap { _ =>
                                    ReferenceRepo.list.flatMap { repos =>
                                        // Re-read manifest from disk to verify the YAML round-trip.
                                        ManifestFile.read(root).map { manifest =>
                                            (repos, manifest)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"multi-repo list failed: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success((repos, manifest)) =>
                        assert(repos.size == 2)
                        assert(manifest.repos.size == 2)
                }
            }

        }

        "artifacts" - {

            "preserved through add and list" in {
                val src  = initSource()
                val root = initProject()
                Abort.run[RefRepoError] {
                    ReferenceRepoHandler.run(root) {
                        ReferenceRepo.ensure.flatMap { _ =>
                            ReferenceRepo.add(
                                s"file://$src",
                                Maybe.Present("main"),
                                Chunk("io.x:y")
                            ).flatMap { _ =>
                                ReferenceRepo.list
                            }
                        }
                    }
                }.map {
                    case Result.Failure(e) => fail(s"artifacts test failed: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(repos) =>
                        assert(repos.size == 1)
                        assert(repos.head.artifacts.nonEmpty)
                        assert(repos.head.artifacts.exists(a =>
                            a.group.contains("io.x") && a.artifact.contains("y")
                        ))
                }
            }

        }

    }

end ReferenceRepoTest
