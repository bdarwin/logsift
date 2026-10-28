---
layout: guide
title: Find
description: The find bar, Find All, several searches at once with their colours, and searching within results.
---

# Find

## The find bar

**Ctrl+F** (⌘F on macOS), or **Find** in the log's bar, opens a find bar at the top of the log, with the selected text filled in.

![The find bar at the top of a log: matches lit up, 1 of 31](../screenshots/find-bar.png)

- Matches light up in <span class="sw" style="background:#FFD54F"></span> **yellow** as you type (<span class="sw" style="background:#8A6A00"></span> dark yellow in dark mode), and the log goes to the first match at or after the current line.
- Inside the box: **Aa** (match case), **W** (whole word), **.\*** (regular expression).
- The count: *3 of 1,240* when you are on a match, else *1,240 matches*. *…* means it is still counting; *+* means there are more than it counts. *no matches* outlines the box in amber; *invalid regex* outlines it in red (hover for why).
- **Enter** / **Shift+Enter** (or ▼ ▲) go to the next and previous match. **F3** / **Shift+F3** do the same from anywhere (also ⌘G / ⇧⌘G on macOS).
- **Find All** (Ctrl+Enter) lists every match in **Find results** below the log.
- **Filter ▾** turns the search into a filter: **Show Only Matching Lines**, **Hide Matching Lines** or **Color Matching Lines** (see [Filters](filters.md)).
- The folder button searches for it in a whole folder, zips included (see [Folder search](folder-search.md)).
- **Esc** closes the bar. The text is kept for next time.

## Find All and the results

Each **Find All** is added to the top of the **Find results** tab and opens there. Earlier searches fold to a header line (*"ERROR" — 1,234 lines*) and stay available.

![Several Find All searches in one window, one nested inside another](../screenshots/find-all.png)

Every search gets its own colour, in the log, on the highlight bar, and as a dot on its header and a stripe on its lines. The colours, in order, then again:

| # | In the log (light) | In the log (dark) | Mark |
|---|---|---|---|
| 1 | <span class="sw" style="background:#FFD54F"></span> yellow | <span class="sw" style="background:#8A6A00"></span> | <span class="sw" style="background:#D4A800"></span> |
| 2 | <span class="sw" style="background:#A5E6A9"></span> green | <span class="sw" style="background:#2F6E3B"></span> | <span class="sw" style="background:#2FA84F"></span> |
| 3 | <span class="sw" style="background:#A9CDFF"></span> blue | <span class="sw" style="background:#2E5A94"></span> | <span class="sw" style="background:#3B82F6"></span> |
| 4 | <span class="sw" style="background:#FFB8D4"></span> pink | <span class="sw" style="background:#7D3558"></span> | <span class="sw" style="background:#EC4899"></span> |
| 5 | <span class="sw" style="background:#FFCC99"></span> orange | <span class="sw" style="background:#80501F"></span> | <span class="sw" style="background:#F97316"></span> |
| 6 | <span class="sw" style="background:#D5BEFF"></span> purple | <span class="sw" style="background:#5E4396"></span> | <span class="sw" style="background:#8B5CF6"></span> |
| 7 | <span class="sw" style="background:#9FE8E0"></span> teal | <span class="sw" style="background:#1F6B66"></span> | <span class="sw" style="background:#14B8A6"></span> |

- Click a line to go there in the log (or move with the arrow keys; Enter or a double-click goes there too).
- Click a header (or press Enter, ← or →) to fold or open it. **Fold all** / **Unfold all** do every search.
- **F3** / **Shift+F3** step through the selected search's hits.
- Running a search you already have moves it back to the top.
- **Close** removes the selected search (Shift+Esc), **Close all** every one. A middle click on a search's header closes it too.
- The header says how the search ran: *(regex, match case, whole word)*, how long it took, and *(limit reached)* when there were too many hits to list.

**Search within results:** select a search (or any of its lines), type in **Search in these results** and press Enter. A nested search like *"ERROR › timeout"* appears under it, showing only the lines that match both. You can keep narrowing. Closing a search also closes the searches nested in it.

**With filters on:** while the log shows only the lines its filters keep (**Matching only**), Find All finds in those lines (*in filtered lines*), and follows the filters as they change.

**Growing files:** Find All keeps running. New matching lines show up in the results as they are written, in nested searches too.

## Go to a line or time

- **Go → Go to Line…** (Ctrl+L).
- **Go → Go to Time…** (Ctrl+Shift+L) takes `10:05`, `10:05:30.250` or `2026-10-21 10:05`.
- **F8** / **Shift+F8**: next or previous error.
