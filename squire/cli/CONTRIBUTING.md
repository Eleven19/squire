# Contributing to squire/cli — the shared-tool pattern

This document explains how to add a new capability to squire so it is available
as both a CLI subcommand and an MCP tool. Use the `reference-repo` family as the
worked example.

## The pattern in one sentence

Implement the capability **once** in `squire/tools` as a kyo `ArrowEffect`, then
expose it **twice** in `squire/cli`: as a CLI subcommand and as an MCP tool.

---

## Step 1 — implement the effect in `squire/tools`

Every capability lives in `squire/tools` as a typed `ArrowEffect`. That module has
no knowledge of CLI or MCP concerns; it only does real work.

### GADT + suspend helpers

```scala
// squire/tools/src/squire/tools/refrepo/ReferenceRepo.scala
sealed trait ReferenceRepo extends ArrowEffect[ReferenceRepo.Op, Id]

object ReferenceRepo:
  sealed trait Op[A]

  // One GADT case per operation; the type parameter is the result type.
  case class Add(url: String, ref: Maybe[String], artifacts: Chunk[String]) extends Op[RepoEntry]
  // … Ensure, ListRepos, Context, Refs, Update, Remove, Purge, Repair

  // Public suspend helper — callers use this, not the Op directly.
  def add(
    url: String,
    ref: Maybe[String] = Maybe.Absent,
    artifacts: Chunk[String] = Chunk.empty
  )(using Frame): RepoEntry < ReferenceRepo =
    ArrowEffect.suspend[RepoEntry](Tag[ReferenceRepo], Add(url, ref, artifacts))
```

Rules:
- Data / output types must `derive Schema, CanEqual`.
- Failures are modelled as `Abort` over a typed error ADT — never throw.
  See `RefRepoError` for the pattern (`enum RefRepoError derives CanEqual`).
- Use `kyo.Command` for spawning processes, not `os.proc` or `ProcessBuilder`.

### Handler

```scala
// squire/tools/src/squire/tools/refrepo/ReferenceRepoHandler.scala
object ReferenceRepoHandler:
  def run[A, S](root: NioPath)(v: A < (ReferenceRepo & S))(using Frame)
      : A < (S & Async & Abort[RefRepoError]) =
    ArrowEffect.handleLoop(summon[Tag[ReferenceRepo]], v) {
      [C] => (op, cont) =>
        val result: C < (Async & Abort[RefRepoError]) = op match
          case a: ReferenceRepo.Add => opAdd(root, a.url, a.ref, a.artifacts)
          // … one branch per Op case
        result.map(c => Loop.continue(cont(c)))
    }
```

The `Env` service pattern is the fallback only when `ArrowEffect` does not fit
(e.g. the effect needs to carry open resources across boundaries).

---

## Step 2 — expose as a CLI subcommand in `squire/cli`

Each operation becomes a `KyoCommand` object in
`squire/cli/src/squire/cli/refrepo/RefRepoCommands.scala`.

```scala
// squire/cli/src/squire/cli/refrepo/RefRepoCommands.scala
final case class RefRepoAddOptions(
  @Name("ref") ref: Option[String] = None,
  @Name("artifacts") artifacts: List[String] = Nil
)

object RefRepoAddCommand extends KyoCommand[RefRepoAddOptions]:
  override def names = List(List("ref", "repo", "add"))   // multi-token name
  run { (opts, remainingArgs) =>
    remainingArgs.remaining.headOption match
      case None =>
        Abort.fail(new IllegalArgumentException("add requires a URL positional argument"))
      case Some(url) =>
        RefRepoCli.runRef(
          ReferenceRepo.add(url, Maybe.fromOption(opts.ref), Chunk.from(opts.artifacts))
        ).flatMap { entry =>
          Console.printLine(s"${entry.id}  ${entry.url}  ${entry.ref.value}")
        }
  }
```

Then register the command in `Main.commands`:

