# Changelog

All notable changes to Tandem are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0] - 2026-08-23

### Added

- **`FileStore` can fsync.** It takes a `Durability`: `OS_BUFFERED`, the default
  and what it has always done, or `SYNC_ON_EVERY_EVENT`, which forces every
  event and every step output to the device before returning, metadata included,
  and forces the run directory too on platforms that allow it. The guarantee used
  to be reachable only by writing your own store, since the version that forced
  anything lived in `bench/`. It costs about 3.3x on step throughput and about
  9.5x on a single append, measured in docs/BENCHMARKS.md, and the choice is the
  caller's because which failure you are buying against is a property of the
  deployment.

- **A read-only inspector.** `WorkflowInspector` reads what a store holds without
  running anything: the run ids it has, each run's classified state, and per run
  the ordered steps with their outcomes and any recorded output. It returns plain
  records and pairs with the durability story, letting someone look at a store
  after a restart without touching a definition. `InspectorCli` is a small
  command line over it, `list <dir>` and `show <dir> <runId>` against a
  `FileStore` directory, returning an exit code rather than calling
  `System.exit` from the logic so it stays testable.

  The state comes from the same rule the recovery scan uses, now shared in one
  place. Working without the definition costs two things, both stated rather than
  hidden: the inspector cannot see which steps carry a `Codec`, so a run that
  died inside a repeat-safe step is reported `IN_DOUBT` where the engine, given
  the definition, would call it `RESUMABLE`; and outputs come back in the store's
  encoded form, since decoding needs the codec.

- **Property and adversarial tests for the on-disk log.** The existing store
  tests work from examples. These generate the inputs nobody writes down by
  hand, and check the log against damage rather than against usage: every string
  survives a JSON line round trip, an encoded object never contains a raw line
  break, a log truncated at every byte offset in turn reads back as a prefix of
  itself, a single flipped byte costs at most the line it landed in, and
  concurrent appends to one run all land whole. They found the four bugs below.

- **A test that settles the delivery semantics with a real crash.**
  `DeliverySemanticsTest` forks a child JVM which performs a side effect and then
  calls `Runtime.halt`, ending the process with no shutdown hooks and nothing
  flushed that the runtime had not already written. The parent recovers from the
  same store directory and counts how many times the side effect happened. It
  establishes, by demonstration rather than by prose, that a step without a
  `Codec` runs again after a crash, that a step with one stops the resume with
  `StepInDoubtException` instead of repeating, that both `confirmCompleted` and
  `confirmNotCompleted` are reachable from there, and that passing a finished
  run's id back to `run` repeats every step without a codec.

- **`docs/BENCHMARKS.md`, and `bench/` to regenerate it.** `bench/run.sh` prints
  the machine it ran on next to its results and needs neither Maven nor a JDK on
  the path, fetching a portable Temurin into `bench/.jdk` if it has to. It
  measures append latency, durable step throughput, recovery time against log
  length, and a breakdown of where an append's time goes. Median and p99
  throughout, the harness stated plainly as not being JMH, and the raw output of
  the documented run committed as `bench/results.txt`.

- **`docs/BUGS.md` and `docs/DECISIONS.md`.** Every defect found so far with the
  test that caught it and the commit that fixed it, and the design choices with
  the alternatives they beat and the costs they still carry.

- **A nightly workflow.** The pull request build runs the suite once, which
  cannot find a test that fails one run in ten, and this project has now had two
  of those. The nightly runs the suite ten times across Java 17 and 21, and
  smoke tests the benchmark harness.

### Changed

- **A run's directory is established once and cached** instead of on every
  append, which was about 40% of an append in syscalls that are no-ops after a
  run's first event. An append to an established run went from 179.3 to 93.1 µs
  median on the machine in docs/BENCHMARKS.md. End-to-end throughput on short
  runs is unchanged, and that is stated there rather than dressed up. The cache
  is dropped and rebuilt if a write fails, so a directory removed from outside
  the process still costs one retry rather than the run.


### Fixed

