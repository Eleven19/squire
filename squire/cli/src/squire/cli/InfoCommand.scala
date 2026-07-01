package squire.cli

import caseapp.*
import kyo.*

final case class InfoOptions(@Name("json") json: Boolean = false)

/** `squire info [--json]`: print name, version, and runtime info. */
object InfoCommand extends KyoCommand[InfoOptions]:
    override def name = "info"

    run { opts =>
        val jvm = sys.props.getOrElse("java.version", "")
        if opts.json then
            Console.printLine(
                s"""{"name":"${BuildInfo.name}","version":"${BuildInfo.version}","scala":"${BuildInfo.scalaVersion}","jvm":"$jvm"}"""
            )
        else
            Console
                .printLine(s"${BuildInfo.name} ${BuildInfo.version}")
                .andThen(
                    Console.printLine(s"scala ${BuildInfo.scalaVersion}  jvm $jvm")
                )
    }
end InfoCommand
