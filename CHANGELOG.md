# Changelog

## Unreleased

- **Your own log formats:** drop a log file and Logsift works out its layout and how its times are written, or paste your log4j / logback / Python logging setup (a pattern, or a whole `logback.xml`, `log4j2.xml` or `log4j.properties`). Properties in the config (`${LOG_PATTERN}`, `${sys:PID}`) are filled in, Log4j 2 `.properties`, `.yaml` and `.json` configs work, and with several appenders the layout your log fits is used. A live preview shows how lines split into columns.
- **Format in the status bar:** shows how the current log is read. Click it to choose another format for that file if the detected one is wrong.
- **Tree of loggers:** loggers (or threads, components, hosts) as a package tree beside the log, with line counts. Uncheck a branch to hide its lines, double-click one to see only it.
- **Settings** (Ctrl+, / ⌘,): font, tab width, encoding for text that isn't UTF-8, how often files are checked for changes, reopening the last session, and more.
- Levels written as `E`, `ERR`, `error` or `SEVERE` count as errors in your own formats (colors, error marks, F8, level chips).
- **Open from WSL** (Windows): opens your Linux home folder.
- **Logs on network shares:** the window no longer freezes while a big file on a share is read. Lines on screen load in the background and appear as soon as they arrive.
- Clicking a long wrapped line that is partly off screen no longer scrolls the view.

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
