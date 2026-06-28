package squire.core

import utest.*

object SquireTest extends TestSuite {
  val tests = Tests {
    test("greeting names the subject") {
      assert(Squire.greeting("squire") == "Hello, squire!")
    }
  }
}
