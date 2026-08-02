# CLAUDE.md: tandem

Context for AI agents working in this repository.

## What this is

**Tandem** is a workflow orchestration framework for Java: typed sequential
steps, retry policies, saga-style compensation, durable resume, in-process
scheduling and listener-based observability. Published to **GitHub Packages** as
`me.blinkdev:tandem`. Repo: <https://github.com/martin-k-m/tandem>.

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
src/main/java/me/blinkdev/tandem/
  Workflow, WorkflowBuilder, StepDefinition   definition and the typed builder
  Step, StepContext, Compensation, Codec      what a user implements
  RetryPolicy, Sleeper                        backoff, injectable for tests
  WorkflowEngine, RunResult                   execution, retries, replay
  WorkflowStore, InMemoryStore, FileStore     durability
  WorkflowEvent, EventType, WorkflowListener  the log and observability
  JsonLine                                    the file store's line format
  Scheduler                                   delayed and repeating runs
```

## Invariants worth not breaking

- **A codec is the only thing that makes a step replayable.** Never infer it.
  Guessing means either re-charging a card or skipping work that never happened.
- **Compensations run in reverse and keep going** when one throws. Stopping
  leaves more undone than continuing.
- **Store failures propagate, listener failures do not.** Durability is why a
  store was chosen; observability is not worth failing a business process for.
- **`InterruptedException` restores the interrupt flag** and ends the run.
  Swallowing it strands whoever asked the thread to stop.
- **Step names key the recorded outputs**, so duplicates are rejected at build
  time or one step would replay another's result.
- **`FileStore` sanitises names into file names.** `../` must not escape the
  root; there is a test for it.
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

The machine this was written on has **no JDK and no Maven**, so nothing here was
compiled locally. CI is the compiler. Expect to iterate through GitHub Actions
and read the Maven output from the logs.
