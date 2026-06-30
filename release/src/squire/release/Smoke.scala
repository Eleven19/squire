package squire.release

import kyo.*

/** Post-build smoke checks against a built squire binary: an `info --json` probe, an MCP stdio `initialize` handshake
  * against `squire mcp`, and `claude plugin validate .`.
  *
  * Each external process runs through [[kyo.Command]] at the I/O edge; failures are captured as a boolean plus a short
  * detail string so a single failing check never aborts the whole run. The MCP handshake reads one newline-delimited
  * JSON-RPC response line and then forcibly terminates the long-lived server (the server's loop never exits on stdin
  * EOF), guarded by a timeout so a broken binary cannot hang the release.
  */
object Smoke:

    final case class Check(name: String, ok: Boolean, detail: String) derives CanEqual

    private def run(cwd: kyo.Path, cmd: String*)(using Frame): (Boolean, String) < (Async & Abort[String]) =
        Abort.run[CommandException] {
            Command(cmd*).cwd(cwd).redirectErrorStream(true).textWithExitCode
        }.map {
            case Result.Success((out: String, code: ExitCode)) => (code.isSuccess, out.trim)
            case Result.Failure(e)                             => (false, e.toString)
            case Result.Panic(t)                               => (false, t.toString)
        }

    /** The release asset name for the current platform, matching the published GitHub release assets. */
    def platformAsset: String =
        val os   = sys.props.getOrElse("os.name", "").toLowerCase
        val arch = sys.props.getOrElse("os.arch", "").toLowerCase
        val a    = if arch.contains("aarch64") || arch.contains("arm") then "arm64" else "x64"
        if os.contains("mac") then s"squire-macos-$a"
        else if os.contains("win") then s"squire-windows-$a.exe"
        else s"squire-linux-$a"

    /** Run all smoke checks against `bin`, with the repository at `repoRoot` as the working directory. */
    def checks(repoRoot: kyo.Path, bin: kyo.Path)(using Frame): Chunk[Check] < (Async & Abort[String]) =
        for
            info   <- run(repoRoot, bin.toString, "info", "--json")
            mcp    <- mcpHandshake(repoRoot, bin)
            plugin <- run(repoRoot, "claude", "plugin", "validate", ".")
        yield Chunk(
            Check("info", info._1 && info._2.contains("\"version\""), info._2),
            Check("mcp-initialize", mcp._1, mcp._2),
            Check("plugin-validate", plugin._1, plugin._2)
        )

    private def mcpHandshake(cwd: kyo.Path, bin: kyo.Path)(using Frame): (Boolean, String) < (Async & Abort[String]) =
        val request =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"squire-smoke","version":"0"}}}"""

        // The server uses a line-delimited JSON-RPC stdio transport, so the initialize response arrives as the first
        // newline-terminated line on stdout. Read up to that newline, then let the scope close and forcibly destroy the
        // process (its loop blocks on Async.never and never exits on stdin EOF).
        val firstLine: String < (Async & Abort[CommandException]) =
            Scope.run {
                Command(bin.toString, "mcp")
                    .cwd(cwd)
                    .stdin(request + "\n")
                    .stream
                    .takeWhilePure(b => b != '\n'.toByte)
                    .run
                    .map(bytes => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8).trim)
            }

        Abort.run[CommandException | Timeout] {
            Async.timeout(15.seconds)(firstLine)
        }.map {
            case Result.Success(line) =>
                (line.contains("\"result\"") && line.contains("\"jsonrpc\""), line.take(200))
            case Result.Failure(_: Timeout) => (false, "mcp initialize timed out")
            case Result.Failure(e)          => (false, e.toString)
            case Result.Panic(t)            => (false, t.toString)
        }
    end mcpHandshake
end Smoke
