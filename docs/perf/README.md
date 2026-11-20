# Performance measurement

Phase 0 of the performance plan (20 Nov 2026): measure before changing anything, then re-run
after each change and compare.

## What is measured

| Benchmark | What | Metric |
|---|---|---|
| `StartupBenchmark.coldStart[None]` | Cold start of a fresh install, nothing compiled: the first launch a worker sees | `timeToInitialDisplayMs` |
| `StartupBenchmark.coldStart[BaselineProfile]` | Cold start with the shipped baseline profile applied. With no app profile shipping today this is close to None | `timeToInitialDisplayMs` |
| `StartupBenchmark.coldStart[Full]` | Everything compiled ahead of time: the ceiling | `timeToInitialDisplayMs` |
| `DemoFlowBenchmark.homeNavigation` | Home → Receive, Ship, Count, Activity and back | `frameDurationCpuMs`, `frameOverrunMs` |
| `DemoFlowBenchmark.scanOnHome` | 20 scans on Home, sent as Sunmi imager broadcasts | `frameDurationCpuMs`, `frameOverrunMs` |

Every run uses the **macrobench** build (`app/build.gradle.kts`):
- It is compiled like release.
- It has its own package, `com.d365fo.mobileops.bench`.
- Nothing from `local.properties` is compiled in, so it starts unconfigured.
- The flows run in demo mode, so no tenant is involved and every run sees the same data.

The startup runs clear the app's data before each launch.

`frameOverrunMs` is the number to watch for jank: how late each frame was against its deadline.
Values at or below 0 were on time.

## Running

Plug in the P2mini (or any device) with USB debugging on, then:

```powershell
tools\perf\run-benchmarks.ps1                          # all, ~15-20 min
tools\perf\run-benchmarks.ps1 -Class StartupBenchmark  # just startup
```

The script writes two files to this folder: `results-<date>-<device>.md` (the table) and the raw
`.json`. Commit both alongside the change they measure.

Emulator numbers only show which way a change moves things. The targets are measured on the
P2mini.

## Compose compiler reports

```powershell
.\gradlew.bat :app:assembleMacrobench -PcomposeReports
```

This writes `app/build/compose_compiler/*-composables.txt`, which lists each composable and
whether it can skip, and `*-classes.txt`, which shows the stability of each class. Use them to
check the recomposition fixes in Phase 2.

## Baseline

| Date | Device | Results |
|---|---|---|
| | | (first run pending) |
