---
layout: guide
title: Coding agents
description: Letting GitHub Copilot and other coding agents ask Logsift about your logs, what they can and can't do, and what is masked.
---

# Coding agents

A coding agent such as GitHub Copilot can ask Logsift about your logs. You ask in the agent's chat, for example *"why is orders.log full of timeouts?"*. The agent calls Logsift, and Logsift reads the file on your computer and answers with short summaries and line numbers, never whole files. The agent can then search and read the lines around what it found.

It works with GitHub Copilot in VS Code, Visual Studio and JetBrains IDEs, with Copilot CLI, and with any other agent that supports MCP (the Model Context Protocol).

## Setting it up

1. Open **Settings → Agents**.
2. Choose your agent in the **Agent** list. Under it, Logsift says where the setup goes and shows it, with the path of `logsift-mcp` on this computer filled in.
3. Click **Copy** and paste it into the agent's MCP settings. Merge it with what is already there.
4. VS Code: choose **Agent** mode in Copilot Chat. Logsift's tools are under the tools button. Copilot CLI: start `copilot` again.

`logsift-mcp` comes with Logsift:

| System | Where |
|---|---|
| Windows | next to `Logsift.exe` |
| macOS | `Logsift.app/Contents/MacOS/logsift-mcp` |
| Linux | `bin/logsift-mcp` |
| The zip for any Java | `bin/logsift-mcp` |

A Logsift run from a source build has no `logsift-mcp`, so the tab says so and **Copy** is off.

Other agents: add a local (stdio) MCP server called `logsift` that runs `logsift-mcp`, with no arguments.

## What the agent can ask

| Tool | What it does |
|---|---|
| **open_log** | Opens a log (also a `.gz` file, or a file inside a zip) and gives back a short name for it (`log:1`), its format, size, line count and time range |
| **list_open** | The logs open so far |
| **insights** | What happened in the log, most important first (error bursts, new errors, restarts, known problems) |
| **exceptions** | Exceptions grouped by root cause, with counts and where they first and last appear |
| **search** | Lines that match a text or regex, in a range of lines if asked |
| **read_lines** | Lines from a line number on |
| **top_messages** | The most frequent messages, with numbers replaced by placeholders |
| **metrics** | How long things took, and numbers in messages |
| **search_folder** | Every file in a folder that contains a text, inside zips and `.gz` files too |
| **analyze_gc** | Explains a GC log: pauses, time stopped, heap after collections, and what to tune |
| **analyze_threads** | Explains thread dumps: deadlocks, lock queues, stuck and busy threads |

Line numbers in answers start at 1, like in the log view. Times are the log's own times.

Long answers are cut, and say where. Slow work, such as a first look at a multi-GB log, goes on in the background. The agent is told to ask again in a few seconds.

## What it can't do

- Change, move or delete any file.
- Run commands, connect to servers, or touch a running JVM.
- Read outside your home folder, or outside the folders listed under **Folders agents may read**. The current folder also counts when it is outside your home folder.
- Read files that hold keys or passwords, even through a link:
  - `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.kube`, `~/.docker`;
  - `.env` files, `credentials`, `id_rsa`;
  - `*.pem`, `*.key`, `*.p12` and other key stores.

## What is masked

Everything Logsift answers goes to the agent's AI provider, under your agreement with them. Before an answer leaves Logsift, it replaces what you chose to mask with `‹masked:…›`, for example `token=‹masked:secret›`, and says how many values it masked.

| Setting | Default | What it masks |
|---|---|---|
| **Secrets** | on | Passwords and secrets in `key=value` pairs, bearer tokens, JWTs, API keys (AWS, GitHub, Slack, Google, OpenAI, Anthropic and similar), passwords in web addresses, private keys, card numbers |
| **Email addresses** | off | `name@example.com` |
| **IP addresses** | off | IPv4 and IPv6 addresses |
| **Your own patterns** | none | One regular expression per line, for example customer numbers or host names. A pattern with a mistake is shown in red under the list and isn't used |

Changes apply the next time the agent starts Logsift. Agents usually start it when you open a new chat or restart the agent.

Text from logs is passed on as data. Logsift tells the agent never to follow instructions found in a log.

## What agents asked

**What Agents Asked…** opens `~/.logsift/agent.log`. Each line is one call, with:
- the time;
- the agent;
- the tool and its arguments, masked;
- how long the answer was;
- the outcome: *ok*, *still working*, *refused*, *cancelled* or *failed*.

The answers themselves are never kept.

## From a terminal

`logsift-mcp --help` lists the options. The command line wins over Settings → Agents.

| Option | What it does |
|---|---|
| `--root FOLDER` | Allow reading under this folder (can be given several times) |
| `--mask-emails`, `--mask-ips` | Also mask email addresses or IP addresses |
| `--mask REGEX` | Also mask your own pattern |
| `--no-mask` | Don't mask secrets |
| `--max-chars N` | The longest answer, in characters |
| `--version` | Print the version |

*GitHub Copilot is a trademark of GitHub, Inc. Logsift is not affiliated with or endorsed by GitHub.*
