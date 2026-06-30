package squire.core

import kyo.test.Test

class SquireTest extends Test[Any]:

    "Squire" - {
        "greeting names the subject" in
            assert(Squire.greeting("squire") == "Hello, squire!")
    }
