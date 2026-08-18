# CLAUDE.md: tandem

Context for AI agents working in this repository.

## What this is

**Tandem** is a workflow orchestration framework for Java: typed sequential
steps, retry policies, saga-style compensation, durable resume, in-process
scheduling and listener-based observability. Published to **GitHub Packages** as
`io.github.martinkm:tandem`. Repo: <https://github.com/martin-k-m/tandem>.

Maven, not Gradle, and GitHub Packages rather than Maven Central. Central needs
a Sonatype account and GPG signing; Packages needs neither and was the point of
the exercise, since it is one of the six registries GitHub actually hosts.

## The two rules

**1. No runtime dependencies.** A workflow engine sits in the middle of somebody
else's dependency tree, and the fastest way to be unusable there is to drag in a
JSON library or a logging facade that fights the one they already have. This is
why `Codec` exists instead of a mapper, `JsonLine` instead of Jackson, and
`WorkflowListener` instead of SLF4J. JUnit is test-scoped and never reaches
consumers. CI fails if anything appears at compile or runtime scope.

**2. Nothing is stubbed.** Distributed coordination, parallel steps, persistent
timers and definition versioning are absent, and `docs/roadmap.md` says so. Do
not add a method that accepts a call and quietly does nothing.

## Build and test

```sh
mvn verify          # tests, plus sources and javadoc jars, so bad javadoc fails here
mvn test
```

CI builds on Java 17 and 21. The pom's floor is 17; raising it means changing
`maven.compiler.release` and the README badge.

## Layout

```
src/main/java/io/github/martinkm/tandem/
  Workflow, WorkflowBuilder, StepDefinition   definition and the typed builder
  Step, StepContext, Compensation, Codec      what a user implements
  RetryPolicy, Sleeper                        backoff, injectable for tests
  WorkflowEngine, RunResult                   execution, retries, replay
  WorkflowInspector, InspectorCli             read-only view of a store, plus a CLI
  WorkflowStore, InMemoryStore, FileStore     durability
  WorkflowEvent, EventType, WorkflowListener  the log and observability
  JsonLine                                    the file store's line format
  Scheduler                                   delayed and repeating runs
```

## Invariants worth not breaking

- **A codec is the only thing that makes a step replayable.** Never infer it.
  Guessing means either re-charging a card or skipping work that never happened.
- **The write order inside a step attempt is the durability fix**, not
  incidental: `STEP_STARTED` before the step runs, the output before
  `STEP_SUCCEEDED`. A resume reads the log and stops at a recorded step that was
  started with no outcome and no output. Move either write and the double-charge
  window reopens; there are tests for both halves.
- **Compensations run in reverse and keep going** when one throws. Stopping
  leaves more undone than continuing.
- **Store failures propagate, listener failures do not.** Durability is why a
  store was chosen; observability is not worth failing a business process for.
- **`InterruptedException` restores the interrupt flag** and ends the run.
  Swallowing it strands whoever asked the thread to stop.
- **Step names key the recorded outputs**, so duplicates are rejected at build
  time or one step would replay another's result.
- **`FileStore` escapes names into file names, on reads as well as writes.**
  It used to escape only on the way in, so a run id containing anything outside
  `[A-Za-z0-9._-]` wrote its events to one directory and read them from another:
  `eventsFor` came back empty, the engine called the run fresh, and every
  recorded step ran again. `../` must not escape the root, for run ids as well as
  step names, and two distinct ids must never share a directory. The escaping is
  reversible for that reason: `_` plus four hex digits for anything that cannot
  go in a file name, `_` included, so an untouched name never contains `_` and an
  escaped one always does. Replacing characters and appending a hash instead was
  not injective, because the escaped spelling was itself a legal id.
- **A damaged log costs the damaged line and nothing else.** Both halves matter:
  bytes are decoded leniently, or a torn multi-byte character fails the whole
  file, and each line is parsed inside its own try, or one bad line ends the
  read. There are property tests for both.
- **A run whose log ends in `RUN_SUCCEEDED` is refused, by `run` as well as by
  `resume`.** Resuming a finished run re-executes every step without a codec,
  which is work that already happened, and it needs no crash to reach.
- **The run directory cache is keyed by run id and dropped when a write fails.**
  It exists because establishing the directory was 40% of an append. It has to
  be safe under concurrent appends to one run, must never hand one run another's
  directory, and must not turn an externally removed directory into a dead run.
- **`Scheduler.every` uses fixed delay, not fixed rate.** Fixed rate turns a slow
  run into a stampede.

## Gotchas

- A step lambda whose body only throws gives javac nothing to infer the output
  type from, so it resolves to `Object` and will not match the declared workflow
  type. Use an explicit witness: `.<String>step(...)`. This bit the test suite on
  the first CI run.
- `Compensation` receives `Object`, because compensations sit alongside
  type-erased steps. Documented rather than papered over.

## Conventions

- **No em dashes** anywhere: javadoc, comments, docs, commit messages.
- **Javadoc explains the decision, not the signature.** Why the cap on
  exponential backoff, why hooks over `next()`, why a failed run is a value.
- **State the limits.** The README names the calling-thread execution and the
  in-process scheduler directly. Do not soften either.

## Environment note

The machine this was written on has **no JDK and no Maven**, but Docker runs, so
build in a container rather than waiting on CI:

```sh
docker run --rm -v "$(pwd):/w" -w /w maven:3.9-eclipse-temurin-17 mvn -B verify
```

That gives real test counts in about a minute. Use `-q` sparingly: it hides the
per-class `Tests run:` lines that tell you a suite actually executed.
