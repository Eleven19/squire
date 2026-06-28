package squire.core

/** Shared squire domain. Pure, no effects. */
object Squire:
  val name: String = "squire"

  /** Total, pure greeting used by both the CLI and MCP demos. */
  def greeting(subject: String): String = s"Hello, $subject!"
end Squire
