package squire.core

import kyo.test.Test

class SquireTest extends Test[Any]:

    "Squire" - {
        "exposes the project name" in
            assert(Squire.name == "squire")
    }
