# Contributing to Tandem

Thanks for taking a look. Tandem is workflow orchestration for Java with **no
runtime dependencies** — durable runs, retries, compensation, and scheduling on
the standard library alone. That constraint is the project, so keep it.

## Setup

You need JDK 17 or newer and Maven.

```bash
git clone https://github.com/martin-k-m/tandem
cd tandem
mvn --batch-mode verify
```

`verify` runs the full test suite; it is the same gate CI applies.

## Ground rules

- **Zero runtime dependencies.** The `pom.xml` carries test-scoped dependencies
  only; the shipped jar depends on nothing but the JDK. CI enforces this by
  listing dependencies and failing on an unexpected runtime one, so a new
  `compile`/`runtime` dependency will break the build on purpose.
- **Durability is the promise.** A run must survive a process restart and resume
  where it left off. Anything touching persistence, retries, or compensation
  states the guarantee it relies on and comes with a test that kills and resumes
  a run rather than only asserting the happy path.
- **Compensation is ordered.** When a step fails, already-completed steps are
  compensated in reverse. Keep that ordering explicit and tested.
- **Scheduling is deterministic in tests.** Drive time through the injectable
  clock rather than real sleeps, so the suite stays fast and reproducible.

## Before you open a pull request

CI tests a matrix of JDK versions; run the gate locally first:

```bash
mvn --batch-mode verify
```

Keep pull requests focused. A change to the durable state format is easier to
review with a test that writes with the old shape and reads with the new.

## Reporting bugs

Open an issue with a minimal workflow definition, the sequence of failures or
restarts that triggers the problem, and what you expected to resume versus what
happened. A failing JUnit test is the most useful report there is.
