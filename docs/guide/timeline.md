---
layout: guide
title: Timeline
description: The timeline above a log, its colours, and picking a time range.
---

# Timeline

When a log has timestamps, a timeline above it shows how much was logged over time, so incidents stand out at a glance.

![A selected time range on the timeline, with errors filtered and their stack traces kept](../screenshots/timeline.png)

## Colours

| Bar | Light | Dark | Means |
|---|---|---|---|
| gray-blue | <span class="sw" style="background:#B4C3DA"></span> | <span class="sw" style="background:#46526A"></span> | All lines in that stretch of time |
| amber | <span class="sw" style="background:#F5A524"></span> | <span class="sw" style="background:#F5A524"></span> | Warnings |
| red | <span class="sw" style="background:#E5484D"></span> | <span class="sw" style="background:#E5484D"></span> | Errors, drawn in front |
| blue band | <span class="sw" style="background:#3B82F6"></span> | <span class="sw" style="background:#60A5FA"></span> | The time range you selected |

A see-through box shows the part of the file on screen. Hover a bar for its time, and how many lines, errors and warnings it has.

**How to read it:** a tall red bar is an error burst. A gap with no bars is a time nothing was logged: the program was quiet, stuck or not running. [Insights](insights.md) points out both.

## Using it

- **Click** to jump to that time.
- **Drag** across it to show only that time range. A blue label shows the range; click its **✕** (or right-click → **Show All Times**) to show all times again. The range also shows as a **Time:** chip in the log's bar.
- The range works with the filters: drag over an incident, add an ERROR filter, and you see just the errors of the incident.
- **Go → Go to Time…** (Ctrl+Shift+L) takes `10:05`, `10:05:30.250` or `2026-10-21 10:05`.
- The status bar shows the time of the current line.
- Turn the timeline off with the Timeline button in the log's bar, or in [Settings](settings.md).

## Times it understands

`2026-10-21 10:02:13`, ISO with `T`, `2026/10/21`, `21-10-2026`, `21.10.2026`, `21-Oct-2026`, syslog `Oct 21 10:02:13`, Apache `[21/Oct/2026:10:02:13 +0000]`, times inside JSON lines, time only, and epoch seconds or milliseconds.

Times are shown as written in the log: time zones are not converted. When a log has no times, the timeline isn't shown.
