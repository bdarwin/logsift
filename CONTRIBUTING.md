# Contributing to Logsift

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/bdarwin/logsift/issues/new/choose). The quickest way is **Help → Report a Bug** in Logsift, which fills in your version and system. If Logsift showed an error window, include its details (**Copy Details**). Please remove anything private from sample logs.

## Building and testing

You need JDK 21 or newer.

```bash
./gradlew run --args="/path/to/app.log"   # run from source
./gradlew test                            # unit tests (fast)
./gradlew visualTest -PsizeMb=200         # drives the real window; screenshots go to build/screenshots
```

## How the code is organized

- `logsift.core`: indexing, search, filters, formats, tail and remote logs. It has no UI code and is tested against simple reference code.
- `logsift.ui`: the Swing interface (FlatLaf).
- [docs/how-it-works.md](docs/how-it-works.md) explains the design.

## Pull requests

- Keep changes focused, and add a test: a unit test for `core` code, or a step in `VisualTest` for UI changes.
- `./gradlew test` must pass. CI also runs the visual tests on Linux and Windows.
- Describe what changed and why in plain words.

By contributing, you agree that your contributions are licensed under the [MIT license](LICENSE).
