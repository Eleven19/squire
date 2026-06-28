package millbuild

import mill.*
import mill.api.BuildCtx

trait HarnessGen extends Cross.Module[String] {
  def id = crossValue

  /** Registers the skill directory as a tracked source so Mill allows reads from it. */
  def skillDir: T[PathRef] = Task.Source(BuildCtx.workspaceRoot / "skills" / "reference-repos")

  def generate: T[PathRef] = Task {
    val sDir  = skillDir().path
    val model = Codegen.model(sDir)
    Harness.emit(id, model).foreach {
      case GenFile.Write(p, content) => os.write.over(Task.dest / p, content, createFolders = true)
      case GenFile.Copy(p, src)      => os.copy(src, Task.dest / p, replaceExisting = true, createFolders = true)
    }
    PathRef(Task.dest)
  }
}
