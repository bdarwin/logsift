---
layout: guide
title: Compare files
description: Comparing two CSV or TSV files as tables, with rows matched by a key whatever their order.
---

# Compare files

**File → Compare Files…** compares two files. CSV and TSV files are compared as **tables**: rows are matched by a **key** (an ID column, or a few columns together), wherever they are in each file, so two exports of the same data in a different order compare cleanly. Other files get a note for now: comparing text and logs line by line is coming next. To compare two logs by kinds of message, use [Compare Logs by Message](compare.md).

![Two exports of the same orders compared by order_id: 6 added, 8 removed, 20 changed, changed prices and statuses as old → new](../screenshots/csv-compare.png)

## Starting a comparison

- **File → Compare Files…**: pick the **left** (older) and **right** (newer) file, type their paths, or drop files on the window: one on a field, or two at once. **Swap ⇅** swaps them. With a CSV tab open, it is filled in as the left file.
- **A CSV tab:** the compare button in the log's bar (the two arrows next to *Format: CSV*) compares that file with another.
- **A folder's or a zip's tab:** select two files (Ctrl-click, ⌘-click on macOS) and click **Compare**. Files in a zip, and `.gz` files, are unpacked first.
- **Drag and drop:** drop a file on the left or right half of a comparison to compare another file on that side; an outline shows where it will land. Drop two files at once to replace both. Files dropped anywhere else on the window open as logs, as always.

The files may use different separators (commas in one, semicolons or tabs in the other), different encodings (UTF-8, UTF-16, Windows-1252), a byte order mark, Windows line ends and quoted fields over several lines.

## Keys

A key is what makes a row the same row in both files: an order number, a user ID, or a few columns together (*region* + *sku*). Rows with the same key are compared value by value; the order of rows doesn't matter.

- **Suggested key:** Logsift looks for a column, or two or three together, whose values are filled in and different on every row of both files (the first 20,000 rows of big files), and prefers names like *id*, *key*, *…_id* and *code*. It is chosen for you, with why underneath: *Suggested: order_id — unique and filled in on every row of both files*.
- **Change it:** ✕ on a key removes it, **+ Key ▾** adds a column (several make a key together), **Use Suggested** goes back. Then **Compare**.
- **No key:** rows are lined up in file order, as a text diff lines up lines: equal rows match, a row changed in place shows as changed, and rows added or removed in between show as such. **Ignore row order** instead matches equal rows wherever they are (only whole rows: nothing is "changed").
- **Duplicate keys:** a key found more than once in a file can't be matched. All its rows, in both files, are listed as **duplicate key**, never matched to one at random.

## Columns

Columns are matched **by name**, whatever their case or the spaces around them (*Price* and *price *). A column only one file has is said once above the table (*Column region only in the right file*), not as a change on every row.

**Columns…** pairs columns by hand (*cost* in the left file with *price* in the right) and leaves columns out of the comparison (untick **Compare**), for example a *last updated* column that always differs.

## Options

| Option | What it does |
|---|---|
| **Trim spaces** | Spaces at the start or end of a value don't count (on by default) |
| **Ignore case** | *Paid* = *paid*, in keys too |
| **Numbers as numbers, within** | Values that are both numbers are compared as numbers, so *1.50* = *1.5* (on by default). **Within** allows a difference: an **absolute** amount (*0.01*), or a **%** of the larger number |
| **Empty = missing** | An empty value equals one that isn't there, as in a row with fewer fields than the header (on by default) |
| **Ignore row order** | Without a key: match equal rows wherever they are |

Change options or keys, then click **Compare** again.

## What it shows

**Tiles** count the rows, and a click on one shows only those rows:

| Tile | Colour | Means |
|---|---|---|
| **+ added (only right)** | <span class="sw" style="background:#16A34A"></span> green | A key only in the right file |
| **− removed (only left)** | <span class="sw" style="background:#DC2626"></span> red | A key only in the left file |
| **≠ changed** | <span class="sw" style="background:#D97706"></span> amber | The same key with different values |
| **= same** | <span class="sw" style="background:#9CA3AF"></span> gray | The same key, the same values |
| **‼ duplicate keys** | <span class="sw" style="background:#8B5CF6"></span> purple | Keys found more than once in a file (and how many rows have them) |

**Changes per column**, on the left, says how many rows changed in each column (*price: 1,204 rows*), most first. Click one to show only those rows; **All columns** shows them all again.

**The table** has a row per key: its **Status** (the symbol and the word, in the tile's colour), **Line (left → right)** in each file, the key columns (*(key)* after their name) and the compared columns.

- A **changed value** shows as <span style="color:#B91C1C"><s>old</s></span> → <span style="color:#15803D">**new**</span>: the old value struck through in red, the new one in bold green, on a light amber cell (in dark mode lighter red <span class="sw" style="background:#F87171"></span>, green <span class="sw" style="background:#4ADE80"></span> and a dark amber cell). Hover a value for both in full. A line break in a value shows as ⏎.
- **Added** rows are on a light green background with green values, **removed** rows on a light red one with red values, **duplicate key** rows on a light purple one.
- **Show** picks the rows: *All differences* (the default), *Changed*, *Added*, *Removed*, *Duplicate keys*, *Same*, *Everything*. **search in results** keeps the rows with that text in a value (any case).
- **▲ ▼** (Shift+F7 / F7) go to the previous and next difference; it says *difference 3 of 120*.
- **Double-click** a row to open its line in the right file (the left one for a removed row). Right-click for either file.

## Export

**Export ▾**:

- **Differences as CSV…**: every row that differs, with its status, its line in each file, the key, and each compared column's left and right value (for a changed row, only the values that changed have a right value).
- **Rows Shown as CSV…**: the rows the table shows now (after Show, the column and the search).
- **Copy Summary** and **Save Summary…**: a few lines saying what was compared and the counts, and the changes per column.

## Limits

- Big files are fine: only each row's place in the file and a few hashes are kept in memory, and rows are read again when shown. Two files of a million rows each compare in a few seconds, with about 50 MB of memory kept.
- The key suggestion looks at the first 20,000 rows of each file. A key that repeats later shows up as duplicate keys.
- Values are compared as text unless both are numbers; dates are text (*2026-01-02* and *02/01/2026* differ).
- Without a key, rows changed in place are paired in order within a run of changes; if many rows moved, choose a key.
- Changing a file while it is compared isn't noticed: compare again.

## Coming next

- Text and log files side by side, with changes within a line, and options to ignore spaces or mask timestamps and IDs so two runs of a job line up.
- Comparing folders.
- Three-way merge, and use from the command line and git.
