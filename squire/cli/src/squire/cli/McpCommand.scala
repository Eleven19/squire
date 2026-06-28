package squire.cli

import caseapp.*
import kyo.*
import squire.core.Squire

final case class McpOptions()

final case class GreetIn(subject: String) derives Schema, CanEqual
final case class GreetOut(reply: String) derives Schema, CanEqual

/** `squire mcp` — runs the kyo-mcp server over stdio until interrupted. */
object McpCommand extends KyoCommand[McpOptions]:
  override def name = "mcp"
  run {
    val greet =
      McpHandler.tool[GreetIn]("greet", "Greet a subject") { in =>
        GreetOut(Squire.greeting(in.subject))
      }
    JsonRpcTransport.stdio().map { transport =>
      McpServer.initWith(transport, greet)(_ => Async.never)
    }
  }
end McpCommand
