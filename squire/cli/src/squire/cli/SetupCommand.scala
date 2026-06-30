package squire.cli

import caseapp.*
import kyo.*

final case class SetupOptions()

/** `squire setup` — placeholder for future project setup. Not yet implemented. */
object SetupCommand extends KyoCommand[SetupOptions]:
    override def name = "setup"

    run {
        Console.printLine("squire setup: not yet implemented")
    }
end SetupCommand
