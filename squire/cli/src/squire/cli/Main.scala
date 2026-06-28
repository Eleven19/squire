package squire.cli

import caseapp.core.app.{Command, CommandsEntryPoint}

/** squire's single CLI entrypoint. Subcommands include the MCP server (`mcp`)
  * and ordinary CLI tools (`greet`, …). */
object Main extends CommandsEntryPoint:
  def progName: String          = "squire"
  override def description       = "squire — MCP server and CLI tools"
  def commands: Seq[Command[?]] = Seq(GreetCommand, McpCommand, SetupCommand)
end Main
