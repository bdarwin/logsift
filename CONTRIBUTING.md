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

### Test data to try by hand

Test data isn't checked in. This writes it all into `test-data/`, which git ignores:

```bash
./gradlew testData                        # logs, CSVs, crash reports and dumps, GC logs, thread and heap dumps
./gradlew testData -PbigMb=20,200,1024    # sizes of the big logs, in MB (default 20,200)
./gradlew testData -Pquick                # skip what runs a JVM (heap dumps, live GC logs, live thread dump)
./gradlew testData -Pfresh                # write everything again (otherwise only what's missing)
./gradlew testJvms --console=plain        # JVMs to attach to: deadlocks, leaks, JMX on 127.0.0.1:9010; Enter stops them
```

`test-data/README.md` lists what's in each folder. Open the data with `-PtestSettings`, so your
own settings, recent files and `~/.logsift` stay out of it (that Logsift keeps its own in
`build/try-home`):

```bash
./gradlew run -PtestSettings --args="test-data/logs/orders-service.log"
```

## How the code is organized

- `logsift.core`: indexing, search, filters, formats, tail and remote logs. It has no UI code and is tested against simple reference code.
- `logsift.ui`: the Swing interface (FlatLaf).
- [docs/how-it-works.md](docs/how-it-works.md) explains the design.

## Pull requests

- Keep changes focused, and add a test: a unit test for `core` code, or a step in `VisualTest` for UI changes.
- `./gradlew test` must pass. CI also runs the visual tests on Linux and Windows.
- Changes users can see also update [docs/guide](docs/guide/index.md), the user guide (colours and thresholds there come from the code; screenshots come from `VisualTest`).
- Describe what changed and why in plain words.

By contributing, you agree that your contributions are licensed under the [MIT license](LICENSE).
