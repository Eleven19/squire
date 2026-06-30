package squire.release

import kyo.*

final case class Bucket(name: String, entries: Chunk[String]) derives CanEqual
final case class Section(version: String, date: Maybe[String], buckets: Chunk[Bucket]) derives CanEqual
final case class Changelog(header: String, sections: Chunk[Section]) derives CanEqual

object Changelog:
    /** Bucket order shared with the conventional-commit mapping. */
    val Buckets: Chunk[String] = Chunk("Added", "Changed", "Fixed", "Documentation", "CI")

    /** Classification of a single CHANGELOG line. */
    private enum LineKind derives CanEqual:
        case Version(version: String, date: Maybe[String])
        case BucketHead(name: String)
        case Entry(text: String)
        case Blank
        case Other(text: String)

    /** `## [<version>]` with an optional ` - <date>` suffix. */
    private def versionParser(using Frame): LineKind < Parse[Char] =
        for
            _    <- Parse.literal("## [")
            ver  <- Parse.regex("[^]]+".r)
            _    <- Parse.literal("]")
            date <- Parse.attempt(
                for
                    _ <- Parse.literal(" - ")
                    d <- Parse.regex(".+".r)
                yield d
            )
            _ <- Parse.end[Char]
        yield LineKind.Version(ver, date)

    /** `### <bucket-name>`. */
    private def bucketParser(using Frame): LineKind < Parse[Char] =
        for
            _    <- Parse.literal("### ")
            name <- Parse.regex(".+".r)
            _    <- Parse.end[Char]
        yield LineKind.BucketHead(name.trim)

    /** Classify one line: blank and bullet lines are handled directly; headings go through `Parse[Char]`. */
    private def classify(line: String)(using Frame): LineKind < Any =
        val t = line.trim
        if t.isEmpty then LineKind.Blank
        else if t.startsWith("- ") then LineKind.Entry(t.drop(2).trim)
        else
            Parse.runResult(t)(Parse.firstOf(versionParser, bucketParser))
                .map(_.out.getOrElse(LineKind.Other(line)))

    /** Outer-fold accumulator: completed sections plus the in-progress section and bucket. */
    private final case class State(
        header: Chunk[String],
        done: Chunk[Section],
        curVersion: Maybe[String],
        curDate: Maybe[String],
        curBuckets: Chunk[Bucket],
        curBucketName: Maybe[String],
        curEntries: Chunk[String]
    )

    private object State:
        val empty: State = State(Chunk.empty, Chunk.empty, Maybe.empty, Maybe.empty, Chunk.empty, Maybe.empty, Chunk.empty)

    private def flushBucket(st: State): State =
        st.curBucketName match
            case Maybe.Present(name) =>
                st.copy(
                    curBuckets = st.curBuckets.append(Bucket(name, st.curEntries)),
                    curBucketName = Maybe.empty,
                    curEntries = Chunk.empty
                )
            case Maybe.Absent => st

    private def flushSection(st: State): State =
        val fb = flushBucket(st)
        fb.curVersion match
            case Maybe.Present(v) =>
                fb.copy(
                    done = fb.done.append(Section(v, fb.curDate, fb.curBuckets)),
                    curVersion = Maybe.empty,
                    curDate = Maybe.empty,
                    curBuckets = Chunk.empty
                )
            case Maybe.Absent => fb

    private def step(st: State, line: String)(using Frame): State < Any =
        classify(line).map {
            case LineKind.Version(v, d) =>
                val flushed = flushSection(st)
                flushed.copy(curVersion = Maybe(v), curDate = d)
            case LineKind.BucketHead(name) =>
                flushBucket(st).copy(curBucketName = Maybe(name))
            case LineKind.Entry(text) =>
                st.copy(curEntries = st.curEntries.append(text))
            case LineKind.Blank | LineKind.Other(_) =>
                // Lines before the first version heading accumulate into the document header.
                if st.curVersion.isEmpty && st.done.isEmpty then st.copy(header = st.header.append(line))
                else st
        }

    private def stripTrailingBlankLines(lines: Chunk[String]): Chunk[String] =
        lines.reverse.dropWhile(_.trim.isEmpty).reverse

    /** Parse a CHANGELOG document into header text and ordered sections. */
    def parse(text: String)(using Frame): Changelog < Abort[String] =
        val lines = Chunk.from(text.split("\n", -1).toIndexedSeq)
        Kyo.foldLeft(lines)(State.empty)(step).map { st =>
            val finalState = flushSection(st)
            val header     = stripTrailingBlankLines(finalState.header).mkString("\n")
            Changelog(header, finalState.done)
        }

    private def renderBucket(b: Bucket): String =
        val entries  = b.entries.map(e => s"- $e\n").mkString
        val trailing = if b.entries.isEmpty then "" else "\n"
        s"### ${b.name}\n\n$entries$trailing"

    private def renderSection(s: Section): String =
        val head = s.date match
            case Maybe.Present(d) => s"## [${s.version}] - $d"
            case Maybe.Absent     => s"## [${s.version}]"
        val buckets = s.buckets.map(renderBucket).mkString
        s"$head\n\n$buckets"

    /** Render a CHANGELOG back to its canonical text; `parse` then `render` round-trips. */
    def render(cl: Changelog): String =
        val sections = cl.sections.map(renderSection).mkString
        s"${cl.header}\n\n$sections".stripSuffix("\n")

    /** The section for a version, or Absent when no such section exists. */
    def extractSection(cl: Changelog, version: String): Maybe[Section] =
        Maybe.fromOption(cl.sections.find(_.version == version))

    /** Succeed when the section exists and carries a release date; fail otherwise. */
    def validate(cl: Changelog, version: String)(using Frame): Unit < Abort[String] =
        extractSection(cl, version) match
            case Maybe.Present(s) if s.date.isDefined => ()
            case Maybe.Present(_)                     => Abort.fail(s"CHANGELOG section [$version] has no release date")
            case Maybe.Absent                         => Abort.fail(s"CHANGELOG has no section [$version]")

    private def emptyUnreleased: Section =
        Section("Unreleased", Maybe.empty, Buckets.map(Bucket(_, Chunk.empty)))

    /** Stamp the Unreleased section as `version`/`date` and prepend a fresh, empty Unreleased; idempotent. */
    def promote(cl: Changelog, version: String, date: String): Changelog =
        if cl.sections.exists(_.version == version) then cl
        else
            val moved = cl.sections.map(s =>
                if s.version == "Unreleased" then s.copy(version = version, date = Maybe(date)) else s
            )
            cl.copy(sections = emptyUnreleased +: moved)
end Changelog
