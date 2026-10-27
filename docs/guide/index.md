---
layout: guide
title: User guide
description: What each Logsift view shows, what its colours and numbers mean, and what to do about them.
---

# Logsift user guide

This guide explains what you see on screen in Logsift: each view, its columns, numbers, colours and buttons, and what to do about what it shows. Colours are always given with their name too, so you don't need to tell them apart to follow along.

New to Logsift? Start with [Opening logs](opening-logs.md) and [The log view](log-view.md). In the app, **Help → Documentation** opens this page.

## Logs

| Page | What it covers |
|---|---|
| [Opening logs](opening-logs.md) | Files, folders, zips, `.gz`, web addresses, WSL, piped output, and logs on a server over SSH/SFTP |
| [The log view](log-view.md) | Lines and columns, level colours, line numbers, the highlight bar, JSON and CSV, the tree of loggers, bookmarks, merged logs |
| [Find](find.md) | The find bar, Find All, several searches at once, searching within results |
| [Filters](filters.md) | Show, Hide and Mark filters, the formula, chips, level chips, stack traces, context lines, alerts |
| [Timeline](timeline.md) | The bar of lines over time, its colours, and picking a time range |
| [Insights and messages](insights.md) | Error bursts, new errors, silences, restarts, noise, known problems; top messages |
| [Metrics](metrics.md) | How long things took, things that never ended, numbers in messages, the chart |
| [Exceptions](exceptions.md) | Exceptions grouped by root cause, the stack, opening your source code |
| [Requests](requests.md) | Following one request through a log, all open logs or a folder |
| [Compare and merge](compare.md) | A bad run against a good one; several logs merged by time |
| [Folder search](folder-search.md) | Searching every file in a folder, inside zips and `.gz` files too |

## Java and Python

| Page | What it covers |
|---|---|
| [JVM connections](jvm.md) | Attaching to a JVM on this computer, over SSH or a JMX port; the bar that names a log's JVM; what each action costs a running service |
| [GC logs](gc.md) | The GC tab: every tile, finding, colour and chart, and the thresholds behind them |
| [Thread dumps](threads.md) | Findings (deadlocks, lock queues, stuck and busy threads), the thread list, state colours, timeline, compare |
| [Profiling and flame graphs](profiling.md) | What to measure, reading a flame graph and its colours, comparing runs |
| [Heap](heap.md) | Live heap histograms, heap dumps (what fills the heap, why it is alive, map, waste, classes), comparing dumps, dumps on a server |

## Everything else

| Page | What it covers |
|---|---|
| [Settings](settings.md) | Every setting and where Logsift keeps its files |
| [Keyboard shortcuts](shortcuts.md) | All the keys, by area |
| [Troubleshooting and FAQ](troubleshooting.md) | Messages you may see, and what to do |

## Colours at a glance

Most views share a few colours:

- <span class="sw" style="background:#E5484D"></span> **red** means errors, or something bad (for example a GC pause of a second or more).
- <span class="sw" style="background:#F5A524"></span> **amber** means warnings, or something to keep an eye on.
- <span class="sw" style="background:#16A34A"></span> **green** means fine.
- <span class="sw" style="background:#2563EB"></span> **blue** or <span class="sw" style="background:#9CA3AF"></span> **gray** means for information only. Gray text in a log means lines your filters leave out, or lines shown only for context.

Each page lists the exact colours of its view. Where dark mode uses other shades, the page says so.
