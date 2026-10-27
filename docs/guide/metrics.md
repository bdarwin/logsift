---
layout: guide
title: Metrics
description: The Metrics tab: how long things that start and end took, things that never ended, numbers in messages, and the chart's colours.
---

# Metrics

The **Metrics** tab (below the log) shows what the log measures, without any setup. Click **Refresh** to look again after the log grew.

![Order times charted over time, with a spike](../screenshots/metrics.png)

## What it finds

- **Things that start and end:** message kinds that open and close something (*Started job sync-7* … *Finished job sync-7*, *Processing request r-12* … *Completed request r-12*) are found by their words. Lines are paired by the value they share (the job, the request), or else by thread. The list says which: *took (by its job)* or *took (by thread)*. Hover for the two messages.
- **Numbers in messages:** a number with a unit after it (`ms`, `s`, `MB`, `%`, `rows`), or a word like *took*, *size*, *latency* or *queue* before it: *order … placed in **#** ms*, *queue size=**#***. Order numbers and IDs are not measurements. A message kind needs 20 lines or more. Durations are in milliseconds and sizes in bytes, whatever unit each line used.

## The list

| Column | Shows |
|---|---|
| **Measurement** | A timer icon for things that start and end, a chart icon for numbers. The **#** marks the measured part |
| **Count** | How many |
| **Median** | Half are below this |
| **95%** | 95 in 100 are below this |
| **Max** | The largest |
| **No end** | Things that started and never ended, in <span class="sw" style="background:#E5484D"></span> red. Often what hung |

## The chart

Select a measurement for a chart over time. Hover a dot for its time and value; click it to go to its line.

![How long jobs took, with the ones that never ended marked along the top](../screenshots/metrics-durations.png)

| Mark | Light | Dark | Means |
|---|---|---|---|
| blue dots | <span class="sw" style="background:#2563EB"></span> | <span class="sw" style="background:#60A5FA"></span> | Each value |
| orange dots | <span class="sw" style="background:#D97706"></span> | <span class="sw" style="background:#FB923C"></span> | A value 3 times the median or more: far above usual |
| red line | <span class="sw" style="background:#DC2626"></span> | <span class="sw" style="background:#F87171"></span> | The 95th percentile over time (with 40 values or more) |
| red marks along the top | <span class="sw" style="background:#DC2626"></span> | <span class="sw" style="background:#F87171"></span> | Things that started then and never ended |
| small triangles at the top | | | Values too big for the chart's height |

**How to read it:** a red line that climbs means things got slower for most, not just a few. A cloud of orange dots at one time is a slow spell: compare it with the [timeline](timeline.md) and [Insights](insights.md).

## Beside the chart

The lines that never ended, then the slowest (or largest) ones: **Time**, **Value**, **Which**. Click one to go to its line.

## In Insights

[Insights](insights.md) reports what never ended (*1 job started and never ended*) and when a measurement was far above usual (*High: order … placed in # ms, 10:15:00 – 10:16:59*: the median of that stretch was 3 times the usual median or more), with **Show these minutes** and **Show in Metrics**. For files over 512 MB this is worked out when the Metrics tab is opened.
