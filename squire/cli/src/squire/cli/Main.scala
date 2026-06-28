package squire.cli

import caseapp.*
import kyo.*
import squire.core.Squire

final case class CliOptions(
    @Name("name") name: String = "world"
)

object Main extends KyoCaseApp[CliOptions]:
  run { opts =>
    Console.printLine(Squire.greeting(opts.name))
  }
end Main