```scala
// squire/cli/src/squire/cli/Main.scala
def commands: Seq[Command[?]] = Seq(
  // … existing commands …
  RefRepoAddCommand,
)
```

`RefRepoCli.runRef` resolves the project root via `git rev-parse --show-toplevel`,
runs the effect through `ReferenceRepoHandler.run`, and converts any `RefRepoError`
to a `RuntimeException` inside `Abort[Throwable]` so the CLI runner can surface it.

---

## Step 3 — expose as an MCP tool in `squire/cli`

Add the input type and tool handler to
`squire/cli/src/squire/cli/McpTools.scala`.

```scala
// squire/cli/src/squire/cli/McpTools.scala

// Input type — must derive Schema and CanEqual.
case class RefRepoAddIn(
  url: String,
  ref: Option[String] = None,
  artifacts: List[String] = Nil
) derives Schema, CanEqual

object McpTools:
  val add: McpHandler[RefRepoAddIn, ToolOutcome, Nothing] =
    McpHandler.toolRaw[RefRepoAddIn](
      "ref-repo-add",                                  // kebab-case tool name
      "Clone and register a reference repository under .ref/"
    ) { in =>
      exec(ReferenceRepo.add(in.url, Maybe.fromOption(in.ref), Chunk.from(in.artifacts)))
    }

  val all: Seq[McpHandler[?, ?, ?]] =
    Seq(ensure, add, list, context, refs, update, remove, purge, repair)
```

MCP tool name rules:
- Kebab-case, matching `^[a-zA-Z0-9_-]{1,64}$`.
- No `/`, `.`, or spaces — providers use these as LLM function names and reject
  anything that does not pass their validation.
- Convention in this module: `ref-repo-<op>`.

`McpTools.all` is consumed by `McpCommand`, which registers every handler with the
kyo-mcp server over stdio.

---

## Testing

Tests live in `squire/tools/test/` and use **kyo-test**:

```scala
// squire/tools/test/src/squire/tools/refrepo/ReferenceRepoTest.scala
class ReferenceRepoTest extends kyo.test.Test[Any]:

  test("add clones and manifests a repo") {
    val root = initProject()   // tmp git repo acting as project root
    val src  = initSource()    // tmp bare-like source repo

    Abort.run[RefRepoError] {
      ReferenceRepoHandler.run(root) {
        ReferenceRepo.add(src.toUri.toString, Maybe.Absent, Chunk.empty)
      }
    }.map {
      case Result.Success(entry) =>
        assertTrue(entry.id.endsWith(src.getFileName.toString))
      case Result.Failure(e) =>
        assertTrue(false, s"unexpected failure: $e")
    }
  }
```

Pattern: extend `kyo.test.Test[Any]`, run effects with
`Abort.run[RefRepoError] { ReferenceRepoHandler.run(root) { … } }`.
Git-heavy tests should set `override def config = super.config.sequential`
to avoid fixture conflicts.

---

## Checklist for adding a new tool

1. Add `case class MyOp(…) extends Op[Result]` to the effect GADT.
2. Add a `suspend` helper (`def myOp(…)(using Frame): Result < MyEffect`).
3. Implement `opMyOp` in the handler; add a branch in `ArrowEffect.handleLoop`.
4. Add `derive Schema, CanEqual` to any new data types.
5. Add `MyOpError` cases to the error ADT if the new op has new failure modes.
6. Create `MyOpOptions` + `MyOpCommand` in the CLI commands file; register in `Main`.
7. Add `MyOpIn` input type + `McpHandler.toolRaw` val to `McpTools`; add to `McpTools.all`.
8. Write a kyo-test covering the happy path and at least one failure case.

---

## Output convention: human by default, `--json` for machines

squire-cli commands print human-readable output by default and accept a `--json`
flag that switches to a single machine-readable JSON object suitable for piping
into other tools or scripts. `squire info` is the worked example: bare `info`
prints two readable lines, while `info --json` prints one JSON object with the
same fields. New commands that emit structured data should follow this pattern,
keeping the default output readable and gating the JSON form behind `--json`.
