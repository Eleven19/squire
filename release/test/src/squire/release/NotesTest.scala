package squire.release

import kyo.*
import kyo.test.Test

class NotesTest extends Test[Any]:
    "Notes.generated" - {
        "groups conventional commits by bucket and omits empties" in {
            Notes.generated(Chunk("feat: add A (#1)", "fix: B", "totally freeform")).map { out =>
                assert(out.contains("### Added"))
                assert(out.contains("- Add A"))
                assert(out.contains("### Fixed"))
                assert(out.contains("- B"))
                assert(out.contains("### Changed"))
                assert(out.contains("- totally freeform"))
                assert(!out.contains("### Documentation"))
            }
        }
    }
end NotesTest
