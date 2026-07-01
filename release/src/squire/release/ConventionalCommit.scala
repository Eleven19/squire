package squire.release

import kyo.*

import scala.util.matching.Regex

final case class ConventionalCommit(
    `type`: String,
    scope: Maybe[String],
    breaking: Boolean,
    description: String
) derives CanEqual

object ConventionalCommit:
    /** Bucket order shared with the changelog. */
    val Buckets: Chunk[String] = Chunk("Added", "Changed", "Fixed", "Documentation", "CI")

    private val prSuffix: Regex = " \\(#\\d+\\)$".r

    /** type = lower-case word; optional parenthesised scope; optional "!"; then ": " then the rest. */
    private def parser(using Frame): ConventionalCommit < Parse[Char] =
        for
            t     <- Parse.regex("[a-z]+".r)
            scope <- Parse.attempt(Parse.between(Parse.literal("("), Parse.regex("[^)]+".r), Parse.literal(")")))
            bang  <- Parse.attempt(Parse.literal("!")).map(_.isDefined)
            _     <- Parse.literal(": ")
            desc  <- Parse.regex(".+".r)
        yield ConventionalCommit(t, scope, bang, prSuffix.replaceAllIn(desc, ""))

    /** Parse a single commit subject; Absent when the line is not Conventional Commits. */
    def parse(subject: String)(using Frame): Maybe[ConventionalCommit] < Any =
        Parse.runResult(subject)(parser).map(_.out)

    /** Keep a Changelog bucket for a parsed (or absent) commit. */
    def bucketOf(c: Maybe[ConventionalCommit]): String =
        c match
            case Maybe.Present(cc) =>
                cc.`type` match
                    case "feat"                            => "Added"
                    case "fix"                             => "Fixed"
                    case "docs"                            => "Documentation"
                    case "ci" | "build" | "chore" | "test" => "CI"
                    case _                                 => "Changed"
            case Maybe.Absent => "Changed"
end ConventionalCommit
