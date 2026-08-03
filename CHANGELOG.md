# Changelog

All notable changes to Tandem are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/martin-k-m/tandem/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/martin-k-m/tandem/releases/tag/v0.1.0
