package squire.release

import kyo.*

final case class SemVer(
    major: Int,
    minor: Int,
    patch: Int,
    prerelease: Maybe[String] = Maybe.empty,
    build: Maybe[String] = Maybe.empty
) derives CanEqual:
    def isPrerelease: Boolean = prerelease.isDefined
    def core: SemVer          = copy(prerelease = Maybe.empty, build = Maybe.empty)
    override def toString =
        val pre = prerelease.map(p => s"-$p").getOrElse("")
        val b   = build.map(m => s"+$m").getOrElse("")
        s"$major.$minor.$patch$pre$b"

private object SemVerOrdering:
    def lt(a: SemVer, b: SemVer): Boolean = compare(a, b) < 0

    /** SemVer 2.0.0 precedence; build metadata ignored. */
    def compare(a: SemVer, b: SemVer): Int =
        val core =
            val mj = a.major.compare(b.major)
            if mj != 0 then mj
            else
                val mn = a.minor.compare(b.minor)
                if mn != 0 then mn else a.patch.compare(b.patch)
        if core != 0 then core
        else
            (a.prerelease, b.prerelease) match
                case (Maybe.Absent, Maybe.Absent)         => 0
                case (Maybe.Absent, Maybe.Present(_))     => 1
                case (Maybe.Present(_), Maybe.Absent)     => -1
                case (Maybe.Present(x), Maybe.Present(y)) => comparePre(x.split('.').toList, y.split('.').toList)

    private def comparePre(xs: List[String], ys: List[String]): Int =
        (xs, ys) match
            case (Nil, Nil) => 0
            case (Nil, _)   => -1
            case (_, Nil)   => 1
            case (x :: xt, y :: yt) =>
                val c = compareId(x, y)
                if c != 0 then c else comparePre(xt, yt)

    private def compareId(x: String, y: String): Int =
        val xNum = x.forall(_.isDigit)
        val yNum = y.forall(_.isDigit)
        (xNum, yNum) match
            case (true, true)   => x.toInt.compare(y.toInt)
            case (true, false)  => -1
            case (false, true)  => 1
            case (false, false) => x.compare(y)
end SemVerOrdering

object SemVer:
    // SemVer 2.0.0 precedence: hand-written, NOT derived (a release sorts above its prereleases).
    given Ordering[SemVer] = Ordering.fromLessThan(SemVerOrdering.lt)

enum Bump derives CanEqual:
    case Major, Minor, Patch

object Version:
    private val tagRe = "v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?".r

    def parseTag(tag: String): Maybe[SemVer] =
        tag.trim match
            case tagRe(a, b, c, pre, build) =>
                Maybe(SemVer(
                    a.toInt,
                    b.toInt,
                    c.toInt,
                    if pre == null then Maybe.empty else Maybe(pre),
                    if build == null then Maybe.empty else Maybe(build)
                ))
            case _ => Maybe.empty

    def bumpOf(c: Maybe[ConventionalCommit]): Bump =
        c match
            case Maybe.Present(cc) if cc.breaking         => Bump.Major
            case Maybe.Present(cc) if cc.`type` == "feat" => Bump.Minor
            case _                                        => Bump.Patch

    private def rank(b: Bump): Int = b match
        case Bump.Major => 3
        case Bump.Minor => 2
        case Bump.Patch => 1

    def nextVersion(last: SemVer, commits: Chunk[Maybe[ConventionalCommit]]): SemVer =
        val base      = last.core
        val highest   = if commits.isEmpty then Bump.Patch else commits.map(bumpOf).maxBy(rank)
        val effective = if base.major == 0 && highest == Bump.Major then Bump.Minor else highest
        effective match
            case Bump.Major => SemVer(base.major + 1, 0, 0)
            case Bump.Minor => SemVer(base.major, base.minor + 1, 0)
            case Bump.Patch => SemVer(base.major, base.minor, base.patch + 1)
end Version
