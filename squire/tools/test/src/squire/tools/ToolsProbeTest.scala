package squire.tools

import kyo.test.Test

class ToolsProbeTest extends Test[Any]:
  "squire.tools" - {
    "name" in {
      assert(Tools.name == "squire-tools")
    }
  }
