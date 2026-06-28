package squire.cli

import caseapp.*
import kyo.*
import squire.core.Squire

final case class GreetOptions(
    @Name("name") name: String = "world"
)

/** `squire greet [--name X]` — prints a greeting. Demo CLI subcommand. */
object GreetCommand extends KyoCommand[GreetOptions]:
  override def name = "greet"
  run { opts =>
    Console.printLine(Squire.greeting(opts.name))
  }
end GreetCommand
