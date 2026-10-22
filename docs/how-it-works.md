# How Logsift works

Logsift opens multi-gigabyte logs in a fraction of a second, scrolls them smoothly, searches them at disk speed, and keeps up while they grow. None of this needs special hardware or native code. It comes from a handful of simple ideas applied carefully. This document explains those ideas, with the reasons for each choice and the mistakes that taught us something.

Code references point to `src/main/java/logsift/...`.

---

## Contents

1. [The big picture](#1-the-big-picture)
2. [Reading the file: positional reads, not memory mapping](#2-reading-the-file-positional-reads-not-memory-mapping)
3. [The sparse line index](#3-the-sparse-line-index)
4. [Finding bytes 8 at a time (SWAR)](#4-finding-bytes-8-at-a-time-swar)
5. [Reading lines: the page cache](#5-reading-lines-the-page-cache)
6. [Following a growing file (tail)](#6-following-a-growing-file-tail)
7. [Search](#7-search)
8. [Filters: combining searches with AND / OR / NOT](#8-filters-combining-searches-with-and--or--not)
9. [The view: drawing only what is visible](#9-the-view-drawing-only-what-is-visible)
10. [Folder search](#10-folder-search)
11. [More features, and the idea behind each](#11-more-features-and-the-idea-behind-each)
12. [Java threads and profiling](#12-java-threads-and-profiling)
13. [Lessons learned](#13-lessons-learned)
14. [Numbers](#14-numbers)

---

## 1. The big picture

```
                 ┌────────────────────────── logsift.core (no UI code) ──────────────────────────┐
  log file ──►   │ FileSource ─► LineIndex ─► LogDocument ─► SearchSession ─► FilterEngine          │
  on disk        │ (pread)       (sparse      (lines, tail,  (Find All, live)   (formula over        │
                 │                index)       rotation)                         several searches)  │
                 │ ByteScan (SWAR byte search)   LineMatcher / RequiredLiteral (what to match)      │
                 │ FolderSearch (streams many files, .gz/.zip)                                      │
                 └──────────────────────────────────────────────────────────────────────────────────┘
                 ┌────────────────────────── logsift.ui (Swing + FlatLaf) ───────────────────────┐
                 │ LogView (virtual list) · OverviewBar (highlight bar) · LogTab · FilterPanel      │
                 │ FolderSearchTab · MainFrame                                                      │
                 └──────────────────────────────────────────────────────────────────────────────────┘
```

Three rules shape everything:

1. **Never load the file.** Keep a small index, and read bytes only for what is on screen or being searched.
2. **Work on raw bytes.** Find newlines and search text on raw UTF-8 bytes. Turn bytes into Java `String`s only for the few lines that are drawn, or that a regex must check.
3. **Use every core, but publish in order.** Index and search pieces of the file in parallel, and hand the results out in file order, so the UI can show the top while the rest is still being processed.

The `core` package has no UI code, so the same engine could later sit behind a command-line tool or a VS Code extension.

---

## 2. Reading the file: positional reads, not memory mapping

`core/FileSource.java`

Memory mapping (`FileChannel.map`) is the usual answer to "read a huge file fast". Logsift deliberately doesn't use it:

| Problem with memory mapping | Why it matters for a log viewer |
|---|---|
| On **Windows**, a file that is mapped cannot be renamed or deleted | The logging framework of the app that writes the log (logback, log4j…) rotates by **renaming** the file. A viewer holding a mapping would break the app's log rotation. |
| A Java mapping is at most **2 GB** | Big files would need many mappings and logic to stitch them together. |
| Java offers **no way to unmap** (the GC decides when) | You cannot reliably let go of a rotated file. |

Instead, `FileSource` uses **positional reads** (`FileChannel.read(buffer, position)`, the `pread` system call). They:

- go through the OS file cache, so a second read of the same region costs about as much as a memory copy;
- are **safe from many threads at once**, because each read carries its own position and there's no shared file pointer;
- keep the file open with Java's default sharing flags on Windows (`FILE_SHARE_READ | WRITE | DELETE`), so the writing app can still rename or delete it.

A test (`LogbackRotationTest`) proves the point. Real logback rotates `app.log` about 18 times in one second while Logsift has it open and is reading it. logback must never report a failed rename or delete.

---

## 3. The sparse line index

`core/LineIndex.java`

A viewer needs to answer one question very quickly: **at which byte does line N start?** Everything else (scrolling, "go to line", showing search hits) builds on that.

### The naive way, and why it doesn't scale

Store the start offset of every line in a `long[]`:

```
100 million lines × 8 bytes = 800 MB of index   ✗
```

That's often more memory than the machine wants to give a viewer, and building the array is slow.

### Store every 64th line, and scan for the rest

The file is cut into **chunks of 4 MB**. For each chunk Logsift records:

```java
record Chunk(long startByte, long endByte,   // the bytes this chunk covers
             long firstLine, int lineCount,  // global number of its first line, how many lines start in it
             long[] checkpoints,             // byte offset of local lines 0, 64, 128, ...
             boolean endsWithNewline)
```

```
 file:  |<------------ chunk 0 (4 MB) ------------>|<------------ chunk 1 (4 MB) ------------>| ...
 lines:  0  1  2 ... 63  64 ... 127  128 ...         firstLine = 41,230
 checkpoints:  ^0           ^64          ^128           ^41,230      ^41,294 ...
               (byte offsets of every 64th line)
```

**To find line N:**

1. **Binary search** the chunks for the last one with `firstLine ≤ N`. There are only a few hundred chunks per GB.
2. Take the checkpoint at or before N: `checkpoints[(N - firstLine) / 64]`.
3. **Scan forward** past at most 63 newlines to reach N.

Step 3 reads at most a few kilobytes, usually from the page cache (section 5), so it costs microseconds.

**Memory:** one `long` per 64 lines.

```
100 million lines ÷ 64 × 8 bytes ≈ 12.5 MB      (instead of 800 MB)
```

64 balances the two costs. A smaller number means more memory; a bigger one means a longer forward scan on every lookup.

### Building it in parallel, publishing in order

```
 worker 1:  [scan chunk 0]          [scan chunk 4] ...
 worker 2:  [scan chunk 1 ]       [scan chunk 5] ...
 worker 3:  [scan chunk 2   ]   ...
 worker 4:  [scan chunk 3]  ...
                │   │   │   │
 indexer:   publish 0 → 1 → 2 → 3 → ...   (strictly in file order)
```

- Chunks are scanned by a thread pool (one thread per CPU core). A chunk on its own can't know its global line numbers, because that depends on how many lines all earlier chunks have. So each worker counts lines **locally**, and the single indexer thread fixes up `firstLine` as it **publishes chunks in order**.
- At most `2 × cores` chunks are in flight, so memory stays small even for a 50 GB file.
- After each publish the UI can show more of the file. The **top of the file is on screen after the first chunk**, about 0.1 s, long before the index is complete.

### Immutable snapshots: many readers, one writer, no locks

Every publish creates a new `Snapshot` object: the chunk array plus a count. A snapshot never changes. The view, the searches and the highlight bar each grab the current snapshot and work with it, while the indexer builds the next one. There are no locks on the read path and no torn reads.

### A detail: line starts at chunk boundaries

A line starts at byte 0 and **after every newline**. When a chunk `[a, b)` is scanned, it reads one extra byte, `a − 1`, because a newline exactly at `a − 1` means a line starts at `a`, which belongs to this chunk. Symmetrically, a newline at `b − 1` starts a line in the *next* chunk, so the scan stops before it. Getting this wrong double-counts or drops lines at chunk edges. The tests therefore use chunk sizes of 2, 3 and 7 bytes, so that every line crosses a boundary somewhere.

---

## 4. Finding bytes 8 at a time (SWAR)

`core/ByteScan.java`

Indexing is mostly "find the next `\n`". Searching is mostly "find the next first byte of the search text". A byte-by-byte loop handles one byte per step. **SWAR** ("SIMD within a register") handles **8 bytes per step** by reading them as one `long`. No special CPU instructions are needed; it's plain arithmetic.

### The trick: a long whose bytes are zero exactly where the match is

To find byte `b` in a word `w` (8 bytes):

1. `x = w XOR (b repeated 8 times)`. A byte of `x` is `0x00` exactly where `w` had `b`.
2. Find the zero bytes of `x` with this classic expression:

```java
long y = (x & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;  // high bit set if low 7 bits non-zero
long z = ~(y | x | 0x7F7F7F7F7F7F7F7FL);                   // high bit set only where the byte is 0
```

3. If `z != 0`, the first match is at byte `Long.numberOfTrailingZeros(z) / 8` (little-endian).

**Worked example**, looking for `\n` (`0x0A`) in the text `"ok\nERROR"`:

```
bytes:            o    k    \n   E    R    R    O    R
w (hex, LE):      6F   6B   0A   45   52   52   4F   52
pattern:          0A   0A   0A   0A   0A   0A   0A   0A
x = w ^ pattern:  65   61   00   4F   58   58   45   58      ← byte 2 is zero
z (high bits):    0    0    80   0    0    0    0    0
numberOfTrailingZeros(z) = 23  →  23 / 8 = 2  →  '\n' is at index 2 ✓
```

Why it's exact: for each byte, `(x & 0x7F) + 0x7F` sets the high bit if any of the low 7 bits is set. OR-ing with `x` itself catches bytes whose own high bit is set. A byte ends with its high bit clear in `y | x` **only if it was zero**. The `0x7F` masking stops carries spilling from one byte into the next. That matters: the shorter, popular version `(x - 0x0101..) & ~x & 0x8080..` finds the *first* zero byte correctly, but a borrow can also mark a `0x01` byte right after a real zero. Counting newlines with it would then give wrong totals.

Java reads 8 bytes as a `long` through a `VarHandle` view of the `byte[]` (`MethodHandles.byteArrayViewVarHandle`), which the JIT compiles down to a single load.

**Counting newlines** uses the same `z` with `Long.bitCount(z)` instead of finding the first one, which counts all newlines in 8 bytes at once.

---

## 5. Reading lines: the page cache

`core/PageCache.java`, `core/LogDocument.java`

Painting the screen needs about 50 lines. Each lookup (section 3) starts at a checkpoint and scans forward, often over the same bytes as the previous line. So `LogDocument` reads through a small **LRU cache of 64 KB pages**, up to 512 pages (32 MB) per file:

- One screen of lines usually touches 1–2 pages.
- Scrolling back and forth costs no file reads.
- A page shorter than 64 KB (the end of a growing file) is read again when more data is needed.

Lines are decoded from UTF-8 only here, for display. **Lines longer than 64 KB are cut for display** (a single 200 MB JSON line would otherwise freeze the UI). Search still sees the whole line.

---

## 6. Following a growing file (tail)

`core/LogDocument.java`

A timer checks the file every 500 ms. There are three cases:

```
                     ┌─ same file, bigger ─────────► index only the new bytes            (growth)
  every 500 ms  ─────┼─ same file, smaller,  ──────► throw the index away, start over    (truncation)
  look at the path   │  or first 256 bytes changed
                     └─ a different file ──────────► reopen the path, start over         (rotation)
```

**Growth.** New bytes are indexed like any other chunk. A tailed file grows a few hundred bytes at a time, though, which would leave thousands of tiny chunks. So when the last chunk is shorter than 4 MB, it is **scanned again together with the new bytes**, and chunks stay full-sized. Older snapshots keep referring to the old chunk, which is why the chunk array is copied before it is changed.

**Truncation** (`> app.log`, or an app that rewrites its log). If the file is now smaller than what was indexed, or its **first 256 bytes changed** (the "fingerprint"), every line number is invalid. The document starts over, and its `generation` number goes up, which tells searches and filters to start over too.

> Lesson: a very short file that grows (say, from 40 to 400 bytes) also has a "different first 256 bytes", because there are now more of them. Comparing the whole fingerprint made Logsift reset small files on every write. The fix is to compare only the bytes both versions have, and then remember the longer prefix.

**Rotation** (the app renames `app.log` to `app.log.1` and starts a new `app.log`):

- On Linux and macOS each file has an **inode** (`BasicFileAttributes.fileKey()`). If the path now has a different inode, it's a new file.
- **Windows** reports no file key. There, if the file at the path is smaller than the file Logsift has open, it's treated as a new file.

The lines Logsift had shown belong to the old file, which now has a different name. Logsift follows the **path**, because that's what the user opened and where new log lines are written.

**The unfinished last line.** A writer may have written half a line. It is displayed, but searches treat it as *tentative*: it is searched when the file is quiet and searched again once it's complete, so a hit is never lost or counted twice.

---

## 7. Search

`core/SearchSession.java`, `core/LineMatcher.java`, `core/RequiredLiteral.java`

### 7.1 Plain text: search the whole buffer, then find the line

The obvious way is: for each line, does it contain the text? That costs work **per line**. Logsift flips it around:

```
buffer (4 MB of whole lines):  ....\n.......\n....ERROR...\n........\n.....\n...ERROR..\n
                                              ▲                                  ▲
        1. jump from match to match ──────────┘──────────────────────────────────┘
        2. only at a match: count the newlines since the previous match → line number
```

1. Find the next occurrence of the text's **first byte** with SWAR, then compare the rest.
2. Only when there's a hit, count the newlines between the previous position and the hit (SWAR `bitCount` again) to get its line number.
3. Skip to the end of that line (one hit per line is enough) and continue.

Lines without a match cost almost nothing; they are skipped 8 bytes at a time. This is why searching for `ERROR` in 1 GB takes about 0.17 s.

**Ignore case, ASCII text:** the search text is lowercased once. The scan looks for **either** case of the first byte (`indexOfEither`, two SWAR comparisons per word) and compares the rest with an ASCII lowercase. Nothing is decoded.

### 7.2 Regex, whole word, and non-ASCII ignore case

These need Java's `Pattern` on decoded `String`s, which is roughly 10 times slower than raw bytes. Running it on every line of a 1 GB file took 1–2.6 s. The fix is ripgrep's trick:

> **Find a piece of plain text that every match must contain. Byte-search for that, and run the regex only on lines that contain it.**

`RequiredLiteral` reads the regex and picks the longest run of literal characters outside groups, classes and optional parts:

| Query | Required text | Regex runs on |
|---|---|---|
| `Payment failed .* user=u12\d4` | `Payment failed ` | only lines containing it |
| whole word `WARN` | `WARN` | only lines containing `WARN` (then checks word boundaries) |
| `müller`, ignore case | `ller` (ASCII-only part) | only lines containing `ller` in any case |
| `(ERROR\|WARN)` | none (top-level alternative) | every line |

That took whole-word and Unicode ignore-case searches from 2.6 s and 1.0 s to about 0.15–0.2 s per GB.

**A subtle trap:** for ignore-case, the required text must be safe for a simple ASCII case-insensitive byte search. Java's Unicode-aware ignore-case also matches:

- the **Kelvin sign** `K` (U+212A) for `k`,
- the **long s** `ſ` (U+017F) for `s`,
- the **dotless/dotted Turkish i** for `i`.

A byte search for `ask` would miss a line containing `aſk` that the regex matches. So the required text may contain no non-ASCII characters and no `k`, `s` or `i`. When unsure, the analysis returns "none" and every line gets the regex. It's slower but never wrong.

### 7.3 Parallel, in order, and live

`SearchSession` splits the indexed lines into ranges along the index chunks and searches them on all cores. Like indexing, results are **published in line order**, so the results list grows from the top and never reshuffles.

After catching up, the session keeps listening to the document. **New lines are searched as they are written**, which makes Find All on a tailed file a live view. On truncation or rotation (a new `generation`), it clears its hits and starts over.

Limits keep memory bounded: at most 20 million hits (8 bytes each), and a single line over 256 MB is only searched in its first 256 MB.

---

## 8. Filters: combining searches with AND / OR / NOT

`core/FilterEngine.java`, `core/FilterResult.java`, `core/Formula.java`

A filter set like `(A OR B) AND NOT C` could be evaluated line by line, running every filter on every line. Logsift does something cheaper:

1. **Each filter is a normal live `SearchSession`**, as fast as Find All, and produces a **sorted list of matching line numbers**.
2. The formula is evaluated over those **sorted lists**: AND is an intersection, OR is a union. Each is one linear merge, like the merge step of merge sort.

```
A = [3, 9, 15, 40]          A OR B  = [3, 7, 9, 15, 22, 40]
B = [7, 9, 22]              A AND B = [9]
```

### NOT without listing (almost) every line

`NOT C`, as in "hide DEBUG", would be a list of nearly every line in the file. So a set is stored as **either** a sorted list **or** "every line except a sorted list" (a *complement*). NOT just flips the flag, and the operations follow De Morgan's laws:

| | `x` is a list | `x` is "all except" |
|---|---|---|
| `x AND y` (y a list) | intersect(x, y) | y minus x.list |
| `x AND y` (y "all except") | x minus y.list | all except union(x.list, y.list) |
| `x OR y` (y a list) | union(x, y) | all except (x.list minus y) |
| `x OR y` (y "all except") | all except (y.list minus x) | all except intersect(x.list, y.list) |

`(A OR B) AND NOT C` therefore costs one union and one "minus". Hiding a few thousand lines out of 28 million stores just those few thousand.

### Row ↔ line in "all except" form

The **Matching only** view needs "which file line is shown in row r?" For a plain list, that's `list[r]`. For "all lines except `e[0..k)`" (sorted) it's less obvious. The trick: `e[j] − j` is the number of *shown* lines before the j-th excluded line, and that value never decreases as `j` grows. So a binary search finds how many excluded lines come before row `r`:

```
excluded e = [2, 3, 7]       e[j] - j = [2, 2, 5]
row 4 → count of j with e[j] - j ≤ 4 is 2 → line = 4 + 2 = 6
check: shown lines are 0 1 4 5 6 8 ...  → row 4 is line 6 ✓
```

It's O(log n) and needs no extra memory.

### Only lines every filter has checked

The filters run at different speeds. A line is only decided once **every filter in the formula has searched it**, so the combined result covers lines `[0, min(covered by each filter))`. While filters are still running, the Matching only view grows from the top. It never shows a line and then removes it.

### Changing the formula is nearly free

Filters keep their hit lists. Changing `(A OR B) AND NOT C` to `A AND NOT C` reruns only the merges: **2 ms** on a 200 MB log, versus 200 ms for the first run. Changing a filter's *text* reruns only that one filter's search.

### Keeping your place

Switching **All lines ↔ Matching only** remembers the file line under the cursor, swaps the row source, and moves the view so the same line, or the next one shown, is under the cursor again.

---

## 9. The view: drawing only what is visible

`ui/LogView.java`, `ui/OverviewBar.java`

### Why not `JList` or `JTable`?

Swing's lists lay rows out in **pixels**, stored as `int`. At about 20 px per row:

```
Integer.MAX_VALUE / 20 px ≈ 107 million rows      → bigger files break the scrollbar
```

`LogView` is a custom component:

- **The scrollbar counts rows, not pixels.** That works up to about 2.1 billion lines.
- **Only the visible rows are read and painted**, about 50 per frame, whether the file has a thousand lines or a billion.
- Tabs are expanded, wide characters (CJK) are measured, and highlights for Find All matches, filter colors and level colors (ERROR, WARN, …) are drawn per visible line.
- Text selection is stored as (row, character) positions, so selecting across lines, double-clicking a word and Shift+click all work without a text component holding the file.

### The highlight bar

The bar beside the scrollbar shows where errors, warnings, filter matches and Find All hits are in the **whole file**. Drawing one tick per hit would cost millions of operations per repaint. Instead it works **per pixel row**:

```
bar height: 800 px        file: 28,000,000 lines   →  each pixel row covers 35,000 lines
for each pixel row y:
    lines [from, to) covered by y
    for each kind of mark (error, warning, filter…):
        first hit ≥ from (binary search in the sorted hit list)  <  to ?  → draw the tick
```

That's 800 × (a few kinds) binary searches per repaint, constant cost whether there are 10 hits or 10 million. Clicking near a tick jumps to that exact line rather than just a position in the file.

---

## 10. Folder search

`core/FolderSearch.java`

Searching a folder is different from viewing one file: each file is read once, no index is needed, and many files can be processed at the same time.

- **Walk** the folder (optionally its subfolders, skipping hidden folders like `.git`) and match file names against patterns like `*.log;*.txt`.
- **Rotated files count as the log they came from:** `app.log.1`, `app.log.2026-10-21` and `app.log.2.gz` all match `*.log`.
- Each matching file is a task on a thread pool (one thread per core). A task **streams** the file in 4 MB blocks of whole lines (a block ends at its last newline, and the rest carries over to the next block) through the same `LineMatcher` as Find All. The text of a hit line is cut from the block only when there is a hit.
- **`.gz`** is unpacked while streaming (`GZIPInputStream`), and **`.zip`** entries are read one after another (`ZipInputStream`) without unpacking to disk. Only when you open a hit is that file unpacked to a temporary file, so the viewer can index it.
- **Binary files are skipped**, as grep does: a zero byte in the first 8 KB means binary.
- Memory is bounded: hit text is kept for up to 10,000 lines per file and 1,000,000 in total; beyond that, hits are only counted.

---

## 11. More features, and the idea behind each

Each later feature reuses the same few building blocks: the line index, the byte-level scanners, and sorted lists of line numbers.

**Text wrap** (`ui/LogView.java`). The view lays out each visible line into *segments* (display rows), breaking at a space near the edge if there is one. The scroll position is a line *plus* a segment within it, and the scrollbar still counts lines. Only on-screen lines are ever laid out, so wrap costs nothing on a 10 GB file.

**Keeping stack traces with their line** (`core/EntryFormat.java`). From a sample of the file, Logsift finds how an entry starts, for example `2026-10-21`, `[10:02:13`, a syslog date, a level word or `{`. A scanner like Find All then lists every line that does *not* start that way: these are continuation lines. With that sorted list, each filter's matches are widened to whole entries before AND/OR/NOT, so the formula works on entries rather than lines. Walking back from a match to the entry's first line, and forward to its last, is a short binary-search-and-walk in the continuation list.

**Context lines and time ranges** (`core/FilterResult.java`). A result now holds two sets: the lines that *match*, and the lines *shown* (matches plus context). Context in the "all except" form is a nice trick: a run of hidden lines simply shrinks by N at each end that touches a shown line. A time range is a *window* `[start, end)` of lines, because logs are mostly written in time order. The window limits both sets, and "all except" then means "all lines of the window except…".

**Timeline** (`core/TimeFormat.java`, `core/TimeIndex.java`, `ui/TimelineBar.java`). Timestamps are parsed straight from bytes (`daysFromCivil` turns a date into a day number without `java.time`). The time index stores one *(line, time)* pair about every 64 lines, the first line with a timestamp at or after each 64th line, which skips stack traces. That's 2 longs per 64 lines. The bars are counted on a background thread, and "go to 10:05" is a binary search in the samples plus at most a few hundred lines read forward.

**Top messages** (`core/MessageStats.java`). Each entry's first line is tokenized at the byte level. Every token containing a digit (IDs, numbers, hosts, durations, the timestamp) becomes `<*>`, so `ERROR [worker-7] lost after 26579 ms` and `ERROR [worker-2] lost after 812 ms` give the same template. Chunks are counted in parallel, each into its own hash map, and the maps are merged. Then templates that differ in exactly one word (for example a customer name) are merged too. The regex for a template escapes characters one by one rather than using `\Q…\E`, so the search can still find its literal text and use the fast pre-filter.

**Comparing logs** (`core/LogCompare.java`) counts the templates of both logs together. Rates are per entry, so a log twice as long doesn't look twice as bad. Templates are then sorted: new errors first, then big increases, then what disappeared.

**Merging logs by time** (`core/LogMerger.java`). This is a k-way merge with a priority queue keyed by each source's next entry time, holding one entry per file in memory. The result is written to a real file with a `<label> ` prefix on each line. That makes every other feature work on it unchanged. Entry and time detection simply skip such a label.

**Columns** (`core/LogFormat.java`). A layout is a regex with named groups, or a JSON field mapping, picked from presets by sampling. Only visible lines are split, at paint time, so columns cost nothing on huge files. *Count values* splits every entry in parallel chunks.

**Encodings** (`core/Encoding.java`, `core/Utf16Mirror.java`). Windows-1252 and other single-byte encodings keep `\n` as the byte 0x0A, so the byte engine works as is: search text is encoded in the file's encoding, and lines are decoded with it. UTF-16 breaks the byte engine (a newline is two bytes), so Logsift reads such logs through a UTF-8 copy that follows the original. The decoder keeps half a character, or half a surrogate pair, until the next bytes arrive.

**Remote logs** (`core/RemoteMirror.java`). The same idea again: a local copy fed over SFTP with offset reads (`get(path, monitor, offset)`). It only ever appends whole lines, and the remote offset is "bytes read minus the incomplete last line". If the remote file gets smaller, the copy starts over, and the document's usual truncation handling takes care of the rest.

## 12. Java threads and profiling

This part lives in its own package, `logsift.jvm`, again without UI code.

**Reading thread dumps** (`jvm/DumpParser.java`). A dump is text, so reading it is a small state machine fed one line at a time. `Full thread dump` starts a dump; the line just before it, if it is a time, says when. A line like `"name" #12 daemon prio=5 … nid=0x5f03` starts a thread, `java.lang.Thread.State:` gives its state, `at …` lines are frames, and `- locked <0x…>`, `- waiting to lock <0x…>`, `- parking to wait for <0x…>` are notes on the frame above. The JVM's own deadlock report after the threads repeats thread names (`"worker-A":`), so the reader stops taking threads at `JNI global references` or `Found one Java-level deadlock`.

Because it is line by line, the same reader finds dumps in the middle of a log. When a program gets `kill -3`, its console log suddenly contains a dump. If the log puts a time in front of every line (`docker logs --timestamps`), the text before `Full thread dump` becomes a pattern: digits may change, the rest must stay. That pattern is cut off the following lines. The newer formats of `jcmd Thread.dump_to_file` (plain text, and JSON with virtual threads) have readers of their own and give the same model. Tests read real dumps from JDK 8, 11, 17, 21 and 25 of a program built to deadlock, and dumps written back by Logsift read the same again.

**Finding deadlocks** (`jvm/ThreadAnalysis.java`). Every thread holds some locks (`locked`, and the "ownable synchronizers" of `ReentrantLock` and friends) and waits for at most one. So *thread → the holder of the lock it waits for* is a graph in which every node has at most one outgoing edge. Following the edges from each thread either ends, or comes back to a thread already on the path, and that loop is a deadlock. Each thread is visited once, so this is linear in the number of threads. A quirk to get right: a thread in `Object.wait()` is listed as having `locked` the object it waits on, but it has let it go while it waits. Counting it as held would show deadlocks that aren't there.

**Stuck and busy threads across dumps.** Threads are matched between dumps by their Java id (`#12`). A thread's *stack key* is its state plus every frame and the kind of lock in each note. Threads with the same key in every dump, which aren't idle (waiting for work in a pool, a selector, a timer), are stuck. CPU use comes from `cpu=` and `elapsed=` in the thread headers (JDK 11+): the change in CPU time divided by the change in elapsed time is the share of one core used between the dumps.

**Attaching to a running JVM** (`jvm/LocalJvms.java`). The JDK's attach API lists JVMs and attaches to one. Logsift then asks it to start its local JMX agent and calls the `DiagnosticCommand` MBean, which runs the same commands as `jcmd` (`threadPrint`, `jfrStart`…). The answer is the same text as `jcmd <pid> Thread.print -l`, so there is just one parser for files and live JVMs. If attaching fails, `jcmd` itself is run.

**Over SSH** (`jvm/SshJvms.java`). `ps` lists the processes, and `/tmp/hsperfdata_*` adds JVMs whose command doesn't say `java`. Then `jcmd` runs on the server, found in the folder you give, on the PATH, or next to the JVM's own `java` (`/proc/<pid>/exe`). On servers with only a Java runtime there is no `jcmd`. Logsift then copies async-profiler's launcher there over SFTP (once; the size is checked next time). Like `jcmd`, it can run diagnostic commands in a JVM. A JVM of another user is reached with `sudo -u`, or as root for async-profiler, which takes on the JVM's user itself.

**Profiles and flame graphs** (`jvm/Profile.java`, `ui/FlameGraph.java`). async-profiler prints one line per distinct stack, outermost frame first, with how often it was seen: `Thread.run;Api.handle;Db.query 42`. Adding each line to a tree merges the common beginnings, and each node keeps its *total* (itself and below) and *self* (itself only). Flight Recorder events are turned into the same tree. The flame graph draws each node as wide as its total, so its children fit exactly under it. The list of hot methods adds up *self* per method. A method's *total* is counted only once on each path, or recursion would count it twice.

## 13. Lessons learned

**The thread taking the dump looked stuck.** With a live JVM, the JMX thread that runs the dump command was always running the same code, so it showed up as "stuck". So did `main` reading from a console. Such threads are idle now. The visual test attaches to a real JVM and checks that nothing in it is reported as stuck.

**A lambda's class looked like a Java module.** `jcmd Thread.dump_to_file` writes frames as `java.base/java.lang.Thread.sleep`, with the module before the slash. A lambda frame is `Fixture$$Lambda/0x0000000800c01440.run`, so splitting at the slash turned the lambda's class into a "module". A slash after a `$`, or before `0x`, is a hidden class now.

**An interrupted thread closes the file for everyone.** Java's `FileChannel` is an *interruptible channel*: if a thread is interrupted while reading, Java **closes the channel**, for every thread. Logsift used to cancel searches by interrupting worker threads (`Future.cancel(true)`, `shutdownNow()`). So editing a filter while its search ran (which cancels the old search) could close the open file, and the whole tab stopped working. Nothing reported it, because the error went to a failure callback that nothing listened to. It surfaced as an intermittent visual-test failure. The fixes:

- cancel cooperatively: a `volatile boolean` that the scan loops check, and never interrupt;
- `FileSource` reopens the channel if it ever finds it closed by an interrupt, after checking that the path still points to the same file;
- tests that read with the interrupt flag set, and cancel 30 searches mid-flight.

**"Finished before I was listening" bugs.** Three times, something happened before a listener was attached:
- a small file finished indexing before the tab subscribed to "indexing finished", so detection never ran;
- the tab's constructor got slower and missed the event on a 200 MB file too;
- a restored view switched to *Matching only* while the filter result was still empty.

The fixes share a pattern. The document *remembers* state (`lastIndexMillis()`), and anything waiting for a condition (a line to show) is checked again on every relevant event, not just once.

**A position lost during recomputation.** Changing filters makes the engine publish an empty result for a moment. The view clamped its selection to "nothing", so the place in the file was gone. Now the view remembers its line before a redefinition, and returns to it once the new result covers it.

**Overloads that look alike.** `sourceColor(long line)` and `sourceColor(int index)` both took a number. Passing an `int` row picked the wrong one, and the source stripes cycled through colors. Different names fixed it. A test that compared each stripe against the line's actual source would have caught it, so now one does.

**Tests must not touch the user's settings.** For a while the visual tests wrote to the real preferences and `~/.logsift`. Now all storage goes through one class (`AppState`), and the test tasks point it at the build folder.

**Measure what you think you measure.** A test once reported that changing a filter formula took 1.1 s. In fact it took 2 ms: the timer included a 360 ms UI settle pause and a slow reference computation. Benchmarks in the visual tests now stop the clock the moment the work is done.

**Prefer "slower but never wrong".** Wherever there's a shortcut (the regex pre-filter, the ASCII ignore-case search), it is only used when it is *provably* the same as the slow path. Otherwise the slow path runs. Every fast path is tested against a simple, obviously correct implementation on random input: random line endings (`\n`, `\r\n`), empty lines, very long lines, Unicode, a missing final newline, tiny chunk sizes and many queries.

**Test on the platforms you ship.** The CI builds and tests on Windows, macOS and Linux, including the visual tests (on Linux with a virtual display) and log rotation by real logback on Windows, where file locking differs most.

---

## 14. Numbers

Measured by `./gradlew visualTest` on an Apple Silicon Mac (10 cores) with the file already in the OS cache. With a cold cache, disk speed sets the limit.

| | 1 GB, 9.4M lines | 3 GB, 28.1M lines |
|---|---|---|
| First lines on screen | 0.12 s | 0.22 s |
| Full index built | 0.26 s | 1.1 s |
| Index memory | ~1.2 MB | ~3.5 MB |
| Jump to any line and repaint | ~10 ms | ~6 ms |
| Find All `ERROR` (plain text) | 0.17 s | 0.58 s |
| Find All `WARN` (whole word, pre-filtered) | 0.15 s | 0.38 s |
| Find All `müller` (ignore case, non-ASCII) | 0.19 s | 0.45 s |

| Filters on a 200 MB log (1.8M lines) | |
|---|---|
| 4 filters, `(A OR B) AND NOT C` | 0.2 s |
| change only the formula | 2 ms |

| Folder search | |
|---|---|
| 4 files, 48 MB, including `.gz` and `.zip` | ~50 ms (Mac), ~160 ms (Windows CI) |

| On a 200 MB log (1.8M lines, 1.56M entries) | |
|---|---|
| Keep stack traces: filter `NullPointerException` (74k lines) | 60 ms |
| Top messages (94 kinds) | ~0.2 s |
| Count values of a column (thread) | ~0.3 s |
| Export 85k filtered lines | ~0.3 s |
