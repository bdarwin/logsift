# Changelog

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
