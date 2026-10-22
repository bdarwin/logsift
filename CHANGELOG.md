# Changelog

## Unreleased

- **Your own log formats:** drop a log file and Logsift works out its layout and how its times are written, or paste your log4j / logback / Python logging setup (a pattern, or a whole `logback.xml`, `log4j2.xml` or `log4j.properties`). Properties in the config (`${LOG_PATTERN}`, `${sys:PID}`) are filled in, Log4j 2 `.properties`, `.yaml` and `.json` configs work, and with several appenders the layout your log fits is used. A live preview shows how lines split into columns.
- **Format in the status bar:** shows how the current log is read. Click it to choose another format for that file if the detected one is wrong.
- **Tree of loggers:** loggers (or threads, components, hosts) as a package tree beside the log, with line counts. Uncheck a branch to hide its lines, double-click one to see only it.
- **Settings** (Ctrl+, / ⌘,): font, tab width, encoding for text that isn't UTF-8, how often files are checked for changes, reopening the last session, and more.
- Levels written as `E`, `ERR`, `error` or `SEVERE` count as errors in your own formats (colors, error marks, F8, level chips).
- **Open from WSL** (Windows): opens your Linux home folder.
- **Insights:** a new tab that shows at a glance what is going on in the log: error bursts (and when), new errors and when they began, silences, restarts, and noisy messages, each with buttons to go there, show or hide it. It fills in by itself and its title counts the problems.
- **Menus with icons, regrouped:** a new **Go** menu (Go to Line/Time, next error, bookmarks), and check marks on everything that turns on or off. New toolbar buttons for the tree of loggers and the timeline, and Settings (⚙).
- **Splash screen** while Logsift starts.
- **Windows: Open with Logsift** on the right-click menu of files and folders, and in the Open with list of `.log`, `.txt` and `.out` files. The installer no longer takes over `.log` files (their icon and double-click stay as they were). On macOS, Logsift is likewise offered in Open With without becoming the default.
- **Logs on network shares:** the window no longer freezes while a big file on a share is read. Lines on screen load in the background and appear as soon as they arrive.
- Clicking a long wrapped line that is partly off screen no longer scrolls the view.
- **Hide columns:** right-click a column header → *Hide … Column*, or tick columns on and off. A **N columns hidden** chip in the header lists them, and **Show All Columns** brings them back. Remembered per format.
- **Select text in the Columns view**, not just whole lines: drag across cells or the message, double-click a word.
- **Details shows the whole entry:** a line with its stack trace, not just the one line. Select text and Details shows just that: several lines, or part of one.
- **JSON in Details:** a **Text | JSON** switch pretty-prints the JSON in a line or selection (also JSON in the middle of a message), opening up JSON written inside strings. Switch back to see the text as it is.
- **Loading indicator:** lines still being read from a slow disk or share show as gray bars with a *Reading from disk…* badge, and an empty log being opened says so, instead of a blank view.
- **Config import reads more:** Log4j 1.x XML (`<param name="ConversionPattern" …/>`), java.util.logging (`SimpleFormatter.format`), every formatter in a Python config with its `datefmt`, and `basicConfig(…)` / `Formatter(…)` in Python code.
- **A config with several layouts makes a format for each** (named after the appender), instead of asking which one. Each log uses the one it fits.
- **Windows installer:** a new build installs over the one already there (snapshots used to need an uninstall first), and a running Logsift is closed before files are replaced or removed.
- Logsift now exits fully when its window is closed (it could stay running in the background).

## 1.1.0

- **Windows installer** (`.msi`): installs for the current user (no admin rights needed), adds a Start menu entry, and opens `.log` files.
- **Open .log files from your file manager:** double-click, Open With, or drop them on the Dock icon (macOS). Logsift registers for `.log` files on Windows, macOS and Debian/Ubuntu.
- **One window:** opening another file while Logsift is running opens it in the same window.
- **View another program's output:** `kubectl logs -f my-pod | logsift -` (or `docker logs -f`, `journalctl -f`, …) is captured as it arrives and followed like a growing log.
- **Help menu:** keyboard shortcuts (read from the menus, so always current), documentation, report a bug (with your version and system filled in), and About.
- **Update check:** a button in the toolbar when a new version is out. It is one anonymous request to GitHub at most once a day, and can be turned off in the Help menu.
- **Error window:** unexpected errors show their details with Copy and Report buttons instead of failing silently. They are also written to `~/.logsift/errors.log`.
- `logsift --version` and `logsift --help`.

## 1.0.0

First release: very large files, live tail, Find All with several searches and search within results, filters with AND / OR / NOT, timeline, highlight bar, columns, JSON logs, folder search (including .gz and .zip), remote logs over SSH/SFTP, top messages, compare and merge logs, bookmarks, alerts, export, sessions, encodings, and dark mode.
