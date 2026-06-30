package squire.release

import kyo.*
import kyo.test.Test

class ConventionalCommitTest extends Test[Any]:
    "ConventionalCommit" - {
        "parses type, scope, breaking, description and strips PR suffix" in {
            ConventionalCommit.parse("feat(cli)!: add info command (#7)").map { c =>
                assert(c == Maybe(ConventionalCommit("feat", Maybe("cli"), true, "add info command")))
            }
        }
        "parses a plain type without scope" in {
            ConventionalCommit.parse("fix: correct exit code").map { c =>
                assert(c == Maybe(ConventionalCommit("fix", Maybe.empty, false, "correct exit code")))
            }
        }
        "returns Absent for a non-conventional subject" in {
            ConventionalCommit.parse("Random commit message").map(c => assert(c == Maybe.empty))
        }
        "maps types to buckets" in {
            def b(s: String) = ConventionalCommit.parse(s).map(ConventionalCommit.bucketOf)
            for
                a <- b("feat: x")
                f <- b("fix: x")
                d <- b("docs: x")
                c <- b("ci: x")
                r <- b("refactor: x")
                o <- b("totally non-conventional")
            yield
                assert(a == "Added")
                assert(f == "Fixed")
                assert(d == "Documentation")
                assert(c == "CI")
                assert(r == "Changed")
                assert(o == "Changed")
        }
    }
end ConventionalCommitTest
