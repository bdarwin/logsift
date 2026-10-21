# LogMaster

A fast, cross-platform desktop viewer for very large log files (Java 21+, Swing + FlatLaf).

The goal is one tool that combines:

- **live tail viewers**: live follow and line coloring
- **text editors**: Find All in the current document
- **folder search tools**: search across folders
- **log analyzers**: parsed columns and filters

**Status (v0.1):** step 1 of the plan is done. You can open huge files, scroll, jump to any line, use Find All with a results panel, follow the file as it grows (handling rotation and truncation), color lines, and switch between light and dark mode. Folder search and column parsing come next (see *Roadmap* below).

## Download

Snapshot builds of the latest `main` are on the [snapshot release](https://github.com/bdarwin/logmaster/releases/tag/snapshot): Windows (x64, arm64), macOS (Apple Silicon, Intel), Linux (x64 .deb/.tar.gz, arm64 .tar.gz), and a plain zip for any OS with Java 21+. The native builds include their own Java runtime and are not code-signed (see the release notes for the macOS/Windows warnings).

## Run it

```bash
./gradlew run --args="/path/to/app.log"     # or open files via File → Open / drag and drop
./gradlew installDist                       # launcher in build/install/logmaster/bin/logmaster
```

Make a test log:

```bash
./gradlew genLog -Pout=/tmp/sample.log -PsizeMb=500             # 500 MB realistic log
./gradlew genLog -Pout=/tmp/live.log -PsizeMb=5 -Pfollow=2000   # keeps appending 2000 lines/s
```

## Using it

| Action | Shortcut (macOS uses ⌘ in place of Ctrl) |
|---|---|
| Open files (or drag files onto the window) | Ctrl+O |
| Find All (results panel opens below) | type in the Find box, Enter |
| Match case / whole word / regex | `Aa` `W` `.*` buttons inside the Find box |
| Next / previous hit | F3 / Shift+F3 (also ⌘G / ⇧⌘G on macOS), or ▲ ▼ |
| Stop a running search | Esc in the Find box |
| Close results | Shift+Esc |
| Jump from a result to the line | click it, or move with the arrow keys in the results |
| Wrap long lines | Alt+Z (⌥Z on macOS), or the Wrap button |
| Next / previous error | F8 / Shift+F8 |
| Bookmark the current line | Ctrl+F2 (⌘F2 on macOS) |
| Next / previous bookmark | F2 / Shift+F2 |
| Search in a folder | Ctrl+Shift+F (⇧⌘F on macOS) |
| Merge logs by time | Ctrl+Shift+M |
| Columns view | Ctrl+Shift+K |
| Export lines shown by filters | Ctrl+Shift+E |
| Go to line | Ctrl+L |
| Go to time | Ctrl+Shift+L |
| Matching lines only / all lines | Ctrl+Shift+H (⇧⌘H on macOS) |
| Show or hide the Filters panel | Ctrl+J |
| Follow the end of the file (tail -f) | Ctrl+T, the Follow button, or End |
| Select text | drag; double-click = word, triple-click or click the line number = whole line; Shift+click extends |
| Copy | Ctrl+C (with nothing selected, copies the current line) · Select all: Ctrl+A |
| Search for the selected text | Ctrl+F (fills the Find box, like text editors) |
| Font size | Ctrl+= / Ctrl+- |
| Dark mode | View → Toggle Dark Mode |

## Folder search

**File → Search in Folder…** (Ctrl+Shift+F, ⇧⌘F on macOS), or drop a folder on the window, opens a search tab like folder search tools:

- Pick a folder, file name patterns (`*.log;*.txt`), and whether to include subfolders and archives.
- Rotated logs count as the log they came from: `*.log` also finds `app.log.1`, `app.log.2026-10-21`, `app.log.2.gz`, and `.log` files inside any `.zip`.
- All files are searched in parallel with the same fast search as Find All. Binary files are skipped. Results are grouped by file and appear while the search runs.
- Double-click a line to open the file at that line, with Find All already run there. Files from `.gz` or `.zip` are unpacked to a temporary file first. Double-click a file name to fold it.

## Timeline

When a log has timestamps, a timeline above it shows how much was logged over time, with **errors in red and warnings in amber**, so incidents stand out at a glance. The box shows which part of the file you are looking at.

- **Click** the timeline to jump to that time. **Edit → Go to Time…** (Ctrl+Shift+L) takes `10:05`, `10:05:30.250` or `2026-10-21 10:05`.
- **Drag** across it to show only that time range. The range works with the filters: drag over an incident, add an ERROR filter, and you see just the errors of the incident. Click ✕ on the range to show all times again.
- The status bar shows the time of the current line.
- Recognized formats: `2026-10-21 10:02:13`, ISO with `T`, `2026/10/21`, `21-10-2026`, `21.10.2026`, `21-Oct-2026`, syslog `Oct 21 10:02:13`, Apache `[21/Oct/2026:10:02:13 +0000]`, timestamps inside JSON lines, time only, and epoch seconds or milliseconds. Times are shown as written in the log (time zones are not converted).

## Bookmarks

**Ctrl+F2** (⌘F2 on macOS) bookmarks the current line (or right-click → *Bookmark This Line*); **F2 / Shift+F2** jump to the next / previous bookmark. Bookmarks show as blue dots by the line number and on the highlight bar. The **Bookmarks** tab lists them with their time and text; type a note in the Note column, double-click to go there, Delete to remove. Bookmarks are remembered per file, and dropped if the file was truncated (the lines would have moved).

## Recent files and sessions

**File → Open Recent** lists the last 15 files. When LogMaster starts without a file to open, it reopens the logs that were open last time (**File → Reopen Last Session** does it any time). Every file remembers its filters, formula, level chips, *Matching only* and position, so reopening a log puts you back where you were; if the file got smaller in the meantime (truncated or rotated), it starts fresh.

## Export

**File → Export** saves lines to a new file: the **lines shown by filters** (Ctrl+Shift+E), the **Find results**, the **selected lines**, or **everything in the view**. The original bytes are copied (line endings and encoding unchanged), in runs of consecutive lines, so even large exports take seconds.

## Columns and levels

The **Columns** button (or View → Columns, Ctrl+Shift+K) shows lines as columns, like log analyzers: **Time, Level, Thread, Logger, Message**, with the level colored and stack traces under the message. LogMaster recognizes common layouts by itself: logback and log4j patterns, Python logging, syslog (host, process, pid), web server access logs (client, user, request, status, size), "time level message", and merged logs (a Source column). Drag a header edge to resize a column.

- **Count values:** right-click a column header (Thread, Logger, Host, Status…) → *Count Values* lists every value with its number of lines. Double-click one to show only its lines, or *Hide* it.
- **Level chips** in the Filters bar (**E W I D T**) show only some levels, together with your other filters: turn off I, D and T to see just errors and warnings, stack traces included.

## Merge logs by time

**File → Merge Files by Time…** (Ctrl+Shift+M) merges several logs (say, the same app on two nodes, or an app log and its server log) into one view in timestamp order. Each line starts with a label like `<node2-app>` and a colored stripe, so you can see where it came from; right-click → **Show Only Lines from <node2-app>** filters to one source. Stack traces stay with their log line. The merged view is an ordinary log, so filters, the timeline, messages and search all work on it, and it keeps updating while the source logs grow.

## Top messages

The **Messages** tab (next to Filters) groups the log's lines into kinds of messages and counts them, most frequent first. Variable parts (numbers, IDs, hosts, durations, user names) are shown as ∗, so `ERROR [worker-7] Database connection lost after 26579 ms` and `ERROR [worker-2] Database connection lost after 812 ms` count as one message. Stack traces count with their log line.

- Use it to answer "what is actually going wrong?" in seconds: pick **Errors** in the level list.
- **Double-click** a message to find all its lines (and jump to the first one).
- **Right-click → Hide these lines** to remove noisy messages, or **Show only these lines**. Select several messages to hide them all at once.
- **Lines shown by filters** counts only the lines your filters and time range show.
- It is fast: 1.56 million entries are grouped in about 0.25 s.

## Highlight bar

A narrow bar beside the scrollbar shows the whole file at a glance, like log analyzers. It has red marks for errors (ERROR, FATAL, SEVERE, CRITICAL), amber for warnings, a second lane for Find All hits and colored filters, and a box showing where you are. Click a mark to jump to that line; hover to see it. F8 / Shift+F8 jump to the next or previous error. The marks stay up to date while the file grows.

## Filters

The **Filters** panel below the log holds any number of filters (up to 26). Each one has:

- a letter (A, B, C, …) for use in the formula
- an action: **Show** keeps matching lines, **Hide** removes them, **Mark** only colors them
- the text to match, with the same `Aa` / `W` / `.*` options as Find
- a line color, a live count of matching lines, and ▲ ▼ to step through its matches

**Combine** says how filters work together. In **Auto** mode it is "any Show filter, and no Hide filter", for example `(A OR B) AND NOT C`. Type your own to get anything else: `A AND B`, `(A OR B) AND NOT (C OR D)`, `NOT C`. `AND OR NOT ( )` work, and so do `& | ! -`.

**All lines / Matching only** (Ctrl+Shift+H, ⇧⌘H on macOS) switches between every line, with filtered-out lines grayed, and only the lines that pass. Your place in the file is kept when you switch, so you can filter down, click a line, switch back and see everything around it.

**Keep stack traces** (on by default) treats a log line and the lines under it (stack traces, wrapped messages) as one entry: if any line of the entry matches, the whole entry is shown, and Hide filters hide whole entries. LogMaster detects how entries start from the first lines of the file (a date, a time, a syslog date, a level word, or `{` for JSON lines); the checkbox tooltip shows what it found. **Context ±N** adds N grayed lines before and after each match, with a dashed line where lines were skipped, like `grep -C`.

**Alerts:** click the bell on a filter to be told when new lines matching it are written (while the file grows). LogMaster shows a desktop notification and a popup you can click to jump to the line, beeps, and puts a count on the tab. Bursts are combined (at most one alert per filter every 3 seconds). Lines that were already in the file don't alert.

Quick ways to add filters: select text in the log, right-click, then choose **Show / Hide / Mark lines with "…"**. Filter sets can be saved and loaded (the ⋯ menu), and filters keep working while the file grows.

Find All keeps running on a growing file. New matching lines show up in the results as they are written.

## Measured speed

These numbers come from `./gradlew visualTest` on an Apple Silicon Mac (10 cores, 32 GB RAM). The file had just been generated, so it was already in the OS file cache. With a cold cache, the disk speed sets the limit.

| | 1 GB, 9.4M lines | 3 GB, 28.1M lines |
|---|---|---|
| First lines on screen | 0.12 s | 0.22 s |
| Full line index built | 0.26 s | 1.1 s |
| Jump to any line + repaint | ~10 ms | ~6 ms |
| Find All `ERROR` (literal, 335k / 1M hits) | 0.17 s | 0.58 s |
| Find All `WARN` (whole word) | 0.15 s | 0.38 s |
| Find All `müller` (ignore case, non-ASCII) | 0.19 s | 0.45 s |
| Find All regex `Payment failed .* user=u12\d4 ` | 0.12 s | 0.37 s |

Filters on a 200 MB log (1.8M lines): four filters with formula `(A OR B) AND NOT C` take 0.2 s. Changing only the formula takes about 2 ms, because the filters' results are kept and only merged again.

Heap use stays at a few hundred MB for these sizes. The index costs about 12 MB per 100M lines.

## Tests

```bash
./gradlew test                       # fast unit tests (index, tail, search vs simple reference code)
./gradlew visualTest                 # opens a generated 1 GB log in the real UI
./gradlew visualTest -PsizeMb=3072   # same with 3 GB
```

`visualTest` generates `build/visual/big-<N>mb.log` (and reuses it on later runs). It then drives the real window and checks the following against independent, plain-Java reference code:

- the line count
- 25 random lines read straight from the file
- hit counts for literal, whole-word, Unicode and regex searches
- F3 and Shift+F3 navigation
- live search while 10k lines are being appended
- log rotation

It saves screenshots and a timing summary to `build/screenshots/`. Delete `build/visual` when you need the disk space back.

## How it works

For the full, educational explanation (sparse index, SWAR byte search, the regex pre-filter, filter merging, tail and rotation, and lessons learned), see **[docs/how-it-works.md](docs/how-it-works.md)**. Short version:

```
logmaster.core   (no UI code; could later back a VS Code extension or a CLI)
  FileSource      positional reads (pread), safe from many threads
  LineIndex       sparse index: chunks of 4 MB; offset of every 64th line
  LogDocument     open file + index + tail polling (growth, truncation, rotation)
  ByteScan        newline/byte search 8 bytes at a time (SWAR)
  LineMatcher     literal byte search, regex, or literal pre-filter + regex
  RequiredLiteral finds text every regex match must contain (ripgrep's trick)
  SearchSession   parallel Find All, hits published in line order, follows growth
  Formula         AND / OR / NOT of filter letters (parser and auto formula)
  FilterEngine    one live search per filter; merges their sorted hits by the formula
  FilterResult    the passing lines: a sorted list, or "all lines except a list"
  FolderSearch    parallel search of a folder's files, streaming; .gz and .zip inside
logmaster.ui
  LogView         virtual view: paints only visible rows; scrollbar counts rows
  LogTab          log view + Filters and Find results panels for one file
  FilterPanel     the filter rows, formula field and All lines / Matching only switch
  OverviewBar     highlight bar beside the scrollbar (errors, warnings, filters, hits)
  FolderSearchTab folder search form and results grouped by file
  MainFrame       window, toolbar, menus, status bar, drag & drop
logmaster.tools
  LogGenerator    realistic reproducible logs (levels, threads, stack traces, Unicode)
```

Design choices:

- **No memory mapping.** On Windows, a memory-mapped file can't be renamed or deleted, which breaks log rotation for the app that writes it. Mappings are also limited to 2 GB each. Positional reads through the OS cache are nearly as fast.
- **Sparse index.** It stores the position of every 64th line per 4 MB chunk. Chunks are scanned in parallel and published in order, so the top of the file shows up immediately. When following a growing file, a short last chunk is re-scanned together with the new data.
- **Custom view instead of JList/JTable.** Swing's own lists measure in pixels and overflow at around 100M rows. LogView's scrollbar counts rows, which supports up to about 2.1 billion lines.
- **Search never decodes lines unless it must.** Plain text is matched on raw UTF-8 bytes. A regex runs only on lines that contain its required text, when there is one.
- **Tail detection works on every platform.** It polls the file every 500 ms. It detects rotation by a changed file ID (inode), and on Windows by a smaller file appearing at the same path. It detects truncation by a smaller size or changed first 256 bytes. The view and the running search restart automatically.

## Known limits

- **Only UTF-8 (and ASCII) files are decoded correctly.** UTF-16 and other encodings are not detected yet.
- **Highlight rules are built in** (ERROR/FATAL, WARN, stack traces, DEBUG/TRACE). There is no editor for them yet.
- **Very long lines:**
  - Lines over 64 KB are cut for display. Search still sees the whole line.
  - A single line over 256 MB is only searched in its first 256 MB.
- **Wide CJK characters:** the highlight boxes can be slightly off on lines that contain them.
- **Tested only on macOS so far.** The code is cross-platform. On Windows, check that log rotation works while a file is open: Java opens files with delete-sharing, but older Windows versions can still keep a deleted file's name reserved.

## Roadmap

1. ✅ Open, scroll and jump in huge files; Find All with a results panel; tail with live search; highlighting
1. ✅ Text selection; multiple filters (Show / Hide / Mark) combined with AND / OR / NOT; highlight bar
2. ✅ Folder search (folder search tools-like): folder + file patterns, parallel over files, `.gz`/`.zip`, results by file
2. ✅ Text wrap; keep stack traces with their log line when filtering; context lines (±N)
3. ✅ Timeline, top messages, merge by timestamp, columns (log analyzers-like) with level chips and value counts
4. Editable highlight rules, saved searches, bookmarks, recent files, filter view ("show only matching")
5. Native installers with `jpackage` (Windows .msi, macOS .dmg, Linux .deb/.rpm)
