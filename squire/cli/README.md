# squire/cli

The squire CLI and MCP server. Every capability is implemented once in
`squire/tools` as a kyo `ArrowEffect` and exposed twice from this module:
as a CLI subcommand (`squire ref repo <op>`) and as an MCP tool
(`ref-repo-<op>`).

See [CONTRIBUTING.md](CONTRIBUTING.md) for the step-by-step pattern for
adding a new tool.

---

## Tool catalog

| Tool | CLI command | MCP tool | Description |
|------|-------------|----------|-------------|
| ensure | `squire ref repo ensure` | `ref-repo-ensure` | Create `.ref/`, an empty manifest, and a `.gitignore` entry if absent |
| add | `squire ref repo add` | `ref-repo-add` | Clone and register a reference repository under `.ref/` |
| list | `squire ref repo list` | `ref-repo-list` | List all managed repositories from the manifest |
| context | `squire ref repo context` | `ref-repo-context` | Return an agent-friendly summary of available reference repositories |
| refs | `squire ref repo refs` | `ref-repo-refs` | List recent branches and tags for a repository by id or URL |
| update | `squire ref repo update` | `ref-repo-update` | Fetch and re-pin a managed repository to a new ref |
| remove | `squire ref repo remove` | `ref-repo-remove` | Remove a managed checkout and its manifest entry |
| purge | `squire ref repo purge` | `ref-repo-purge` | Delete all managed checkouts and clear the manifest |
| repair | `squire ref repo repair` | `ref-repo-repair` | Rebuild the manifest from git checkouts discovered under `.ref/` |

---

## reference-repo

Manages local reference repository checkouts under `.ref/` with a pinned
YAML manifest (`.ref/manifest.yaml`) and optional artifact hints. Use it to
explore upstream source, cross-reference a published library's implementation,
or give an agent stable on-disk context without hitting the network on every
request.

### CLI usage

Initialize the layout once per project, then add repos:

```bash
squire ref repo ensure
squire ref repo add https://github.com/getkyo/kyo --ref main
squire ref repo list
```

Example output of `list`:

```
getkyo/kyo  https://github.com/getkyo/kyo.git  main
```

Common follow-ups:

```bash
# Show what refs are available before pinning
squire ref repo refs getkyo/kyo

# Re-pin to a release tag
squire ref repo update getkyo/kyo --ref v0.15.0

# Remove one checkout
squire ref repo remove getkyo/kyo

# Rebuild the manifest from on-disk checkouts after a corrupted write
squire ref repo repair
```

### MCP usage

The same operations are available as MCP tools. Connect the squire MCP server
(`squire mcp`) and call tools over the JSON-RPC protocol.

Add a reference repository:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "ref-repo-add",
    "arguments": {
      "url": "https://github.com/getkyo/kyo",
      "ref": "main",
      "artifacts": ["io.getkyo:kyo-core_3"]
    }
  }
}
```

List registered repositories:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "ref-repo-list",
    "arguments": {}
  }
}
```

MCP input shapes for tools that take arguments:

| MCP tool | Input fields |
|----------|-------------|
| `ref-repo-add` | `url: String`, `ref?: String`, `artifacts?: String[]` |
| `ref-repo-refs` | `idOrUrl: String` |
| `ref-repo-update` | `id: String`, `ref?: String` |
| `ref-repo-remove` | `id: String` |

`ensure`, `list`, `context`, `purge`, and `repair` take no input (`{}`).