- **`run()` with the id of a run that already succeeded is refused rather than
  resumed.** Resuming a completed run re-executes every step without a codec, so
  the side effect happened a second time, with no crash involved. `resume` has
  always refused this; `run` now raises the same `TandemException`. The README's
  delivery table row that read *twice* now reads *never*.

- **A torn log made the whole run unreadable, not just the torn line.**
  `eventsFor` read the file with a strict UTF-8 decoder, so a crash that tore the
  last append in the middle of a multi-byte character failed the decode of the
  entire file, and the `UncheckedIOException` came out in place of every event
  before the damage. Bytes are now decoded leniently, which confines the damage
  to the line holding it, where the per-line parse already dropped it. This is
  the case the append-only format existed to handle, and it did not handle it.

- **Two different run ids could share one directory.** Sanitising replaced every
  awkward character with `_` and appended a hash to say it had done so, but the
  result was itself a legal run id: `"a/b"` was stored as `"a_b-17234"`, so a run
  actually called `"a_b-17234"` appended to the first run's log and resumed from
  its history. Names are now escaped rather than replaced, `_` included, so a
  passed-through name never contains `_`, an escaped one always does, and the two
  cannot meet. Ids of letters, digits, `-` and an interior `.`, which is every
  UUID, are unaffected; ids containing `_` or an outer `.` change directory.

- **Concurrent appends to one run failed for no reason to do with the run.**
  `FileStore` records the run id beside its events on first append, checking for
  the file and then moving one into place. Two threads appending to the same run
  could both find it missing, and the loser's move threw
  `FileAlreadyExistsException`, which propagated out of `append` and, because
  store failures are deliberately fatal, took the run with it. Losing that race
  is now ignored: the winner wrote the same id, because it is the same run.

- **An unencodable character in an event stopped the run.** A lone surrogate,
  which is what truncating a message through the middle of an emoji leaves
  behind, was written to the log literally and UTF-8 could not encode it, so
  `append` threw and the run died over the spelling of an error message.
  Surrogates are now escaped like the other characters JSON cannot carry
  literally, and decode back to exactly what went in.

- **A flaky timeout test.** `aTimeoutIsRetriedLikeAnyOtherFailure` gave the
  attempt that answers instantly a 150ms budget, which a loaded machine could
  miss in scheduling alone; it failed roughly one full-suite run in ten. The
  budget is now far enough above scheduling jitter to mean what it says.

- **A second flaky test, and a worse one.**
  `SchedulerTest.closingAnOwnedSchedulerStopsFurtherRuns` slept 30ms, closed the
  scheduler, and sampled the run counter the instant `close` returned. On a
  loaded machine the schedule had often not fired at all in those 30ms, so the
  test proved nothing about `close`, and the sample was then overtaken by the
  firing already in flight, since `close` calls `shutdownNow`, which interrupts
  rather than waits. It failed 15 of 15 runs under load having passed cleanly an
  hour earlier. It now waits on a latch for proof the schedule is live, as every
  other test in the file does, and lets an interrupted firing settle before
  sampling.

## [1.1.0] - 2026-08-06

### Added

- **A per-step time bound**: a step that hangs used to hang the run, which is the
  one failure a recovery tool must not have. It now fails on its own deadline and
  leaves a record the resume path can read.

### Changed

- **The install snippet shows the published version**, rather than a number that
  drifted from whatever was actually on the registry.

## [1.0.0] - 2026-08-03

A crash can no longer repeat a recorded step, and what it left behind can be found, classified and resumed.

### Added

- **`recoverable` and `resume`: pick up what a crash left behind.** Resuming
  needed the run id, the `Workflow` and the original input, and a crash kept
  only the first, so durable resume could not be used in the situation it exists
  for. Stores persist the input now, through the same codec as any step output
  and never inferred, and `listRuns` enumerates what they hold.

  Each run comes back classified: `COMPLETED`, `FAILED`, `RESUMABLE`, or
  `IN_DOUBT` for one that died inside a recorded step, where the side effect may
  or may not have happened. Resuming an in-doubt run raises
  `StepInDoubtException` rather than guessing, since guessing means charging
  twice or never charging, and only the caller can ask which.

  `listRuns` returns the id you used rather than the directory name, which
  sanitising cannot give back, so the id is recorded rather than inferred.

