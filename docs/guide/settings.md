---
layout: guide
title: Settings
description: Every Logsift setting with its default, log formats, source folders, and where Logsift keeps its files.
---

# Settings

**Edit → Settings…** (Ctrl+,), or **Logsift → Settings** on macOS (⌘,). Changes apply at once. Four tabs: **General**, **Log Formats**, **Source Code**, **Agents**.

## General

| Setting | Default | What it does |
|---|---|---|
| **Font** and size | Automatic (the first installed of JetBrains Mono, Cascadia Mono, Consolas, Menlo, SF Mono, DejaVu Sans Mono, Liberation Mono, Ubuntu Mono), 13 | The font of logs. Ctrl+= / Ctrl+- also change the size |
| **Tab width** | 4 | How wide a tab character is |
| **Stripe every other line** | on | A faint stripe on every other line (also View → Line Stripes) |
| **Show the timeline** | on | The [timeline](timeline.md) above logs |
| **Dark mode** | off | Also View → Dark Mode |
| **Text that isn't UTF-8** | Windows-1252 | How to read files that aren't UTF-8 or UTF-16 (Latin-1, Cyrillic, Shift_JIS, EUC-JP, GB18030, Big5, EUC-KR, KOI8-R…). View → Encoding changes it for one file |
| **Check files for changes** | Twice a second | Less often uses less power with many open files, or files on network drives |
| **At start: Reopen the files that were open last time** | on | Reopens the last session when Logsift starts without a file |
| **Check for new versions once a day** | on | Asks GitHub for the latest version number only; nothing about you or your logs is sent. A button appears in the status bar when a new version is out |

## Log formats

When Logsift doesn't know a log's layout, the Columns view says so and offers **Define Its Format…**. Formats are also under **View → Log Format → New Format from This Log…**. There are three ways to make one:

1. **Drop a log file here** (or **Open a Log File…**). Logsift guesses the layout from its lines (time, level, `[thread]`, logger, separators) and how its times are written.
2. **Or paste how your program logs**, then **Create Format**: a log4j, Log4j 2 or logback pattern such as `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`, a whole `logback.xml`, `log4j2.xml` or `log4j.properties`, Spring Boot's layout, a java.util.logging `SimpleFormatter.format`, or a Python logging format, `logging.conf` or `basicConfig(…)`. When a config has several layouts, each becomes a format.
3. **Write a Pattern Yourself…:** `{time} {level} [{thread}] {logger} - {message}`. Columns go in braces; everything else is matched as written. `{*}` skips a part. A regex with named groups like `(?<level>…)` works too.

Each format has a **Name**, a **Line pattern**, a **Time format** (empty for any common timestamp), and **Always use for** (file names like `app*.log; *.trace`). A preview shows sample lines split into columns.

A format is used when most of a log's lines fit it, or always for the file names you give. The log's bar shows the format each log is read with (**Format: …▾**, on the right); click it to choose another for that file, or **Plain text**. Levels in your format's level column drive the colours, the error marks and the E W I D T buttons.

Formats are saved in `~/.logsift/formats.properties`; copy it to use them on another computer.

## Source code

For opening a stack frame in your editor (see [Exceptions](exceptions.md#opening-your-source)):

- **Source folders:** your projects' source code. **Add Folder…**, **Remove**.
- **Open with:** *Automatic* (IntelliJ IDEA or PyCharm when installed, else Visual Studio Code), IntelliJ IDEA, PyCharm, Visual Studio Code, *The system's app for the file (without the line)*, or *My own command*.
- **Command** (for *My own command*): for example `myeditor --goto {file}:{line}`. `{file}` and `{line}` are filled in; put quotes around a path with spaces.

## Other settings in the menus

- **Help → Check for Updates Automatically**: the same as the update setting above.
- **View → Encoding**: reopens one log in another encoding.
- **View → Log Format**: the format for one log.
- Each log's view buttons (Wrap, Columns, Tree, Timeline, Follow, Reload Automatically) are remembered as the default for the next log you open.

## Where Logsift keeps its files

In `~/.logsift` (your home folder):

| File or folder | Holds |
|---|---|
| `formats.properties` | Your log formats |
| `files/` | What each log remembers: filters, position, view |
| `bookmarks/` | Bookmarks and their notes |
| `errors.log` | Unexpected errors (Help → Open Error Log) |
| `stdin/` | Piped output, kept a week |
| `heap-index/` | Indexes of big heap dumps, removed when their tab closes |
| `agent.properties` | Settings → Agents: what is masked, which folders agents may read |
| `agent.log` | Each call coding agents made (never the answers) |

Other preferences (window size, recent files, last folders, servers and users, never passwords) are kept in Java's preferences store for your user.

Logsift never sends your logs anywhere. Its only network connections are the daily update check (if on) and the servers you choose: SSH servers, JMX ports, and web addresses you open. When you set up a [coding agent](agents.md), what Logsift answers it goes to the agent's AI provider.

## Agents

How to let a coding agent such as GitHub Copilot ask Logsift about your logs, what is masked in its answers, and which folders it may read. See [Coding agents](agents.md).
