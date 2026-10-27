---
layout: guide
title: Compare and merge
description: Comparing a log with a baseline by kind of message, and merging several logs by time.
---

# Compare and merge

## Compare two logs

**File → Compare With…** (Ctrl+Shift+D, ⇧⌘D on macOS) compares the current log with a **baseline**: another open log, for example of a run that went well. Open both logs first.

![Comparing a bad run with a good one: new, more frequent and missing messages](../screenshots/compare.png)

Comparing line by line is useless for logs (every timestamp differs), so Logsift compares **kinds of messages**, as in the [Messages](insights.md#messages-top-messages) tab. Counts are scaled to the logs' lengths, so a log twice as long doesn't look twice as bad.

| Change | Colour | Means |
|---|---|---|
| **NEW** | <span class="sw" style="background:#E5484D"></span> red | Only in this log. New errors come first: usually the place to start |
| **×4.2** | <span class="sw" style="background:#C27C00"></span> dark amber | At least twice as often as in the baseline (scaled) |
| **GONE** | <span class="sw" style="background:#9A9A9A"></span> gray | Only in the baseline. Something that used to happen no longer does, such as a *connected* message |
| **÷3.0** | <span class="sw" style="background:#3B82F6"></span> blue (<span class="sw" style="background:#60A5FA"></span> in dark mode) | Half as often or less |
| **same** | gray | About as often (shown only with *Everything*) |

Columns: **Change**, **Level**, **Message**, **Baseline** (count there), **This log** (count here).

- The first list picks what to show: *All differences*, *New in this log*, *More often*, *Gone from this log*, *Less often*, *Everything*. The second picks levels.
- **search messages** filters the rows.
- Double-click a row to find all its lines in the log that has them.

## Merge logs by time

**File → Merge Files by Time…** (Ctrl+Shift+M) merges several logs (say, the same app on two nodes, or an app log and its server's log) into one view in time order. Tick the open logs, or **Add Files…**.

- Each line starts with a label such as `<node2-app>` (the file name, with its folder when names clash) and has a stripe at the left in its source's colour. Hover the tab for the legend.
- The colours, in order: <span class="sw" style="background:#D4A800"></span> yellow, <span class="sw" style="background:#2FA84F"></span> green, <span class="sw" style="background:#3B82F6"></span> blue, <span class="sw" style="background:#8B5CF6"></span> purple, <span class="sw" style="background:#F97316"></span> orange, <span class="sw" style="background:#EC4899"></span> pink, <span class="sw" style="background:#14B8A6"></span> teal, <span class="sw" style="background:#DC2626"></span> red, then again.
- Right-click → **Show Only Lines from `<node2-app>`** shows one source.
- Stack traces stay with their line. The merged view is an ordinary log: filters, the timeline, messages and search all work on it. It keeps up while the sources grow, but lines that arrive late are added at the end, not put in time order.

A log with no timestamps can't be merged: *no timestamps found to merge by*.
