---
layout: guide
title: The log view
description: Lines and columns, level colours, line numbers and marks, the highlight bar, the log's bar and status bar, JSON and CSV, the tree of loggers, bookmarks, and merged logs.
---

# The log view

![Logsift with four filters combined, the timeline on top and the highlight bar on the right](../screenshots/filters.png)

From top to bottom, a log tab has:

- **The log's bar:** **Find**, **Filters**, **Clear All** and the chips of the filters that are on, on the left; the line count, the format and the view buttons on the right.
- **The [timeline](timeline.md):** how much was logged over time.
- **The lines**, with line numbers on the left and the **highlight bar** on the right. A **tree of loggers** can sit on the left.
- **The bottom panel** with its tabs: Filters, Insights, Exceptions, Metrics, Profile (for CSV files), Find results, Messages, Bookmarks, Details (and Request once you follow one). **Ctrl+J** (⌘J), the ▾ button, or a double-click on a tab folds or opens it.
- **The status bar.**

## Level colours

Lines are coloured by their level word. The first rule that matches wins:

| Lines with | Light mode | Dark mode |
|---|---|---|
| `ERROR`, `FATAL`, `SEVERE`, `CRITICAL` | <span style="background:#FDE2E2;color:#9B1C1C;padding:0 4px">dark red text on pink</span> | <span style="background:#4A2327;color:#FFB4B4;padding:0 4px">pink text on dark red</span> |
| `WARN`, `WARNING` | <span style="background:#FFF3C4;color:#7A5300;padding:0 4px">brown text on pale yellow</span> | <span style="background:#413818;color:#F5D98B;padding:0 4px">yellow text on dark olive</span> |
| Stack trace lines (`at …`, `Caused by:`, `... 5 more`, `SomeException: …`) | <span style="color:#B4372F">red text</span> | <span style="background:#1E1F22;color:#E58C84;padding:0 4px">light red text</span> |
| `DEBUG`, `TRACE` | <span style="color:#8A8F98">gray text</span> | <span style="background:#1E1F22;color:#7C828A;padding:0 4px">gray text</span> |
| `INFO` and others | normal text | normal text |

In the plain view, only these words in capitals colour a line. A filter with a colour (see [Filters](filters.md)) replaces the level colour on the lines it matches.

In the **Columns** view the row keeps these colours, and the **Level** cell is coloured too: <span class="sw" style="background:#E5484D"></span> red for errors, <span class="sw" style="background:#C27C00"></span> dark amber for warnings, gray for debug and trace. There, more spellings count: `E`, `ERR`, `FTL`, `W`, `WRN`, `I`, `INF`, `D`, `DBG`, `T`, `TRC`, `NOTICE`, `FINE`, `FINER`, `FINEST` and so on.

**Grayed lines** (<span class="sw" style="background:#B4B8BE"></span> light gray, <span class="sw" style="background:#55595F"></span> in dark mode) are lines your filters leave out, in **All lines** mode, and context lines around matches. They keep their place so you can see what is around a match.

## Line numbers and marks

- Line numbers are gray; the current line's number is in normal text.
- <span class="sw" style="background:#3B82F6"></span> **A blue bar** at the right edge of the line numbers marks the current line (<span class="sw" style="background:#60A5FA"></span> lighter in dark mode). The current line has a <span class="sw" style="background:#EEF4FD"></span> pale blue background when nothing else colours it.
- <span class="sw" style="background:#3B82F6;border-radius:50%"></span> **A blue dot** by the number is a bookmark.
- **A small arrow** instead of a number: the line continues from the row above (with Wrap on).
- **A dashed line** across the view: lines were skipped there (Matching only with Context).
- **A coloured stripe** at the far left: the source of a line in a merged log.
- Every other line has a faint stripe. Turn it off with **View → Line Stripes** or in [Settings](settings.md).
- **Gray bars that pulse**, with *Reading from disk…*: lines are still being read (a slow or network drive).

## Selecting and copying

Drag to select. Double-click selects a word; triple-click, or a click on the line number, the whole line. Shift+click extends. **Ctrl+C** copies (with nothing selected, the current line). **Ctrl+A** selects all.