### Fixed

- **`FileStore` reads a run back from the directory it wrote it to.** The write
  path sanitised the run id into a file name and the read path did not, so any
  id containing a character outside `[A-Za-z0-9._-]` wrote its events to one
  directory and looked for them in another. `eventsFor` returned nothing, the
  engine concluded the run was fresh, and every recorded step ran a second time,
  side effects included. `eventsFor` and `loadOutput` now sanitise the same way
  `append` and `saveOutput` always did, which also stops a run id resolving
  outside the store root. Sanitising is no longer lossy either: two ids that
  flatten to the same characters used to share a directory and interleave their
  event logs, so anything that needed replacing now carries a short tag derived
  from the original. Ids that need no replacement are unchanged on disk.

- **A crash inside a recorded step no longer repeats its side effect on
  resume.** `STEP_STARTED` was already written to the store before a step ran,
  but the engine read the log only to decide whether it was resuming and
  discarded the rest. A run that died between the side effect and the recorded
  output therefore left no trace the resume looked at, and ran the step again. A
  resume now stops at a step that was started with no outcome and no recorded
  output, failing with `StepInDoubtException` rather than repeating it.
- **A step's output is written before its `STEP_SUCCEEDED` event**, not after. In
  the old order, a crash between the two left a log that looked settled next to
  a missing output, which the resume would also have resolved by running the
  step a second time.
- **The documented guarantee now matches the code.** The README claimed a
  recorded step was "never charged twice" without qualification.
  [docs/durability.md](docs/durability.md) states the window, what each store
  does to it, and what is guaranteed instead.

### Added

- `WorkflowEngine.confirmCompleted` and `WorkflowEngine.confirmNotCompleted`,
  for settling a step left in doubt once you have checked the system it talked
  to. Without them a stopped run could never be resumed.
- `StepInDoubtException`, carrying the run id and step name that need a
  decision, and `EventType.STEP_IN_DOUBT`.

## [0.1.0] - 2026-08-02

First release.

### Added

- **Typed workflows.** A builder that carries each step's output type forward,
  so a mismatch between steps is a compile error. Definitions are immutable and
  safe to share across threads.
- **Retry policies.** `none`, `fixed` and `exponential`, the last capped at one
  minute by default, with `withMultiplier`, `withMaxDelay` and `withJitter`.
  Applied per workflow or per step. Jitter only ever shortens a delay.
- **Compensation.** Saga-style undo attached per step, run in reverse order when
  a later step fails, continuing if one compensation itself throws.
- **Durable resume.** A step declared with a `Codec` records its output; a
  resumed run decodes it rather than executing the step again. Steps without one
  are re-executed, and Tandem never guesses which kind a step is.
- **Stores.** `InMemoryStore`, and `FileStore` writing an append-only
  `events.jsonl` plus one file per recorded output, with outputs written to a
  temporary file and moved into place.
- **Observability.** `WorkflowListener` receives every event. Listener failures
  are swallowed; store failures are not.
- **Scheduling.** `Scheduler.after` and `Scheduler.every`, using fixed delay
  rather than fixed rate, on daemon threads.
- **No runtime dependencies**, enforced in CI. JUnit is test-scoped.
- Published to GitHub Packages as `io.github.martinkm:tandem`.

### Known limitations

Runs execute on the calling thread. Scheduling is in-process and does not
survive a restart or coordinate across machines. Steps are a straight line, with
no parallel or branching shapes. Resuming an edited definition matches recorded
outputs by step name and does not detect that the shape changed. See
[docs/roadmap.md](docs/roadmap.md).

[Unreleased]: https://github.com/martin-k-m/tandem/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/martin-k-m/tandem/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/martin-k-m/tandem/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/martin-k-m/tandem/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/martin-k-m/tandem/releases/tag/v0.1.0
