---
layout: guide
title: Assistant
description: Ask GitHub Copilot about your logs beside them, in Logsift's window. It reads them with Logsift's tools, cites lines you can click, shows what each answer cost, and keeps every chat.
---

# Assistant

Logsift opens the assistant when it starts, in a tab of its own. **View → Assistant** (Ctrl+I, ⌘I on macOS), or **Ask** in the status bar, brings it to the front; pressing it again goes back to the log. Ask anything about what's open, or about your services' logs: *why did checkout fail at 10:42?*, *errors by hour and what changed*, *compare this log with yesterday's*. GitHub Copilot answers with Logsift's own tools, the ones [coding agents](agents.md) use, on the logs the window has open, so nothing is read twice and line numbers are the ones you see.

![The assistant beside a log: the question, the lines Logsift read for it, and the answer with links to them](../screenshots/assistant.png)

## What you need

- **GitHub Copilot**, installed and signed in: `npm install -g @github/copilot`, then run `copilot` once in a terminal and type `/login`. Your Copilot subscription pays for the answers. Logsift finds the program where npm, Homebrew, nvm or WinGet put it; elsewhere, choose it with **Choose Copilot Program…**.
- Nothing else. Copilot starts the first time you look at the assistant, never before: a tab in the background costs nothing.

## Where it opens

In a tab of its own, beside the logs' tabs, or at the side of the window next to the log shown: the button beside the gear in the chat's header moves it, and Logsift remembers where. In its tab, it asks about the log you looked at last, so **Ask the Assistant About This Line** and the chips work as they do beside it, and a line link takes you to the log.

It opens when Logsift starts, in front only when nothing else was reopened (with the **Start** page beside it). Turn that off in **Settings → Assistant**.

![The assistant's tab when Logsift starts: questions to start with, for the services it knows, and what to set up](../screenshots/assistant-welcome.png)

With nothing asked yet, it offers questions to start with, for what's open and for your services (*What went wrong in login this morning?*), and what you can set up: **Log Locations…** (where each service's logs are), **Assistant Settings…** (what it's told) and the model.

## Asking

- **Enter** asks; **Shift+Enter** starts a new line. While it answers, the button stops it.
- What you're looking at goes with the question: the log shown, the other logs open, and the lines you selected. The chips above the box say what; **×** leaves one out of the next question.
- **Right-click a line** (or selected lines) and choose **Ask the Assistant About This Line**: the question waits in the box, with the lines chosen, for you to change or send.
- Each question also goes with the date, time and time zone now, so *this morning* or *an hour ago* means what you mean, read in each service's own time zone.

## The answer

- **Lines are links.** The assistant cites each line it relies on, like *checkout.log:1201-1209*; a click selects those lines in the log (showing all lines if filters hide them).
- **Each tool it calls is shown** (*read lines 3–4 in log:1 ✓ 9 ms*). A click shows what Logsift answered, with **Show these lines**, **Find in the log** (for a search) or **Open in Query** (for SQL, run in the Query tab), and **Copy**.
- **What it cost** is above each answer: the model, the AI credits Copilot reported for it, the tokens, and how long it took.

## Models and credits

The list at the bottom has the models your Copilot account offers, each with its price in AI credits per million tokens read · written. A model your organization turned off is greyed. Models that can think harder have a second list (*low*, *medium*, *high*…): harder is slower and costs more. Your choice is kept for new chats; changing it mid-chat applies from the next question.

The header shows what this chat cost and how much of your premium requests is left. Click it for the details: premium requests used and when they start again, this chat, today, and this month by model.

## Every chat is kept

Chats are kept on your computer, in a DuckDB file in Logsift's folder (`~/.logsift/chat/chats.duckdb`), with each question, answer, tool call and its result, and what it cost. A long chat opens at its end and scrolls back a page at a time, as far as it goes.

**Chats** (the clock button) lists them, newest first, with what each cost. **Search every chat** finds words in any question, answer or tool result and opens the chat at that very entry. Right-click a chat to delete it. Double-click a chat's name to rename it. **New chat** (the pen) starts another.

![Search across every chat](../screenshots/assistant-search.png)

## What it's told

Before the first question of each chat, the assistant reads Logsift's instructions, and what you choose in **Settings → Assistant**:

| Part | What it gives the assistant | On by default |
|---|---|---|
| **Logsift's instructions** | How to read logs with Logsift's tools, and to cite lines as links. Always on. | yes |
| **Where your logs are** | Each service you described in [Log Locations for Agents](agents.md): its folders, what its files hold, its logs' time zone and your notes. *The login service* then needs no path. | yes |
| **Step-by-step plans** | How to investigate a problem, explain a crash, compare a bad run with a good one, find what's slow, check a service's health: the plans agents get as prompts. | yes |
| **The date and time** | The time now with each question, and how to turn *this morning* into the log's own times. | yes |
| **Your own instructions** | Anything else: names you use (*“login” is the auth service*), what's normal, how to answer. | when written |

Each part shows about how many tokens it is, and **Show…** shows its words; **Show Everything It's Told…** shows them together. Each question is read with them again, so what's on is part of what every answer costs; all of it is about 2,000 tokens, a small part of what Copilot reads for its tools. Changes apply to chats started or opened from then on.

![Settings → Assistant: where it opens, and what it's told, part by part](../screenshots/settings-assistant.png)

## What it can and can't do

The assistant has **only Logsift's tools**: it reads logs, tables, dumps and profiles, within the [folders agents may read](agents.md#what-it-cant-do), and Logsift masks secrets in what it sees, as set in **Settings → Agents**. It can't run commands, change files or browse the web. Each tool call is written to the agents' audit log (`~/.logsift/agent.log`). Copilot runs as you, from its own folder (`~/.copilot`) where your sign-in is, so its sessions for these chats are kept there too, beside your terminal ones.

*GitHub Copilot is a trademark of GitHub, Inc. Logsift is not affiliated with or endorsed by GitHub.*
