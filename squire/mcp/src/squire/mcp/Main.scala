package squire.mcp

import kyo.*
import squire.core.Squire

object Main extends KyoApp:

  case class GreetIn(subject: String) derives Schema, CanEqual
  case class GreetOut(reply: String) derives Schema, CanEqual

  run {
    val greet =
      McpHandler.tool[GreetIn]("greet", "Greet a subject") { in =>
        GreetOut(Squire.greeting(in.subject))
      }
    JsonRpcTransport.stdio().map { transport =>
      McpServer.initWith(transport, greet)(_ => Async.never)
    }
  }

end Main
