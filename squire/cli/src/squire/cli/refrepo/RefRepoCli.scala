package squire.cli.refrepo

import kyo.*
import squire.tools.refrepo.*

import java.nio.file.{Path as NioPath, Paths}

/** Shared helpers for the `ref repo *` CLI commands. */
private[cli] object RefRepoCli:

    /** Returns the git toplevel for the current working directory,
      * falling back to the JVM working directory if git is unavailable.
      */
    def projectRoot(using Frame): NioPath < (Async & Abort[Throwable]) =
        Abort.run[CommandException] {
            Command("git", "rev-parse", "--show-toplevel")
                .redirectErrorStream(true)
                .textWithExitCode
        }.map {
            case Result.Success((out, code)) if code.isSuccess => Paths.get(out.trim)
            case _                                              => Paths.get(java.lang.System.getProperty("user.dir"))
        }

    /** Human-readable message for each [[RefRepoError]] variant. */
    private def renderError(e: RefRepoError): String = e match
        case RefRepoError.RepoNotFound(id)              => s"Repository not found: $id"
        case RefRepoError.AlreadyExists(id)             => s"Repository already exists: $id"
        case RefRepoError.ManifestParse(detail)         => s"Manifest parse error: $detail"
        case RefRepoError.GitFailed(args, exit, stderr) =>
            s"git ${args.mkString(" ")} failed (exit $exit): $stderr"
        case RefRepoError.NotAGitRepo                   => "Not inside a git repository"

    /** Resolve the project root, interpret a [[ReferenceRepo]] op against it, and surface
      * any [[RefRepoError]] as a [[RuntimeException]] inside [[Abort]][[[Throwable]]].
      */
    def runRef[A](op: A < ReferenceRepo)(using Frame): A < (Async & Abort[Throwable]) =
        projectRoot.flatMap { root =>
            Abort.run[RefRepoError](ReferenceRepoHandler.run(root)(op)).flatMap {
                case Result.Success(a) => a
                case Result.Failure(e) => Abort.fail[Throwable](new RuntimeException(renderError(e)))
                case Result.Panic(t)   => throw t
            }
        }

end RefRepoCli
