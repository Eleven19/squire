package squire.release

import kyo.*
import kyo.test.Test

class ChangelogTest extends Test[Any]:

    private val sample =
        """|# Changelog
           |
           |All notable changes to this project are documented here.
           |
           |## [Unreleased]
           |
           |### Added
           |
           |### Changed
           |
           |### Fixed
           |
           |### Documentation
           |
           |### CI
           |
           |## [0.1.0] - 2026-06-30
           |
           |### Added
           |
           |- First release.
           |- Second item.
           |
           |### Changed
           |
           |### Fixed
           |
           |### Documentation
           |
           |### CI
           |""".stripMargin

    "Changelog" - {
        "parse extracts sections and buckets" in {
            Abort.run[String](Changelog.parse(sample)).map {
                case Result.Success(cl) =>
                    assert(cl.sections.map(_.version) == Chunk("Unreleased", "0.1.0"))
                    val v = cl.sections.find(_.version == "0.1.0").get
                    assert(v.date == Maybe("2026-06-30"))
                    assert(v.buckets.find(_.name == "Added").get.entries == Chunk("First release.", "Second item."))
                case other => fail(s"expected success, got $other")
            }
        }
        "parse then render round-trips" in {
            Abort.run[String](Changelog.parse(sample)).map {
                case Result.Success(cl) => assert(Changelog.render(cl) == sample)
                case other              => fail(s"expected success, got $other")
            }
        }
        "extractSection returns matching section" in {
            Abort.run[String](Changelog.parse(sample)).map {
                case Result.Success(cl) =>
                    assert(Changelog.extractSection(cl, "0.1.0").map(_.version) == Maybe("0.1.0"))
                    assert(Changelog.extractSection(cl, "9.9.9") == Maybe.empty)
                case other => fail(s"$other")
            }
        }
        "validate fails on missing version" in {
            Abort.run[String](Changelog.parse(sample)).map {
                case Result.Success(cl) =>
                    Abort.run[String](Changelog.validate(cl, "9.9.9")).map(r => assert(r.isFailure))
                case other => fail(s"$other")
            }
        }
        "validate succeeds for a dated section" in {
            Abort.run[String](Changelog.parse(sample)).map {
                case Result.Success(cl) =>
                    Abort.run[String](Changelog.validate(cl, "0.1.0")).map(r => assert(r.isSuccess))
                case other => fail(s"$other")
            }
        }
        "promote moves Unreleased and prepends fresh Unreleased; idempotent" in {
            Abort.run[String](Changelog.parse(sample)).map {
                case Result.Success(cl) =>
                    val moved = cl.copy(sections = cl.sections.map(s =>
                        if s.version == "Unreleased" then
                            s.copy(buckets = s.buckets.map(b => if b.name == "Added" then b.copy(entries = Chunk("New.")) else b))
                        else s))
                    val p = Changelog.promote(moved, "0.2.0", "2026-07-01")
                    assert(p.sections.map(_.version) == Chunk("Unreleased", "0.2.0", "0.1.0"))
                    assert(p.sections.head.buckets.forall(_.entries.isEmpty))
                    assert(p.sections.find(_.version == "0.2.0").get.date == Maybe("2026-07-01"))
                    assert(Changelog.promote(p, "0.2.0", "2026-07-02") == p)
                case other => fail(s"$other")
            }
        }
    }
end ChangelogTest
