---
layout: guide
title: Filters
description: Show, Hide and Mark filters, their colours, the formula, chips, level chips, stack traces, context lines and alerts.
---

# Filters

Filters show, hide or colour lines. They keep working while the file grows.

![A search turned into a filter, shown as a chip above the log with the chosen levels](../screenshots/filter-chips.png)

## Adding a filter

- Select text in the log, right-click → **Show / Hide / Mark lines with "…"**.
- In the find bar, **Filter ▾** → **Show Only Matching Lines**, **Hide Matching Lines** or **Color Matching Lines**.
- In the **Filters** tab below the log, **+ Add filter**.

## The Filters tab

Each filter is a row:

| Part | What it does |
|---|---|
| Checkbox | Turns the filter on or off |
| **A**, **B**, **C** … | Its letter, for the formula. The circle has the filter's colour |
| **Show** / **Hide** / **Mark** | **Show** keeps matching lines, **Hide** removes them, **Mark** only colours them |
| Text, with **Aa** **W** **.\*** | What to match: match case, whole word, regular expression. A wrong regular expression gets a red outline |
| Colour | The colour of its lines (below), or *No color* |
| Count | How many lines match |
| ▲ ▼ | Previous or next matching line |
| Bell | Alerts for new matching lines (below) |
| ✕ | Removes the filter |

Up to 26 filters (A to Z). A new filter takes the colour used least; a Hide filter starts with none.

### Filter colours

| Name | Line colour (light) | Line colour (dark) | Mark (chip dot, highlight bar) |
|---|---|---|---|
| Yellow | <span class="sw" style="background:#FFF1A8"></span> | <span class="sw" style="background:#4D4416"></span> | <span class="sw" style="background:#D4A800"></span> |
| Green | <span class="sw" style="background:#D3F2CF"></span> | <span class="sw" style="background:#1F4026"></span> | <span class="sw" style="background:#2FA84F"></span> |
| Blue | <span class="sw" style="background:#D3E5FF"></span> | <span class="sw" style="background:#1C3457"></span> | <span class="sw" style="background:#3B82F6"></span> |
| Purple | <span class="sw" style="background:#E8DAFB"></span> | <span class="sw" style="background:#3B2A57"></span> | <span class="sw" style="background:#8B5CF6"></span> |
| Orange | <span class="sw" style="background:#FFDDBC"></span> | <span class="sw" style="background:#52341E"></span> | <span class="sw" style="background:#F97316"></span> |
| Pink | <span class="sw" style="background:#FBD9E9"></span> | <span class="sw" style="background:#52263B"></span> | <span class="sw" style="background:#EC4899"></span> |
| Teal | <span class="sw" style="background:#CBF1EC"></span> | <span class="sw" style="background:#1A4542"></span> | <span class="sw" style="background:#14B8A6"></span> |
| Red | <span class="sw" style="background:#FAD0D0"></span> | <span class="sw" style="background:#552326"></span> | <span class="sw" style="background:#DC2626"></span> |

A filter's colour replaces the [level colour](log-view.md#level-colours) on the lines it matches.

## Combining filters

**Combine** says how filters work together, using their letters.

- **Auto** (on by default) writes it for you: any Show filter, and no Hide filter. For example `(A OR B) AND NOT C`.
- Type your own to get anything else: `A AND B`, `(A OR B) AND NOT (C OR D)`, `NOT C`. `AND OR NOT ( )` work, and so do `& | ! -`. Typing turns Auto off. A formula that can't be read gets a red outline, and the last good one stays in use.
- Mark filters only colour; they don't change which lines show.

## All lines or Matching only

**All lines** shows every line, with the lines your filters leave out grayed. **Matching only** (Ctrl+Shift+H, ⇧⌘H on macOS) shows only the lines that pass. Your place in the file is kept when you switch: filter down, click a line, switch back and see everything around it.

The count on the right says *65,361 of 1,832,469 lines* (with context lines: *… matching, … shown*). It adds *in the selected time range* or *some levels hidden* when those apply.

## Levels: E W I D T

The **E W I D T** buttons show only some levels, together with your other filters: errors (ERROR, FATAL, SEVERE, CRITICAL), warnings (WARN, WARNING), info (INFO, NOTICE), debug (DEBUG), trace (TRACE). All on means no level filtering. Turn off I, D and T to see just errors and warnings, stack traces included. With your own log format, its level column is used instead.

## Keep stack traces and Context

- **Keep stack traces** (on by default) treats a log line and the lines under it (stack traces, wrapped messages) as one entry: if any line of the entry matches, the whole entry shows, and Hide filters hide whole entries. Hover it to see how Logsift found that entries start (a date, a time, a level word, `{` for JSON). It is grayed out when every line stands alone.
- **Context ±N** adds N grayed lines before and after each match (up to 50), with a dashed line where lines were skipped, like `grep -C`.

## Chips

Filters that are on show as **chips** in the log's bar: *Show: ERROR 65,529*, *Hide: …*, *Color: …* (a Mark filter), with a dot in the filter's colour and the number of lines.

- **Click** a chip to turn its filter off or on. An off chip is gray, with a dashed outline.
- **✕** removes it.
- **Right-click** to change what it does (On, Show, Hide, Color), **Edit in Filters…** or **Remove**.
- Other chips: **Levels: ERROR, WARN** (the levels shown), **Time: …** (a time range from the [timeline](timeline.md)), **Tree: N hidden** (branches hidden in the tree of loggers). Their ✕ clears them.
- Chips that don't fit fold into **+N more**, which opens the Filters tab.

## Alerts

Click the bell on a filter to be told when new lines matching it are written (while the file grows). The bell turns <span class="sw" style="background:#F5A524"></span> amber. Logsift shows a desktop notification and a popup you can click to jump to the line, beeps, and puts a count on the tab. Bursts are combined: at most one alert per filter every 3 seconds. Lines that were already in the file don't alert.

## Clearing

- **Clear All** in the log's bar (lit in blue while anything is set) goes back to the whole log: it removes every filter, turns all levels back on, clears the time range and what the tree hides, and closes the find bar and the Find results.
- **Clear all** in the Filters tab clears only the filters.

## Saving filters

The **⋯** menu in the Filters tab: **Save Filters…**, **Load Filters…** (`.lmfilters` files), **Remove All Filters**. Each log also remembers its own filters, formula, level buttons and Matching only for next time.
