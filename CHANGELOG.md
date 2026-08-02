# Changelog

All notable changes to Tandem are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
