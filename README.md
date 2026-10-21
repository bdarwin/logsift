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
| Go to line | Ctrl+L |
| Follow the end of the file (tail -f) | Ctrl+T, the Follow button, or End |
| Copy selected lines | Ctrl+C (Shift+click or drag to select several) |
| Font size | Ctrl+= / Ctrl+- |
| Dark mode | View → Toggle Dark Mode |

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

```
logmaster.core   (no UI code; could later back a VS Code extension or a CLI)
  FileSource      positional reads (pread), safe from many threads
  LineIndex       sparse index: chunks of 4 MB; offset of every 64th line
  LogDocument     open file + index + tail polling (growth, truncation, rotation)
  ByteScan        newline/byte search 8 bytes at a time (SWAR)
  LineMatcher     literal byte search, regex, or literal pre-filter + regex
  RequiredLiteral finds text every regex match must contain (ripgrep's trick)
  SearchSession   parallel Find All, hits published in line order, follows growth
logmaster.ui
  LogView         virtual view: paints only visible rows; scrollbar counts rows
  LogTab          log view + results panel for one file
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
- **Selection works on whole lines.** You can't select part of a line yet.
- **Highlight rules are built in** (ERROR/FATAL, WARN, stack traces, DEBUG/TRACE). There is no editor for them yet.
- **Very long lines:**
  - Lines over 64 KB are cut for display. Search still sees the whole line.
  - A single line over 256 MB is only searched in its first 256 MB.
- **Wide CJK characters:** the highlight boxes can be slightly off on lines that contain them.
- **Tested only on macOS so far.** The code is cross-platform. On Windows, check that log rotation works while a file is open: Java opens files with delete-sharing, but older Windows versions can still keep a deleted file's name reserved.

## Roadmap

1. ✅ Open, scroll and jump in huge files; Find All with a results panel; tail with live search; highlighting
2. Folder search (folder search tools-like): folder + glob, parallel over files, `.gz`/`.zip`, results tree
3. Log formats (log analyzers-like): pattern → columns (time, level, thread, logger, message), multi-line entries, filter by level and time, merge files by timestamp
4. Editable highlight rules, saved searches, bookmarks, recent files, filter view ("show only matching")
5. Native installers with `jpackage` (Windows .msi, macOS .dmg, Linux .deb/.rpm)
