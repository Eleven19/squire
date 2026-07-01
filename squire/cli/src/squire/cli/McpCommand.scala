package squire.cli

import caseapp.*
import kyo.*

final case class McpOptions()

/** `squire mcp` — runs the kyo-mcp server over stdio until interrupted. */
object McpCommand extends KyoCommand[McpOptions]:
    override def name = "mcp"

    run {
        JsonRpcTransport.stdio().map { transport =>
            val handlers: Seq[McpHandler[?, ?, ?]] = McpTools.all
            McpServer.initWith(transport, handlers*)(_ => Async.never)
        }
    }
end McpCommand
