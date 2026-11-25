# logsift-mcp

[Logsift](https://bdarwin.github.io/logsift)'s tools for AI agents, as an MCP server over standard input and output. It reads logs, CSV/TSV tables, Java GC logs, thread dumps, heap dumps, profiles and crash files on your computer, fast even when they're gigabytes, and answers with short summaries and line numbers.

```json
{
  "servers": {
    "logsift": { "command": "npx", "args": ["-y", "logsift-mcp"] }
  }
}
```

The first start downloads Logsift for your system from its [GitHub release](https://github.com/bdarwin/logsift/releases) (about 150 MB, Java included), checks it against the release's SHA-256 sums and keeps it in `~/.logsift/mcp-runtime`. To download it before your agent's first start:

```sh
npx -y logsift-mcp --version
```

Behind an office proxy, set `HTTPS_PROXY`.

## What agents can do

The `guide` tool tells an agent which tool to use for which question, with an example call for every tool. Agents can:

- search and filter logs;
- query them with SQL;
- follow a request across logs;
- compare runs, files and folders;
- explain crashes, GC logs, thread dumps, heap dumps and profiles.

By default agents only read, inside your home folder, and secrets are masked. The options:

- `--root FOLDER` sets the folders agents may read.
- `--allow-changes` lets them save new files, bookmarks and log formats. They never replace or delete files.

`npx -y logsift-mcp --help` lists every option.

More: [Coding agents](https://bdarwin.github.io/logsift/guide/agents.html).
