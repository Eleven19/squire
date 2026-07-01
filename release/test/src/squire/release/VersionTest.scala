package squire.release

import kyo.*
import kyo.test.Test

class VersionTest extends Test[Any]:

    private def cc(s: String): ConventionalCommit =
        val parts = s.split(":", 2)
        ConventionalCommit(parts(0).stripSuffix("!"), Maybe.empty, s.takeWhile(_ != ':').endsWith("!"), parts(1).trim)

    "Version" - {
        "parseTag strips leading v and parses X.Y.Z" in {
            assert(Version.parseTag("v1.2.3") == Maybe(SemVer(1, 2, 3)))
            assert(Version.parseTag("0.1.0") == Maybe(SemVer(0, 1, 0)))
            assert(Version.parseTag("nope") == Maybe.empty)
        }
        "parseTag parses prerelease and build, and round-trips toString" in {
            assert(Version.parseTag("v1.2.3-rc.1") == Maybe(SemVer(1, 2, 3, Maybe("rc.1"))))
            assert(
                Version.parseTag("1.0.0-alpha.1+build.5") == Maybe(SemVer(1, 0, 0, Maybe("alpha.1"), Maybe("build.5")))
            )
            assert(SemVer(1, 2, 3, Maybe("rc.1")).toString == "1.2.3-rc.1")
            assert(SemVer(1, 0, 0, Maybe("alpha.1"), Maybe("build.5")).toString == "1.0.0-alpha.1+build.5")
            assert(Version.parseTag(SemVer(2, 0, 0, Maybe("rc.2")).toString) == Maybe(SemVer(2, 0, 0, Maybe("rc.2"))))
        }
        "SemVer precedence follows the spec ordering" in {
            val ordered = List(
                SemVer(1, 0, 0, Maybe("alpha")),
                SemVer(1, 0, 0, Maybe("alpha.1")),
                SemVer(1, 0, 0, Maybe("alpha.beta")),
                SemVer(1, 0, 0, Maybe("beta")),
                SemVer(1, 0, 0, Maybe("beta.2")),
                SemVer(1, 0, 0, Maybe("beta.11")),
                SemVer(1, 0, 0, Maybe("rc.1")),
                SemVer(1, 0, 0)
            )
            val ord = summon[Ordering[SemVer]]
            assert(ordered.sorted(using ord) == ordered)
            assert(ord.lt(SemVer(1, 0, 0, Maybe("rc.1")), SemVer(1, 0, 0)))
            assert(ord.compare(SemVer(1, 0, 0, build = Maybe("a")), SemVer(1, 0, 0, build = Maybe("b"))) == 0)
        }
        "bumpOf maps commit kind to bump" in {
            assert(Version.bumpOf(Maybe(cc("feat: x"))) == Bump.Minor)
            assert(Version.bumpOf(Maybe(cc("fix: x"))) == Bump.Patch)
            assert(Version.bumpOf(Maybe(cc("feat!: x"))) == Bump.Major)
            assert(Version.bumpOf(Maybe.empty) == Bump.Patch)
        }
        "nextVersion applies the 0.x policy" in {
            val base = SemVer(0, 1, 0)
            assert(Version.nextVersion(base, Chunk(Maybe(cc("feat: x")))) == SemVer(0, 2, 0))
            assert(Version.nextVersion(base, Chunk(Maybe(cc("fix: x")))) == SemVer(0, 1, 1))
            assert(Version.nextVersion(base, Chunk(Maybe(cc("feat!: x")))) == SemVer(0, 2, 0))
            assert(Version.nextVersion(base, Chunk(Maybe.empty)) == SemVer(0, 1, 1))
        }
        "nextVersion applies normal SemVer at >= 1.0" in {
            val base = SemVer(1, 2, 3)
            assert(Version.nextVersion(base, Chunk(Maybe(cc("feat!: x")))) == SemVer(2, 0, 0))
            assert(Version.nextVersion(base, Chunk(Maybe(cc("feat: x")))) == SemVer(1, 3, 0))
            assert(Version.nextVersion(base, Chunk(Maybe(cc("fix: x")))) == SemVer(1, 2, 4))
        }
        "nextVersion takes the highest bump across commits" in {
            val cs = Chunk(Maybe(cc("fix: a")), Maybe(cc("feat: b")), Maybe.empty)
            assert(Version.nextVersion(SemVer(1, 0, 0), cs) == SemVer(1, 1, 0))
        }
        "nextVersion uses the core of a prerelease base and returns a clean release" in {
            val base = SemVer(1, 2, 3, Maybe("rc.1"))
            val out  = Version.nextVersion(base, Chunk(Maybe(cc("feat: x"))))
            assert(out == SemVer(1, 3, 0))
            assert(!out.isPrerelease)
        }
    }
end VersionTest