Right-click a line for: Copy, **Bookmark This Line**, **Follow** its request ID, **Open … in Editor** (on a stack line), **Show Thread "…" in Threads**, **Show Only Lines of Thread "…"**, **Open the Heap Dump** (on an out-of-memory line); with text selected, **Find All**, **Show / Hide / Mark lines with "…"**. In the Columns view, right-click a value for **Show Only Rows Where Host = web-1** and **Hide Rows Where Host = web-1** (see [Filters on a column](filters.md#filters-on-a-column)).

## The highlight bar

A narrow bar beside the scrollbar shows the whole file at a glance, in two lanes:

- Left lane: <span class="sw" style="background:#E5484D"></span> **red** marks for errors and <span class="sw" style="background:#F5A524"></span> **amber** marks for warnings (the same in both modes). Red wins where both are.
- Right lane: <span class="sw" style="background:#3B82F6"></span> blue for bookmarks, each Find All search in its colour, the find bar's matches in <span class="sw" style="background:#D4A800"></span> dark yellow, and each filter with a colour in that colour.
- A see-through box shows the part of the file on screen.

Click a mark to jump to its line; hover to see it. **F8** / **Shift+F8** go to the next or previous error. Only lines the view shows get marks.

## The log's bar

Left: **Find** (Ctrl+F), **Filters** (opens the Filters tab), **Clear All** (lit while anything is set: back to the whole log), the chips (see [Filters](filters.md)) and **Matching only**.

Right: the count (*1,234 of 50,000 lines*), then:

| Button | Does |
|---|---|
| **Format: …▾** | How the lines are read, for example *Format: CSV ▾* or *Format: logback / log4j ▾*. Click it to choose another format for this file (it is remembered), **Plain text**, **New Format from This Log…** or **Edit Log Formats…**. The same list is in **View → Log Format**. In a narrow window it shows just the name (*CSV ▾*); hover it for the whole name |
| Wrap | Wrap long lines (Alt+Z, ⌥Z) |
| Columns | Show lines as columns (Ctrl+Shift+K) |
| Tree | The tree of loggers (Ctrl+Shift+B) |
| Timeline | Show or hide the timeline |
| **Follow** | Follow the end of the file as it grows, like `tail -f` (Ctrl+T, or End) |
| **Reload** | Load what changed in the file (F5). It turns into an <span class="sw" style="background:#FFE08A"></span> amber pill, *Reload (+1.23 MB)* or *Reload (file replaced)*, when the file changed |
| ▾ | **Reload Automatically**: load changes as they happen. Follow turns it on |

These apply to this log, and your last choice becomes the default for the next log you open.

## The status bar

Left: the file (or `sftp user@host:/path`, or the web address), its size, the number of lines and how fast it was indexed. Then, when it applies: *• 1.2 MB more on disk: Reload (F5) to see it*, *• the file was replaced on disk*, or the state of a remote connection (*⚡ …*). A blue button appears when a new version of Logsift is out.

Right: *MATCHING ONLY*, the line number (*Ln 1,234*), the current line's time, *FOLLOW*, the text encoding, and the memory Logsift uses.

## Columns

**Columns** (View → Columns, Ctrl+Shift+K) shows lines as columns: Time, Level, Thread, Logger, Message, or whatever the format has (host, process, status, …). Stack traces show under the message.

![Parsed columns with top messages below](../screenshots/columns-and-messages.png)

- Drag a header edge to resize a column.
- Right-click a header: **Count Values in …** (every value with its number of lines; double-click one, or **Show Only**, to see its rows, or **Hide** them), **Filter by …** (a filter that looks only in that column, see [Filters on a column](filters.md#filters-on-a-column)), **Profile of the Columns** (the [Profile](profile.md) tab), **Hide … Column**, tick columns on and off, **Show All Columns**.
- Right-click a value: **Show Only Rows Where Host = web-1** or **Hide Rows Where Host = web-1**. *N columns hidden ▾* at the right of the header shows which; hidden columns are remembered for each format.
- When Logsift doesn't know the layout, it says *"Logsift doesn't know this log's layout, so it can't show it as columns"* and offers **Define Its Format…** (see [Settings](settings.md#log-formats)).

## JSON logs

Logs with one JSON object per line are recognized. With Columns on, the usual fields become columns (`@timestamp`/`timestamp`/`time`/`ts` → Time, `level`/`severity` → Level, `thread`, `logger`, `message`/`msg`) and the rest go together in a **Fields** column as `key=value`.

![JSON log shown as columns, with a pretty-printed details panel](../screenshots/json.png)

The **Details** tab shows the current entry in full (a line with its stack trace), or just the text you selected. Its **Text | JSON** switch pretty-prints the JSON in it, also JSON in the middle of a message and JSON written inside strings. JSON colours:

| Part | Light | Dark |
|---|---|---|
| Keys (bold) | <span class="sw" style="background:#1D4ED8"></span> blue | <span class="sw" style="background:#7FB4FF"></span> light blue |
| Strings | <span class="sw" style="background:#15803D"></span> green | <span class="sw" style="background:#98C379"></span> light green |
| Numbers | <span class="sw" style="background:#7E22CE"></span> purple | <span class="sw" style="background:#D19AFF"></span> light purple |
| `true`, `false`, `null` | <span class="sw" style="background:#C2410C"></span> orange | <span class="sw" style="background:#E5A062"></span> light orange |
| `{ } [ ] , :` and text around the JSON | gray | gray |

## CSV files

A `.csv` or `.tsv` file is read as a table: the header row names the columns, and rows are split by comma, semicolon, tab or `|`, with quoted fields handled. Columns named like a time, level, thread, logger or message (`timestamp`, `severity`, `msg`…) are used as such: the timeline uses the time column, and levels colour the rows. Other columns appear in the tree beside the log. Other files are read as CSV when they clearly are one, or choose **CSV** in **Format: …▾** in the log's bar.

- In the Columns view the header row isn't shown as a row: its names are the column titles. Line numbers are those of the file, so the first row is line 2. The titles are the header's names as written (*order_id*, *timestamp*), in the tree, the filters and their chips too. Logsift still knows which column holds the time, level, thread, logger or message (*timestamp*, *severity*, *msg*…): hover such a title to see it. The plain view shows the header line as it is.
- Right-click a value to show only (or hide) the rows with it, or a column title for **Filter by …**: see [Filters on a column](filters.md#filters-on-a-column).
- The [Profile](profile.md) tab shows what each column holds, with a chart per column. The **Messages** tab groups rows by the message column (a table without one has no messages to group). The [Metrics](metrics.md#columns) tab charts the columns of numbers, and how many rows each value of a column like *host* or *status* has over time.

![A CSV file from inside a zip, shown as columns, with its hosts in the tree](../screenshots/csv.png)

## The tree of loggers

Logs with a logger column (or class, component, thread, host…) can show a tree on the left (**Tree**, Ctrl+Shift+B). `com.acme.db.Pool` sits under **com.acme › db**, with line counts.

- Uncheck a branch to hide its lines and their stack traces. A half-checked box means part of a branch is hidden. Hidden names are grayed.
- Double-click a name (or right-click → **Show Only**) to see only that one; **Show all** brings everything back.
- The list at the top picks the column. **Filter the tree** finds a name.
- Hiding anything switches the log to Matching only, and adds a chip *Tree: N hidden*. What you hid is remembered for the file.

## Bookmarks

**Ctrl+F2** (⌘F2) bookmarks the current line, or right-click → **Bookmark This Line**. **F2** / **Shift+F2** go to the next or previous one. Bookmarks show as <span class="sw" style="background:#3B82F6;border-radius:50%"></span> blue dots by the line number and on the highlight bar.

The **Bookmarks** tab lists them: **Line**, **Time**, **Note** (type a note there) and **Text**. Double-click to go there; Delete removes one. Bookmarks are remembered per file, and dropped when the file was truncated (the lines would have moved).

## Merged logs

A log made with **File → Merge Files by Time…** starts each line with a label like `<node2-app>` and a coloured stripe at the far left. See [Compare and merge](compare.md#merge-logs-by-time). CSV files merge into one CSV with a Source column instead: see [Merge CSV files](compare.md#merge-csv-files).

## Text encodings

Logsift detects UTF-8 (with or without a byte order mark), UTF-16, and Windows-1252. The status bar shows the encoding. **View → Encoding** reopens the log in another one (Latin-1, Cyrillic, Shift_JIS, GB18030 and more). The encoding for text that isn't UTF-8 is set in [Settings](settings.md).

## Font and dark mode

**Ctrl+=** / **Ctrl+-** change the font size; **View → Dark Mode** switches the theme. The font itself is in [Settings](settings.md).

![Dark mode](../screenshots/dark-mode.png)
