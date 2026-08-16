# Tandem

**Workflow orchestration for Java. Durable runs, retries, compensation and scheduling, with no runtime dependencies.**

[![CI](https://github.com/martin-k-m/tandem/actions/workflows/ci.yml/badge.svg)](https://github.com/martin-k-m/tandem/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/martin-k-m/tandem?sort=semver&display_name=tag&style=flat-square&label=release&color=7C6CFF)](https://github.com/martin-k-m/tandem/releases)
[![GitHub Packages](https://img.shields.io/badge/GitHub%20Packages-io.github.martinkm%3Atandem-1F883D?style=flat-square&logo=apachemaven&logoColor=fff)](https://github.com/martin-k-m/tandem/packages)
[![Java](https://img.shields.io/badge/java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=fff)](https://openjdk.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-4F8CFF?style=flat-square)](LICENSE)
[![Dependencies](https://img.shields.io/badge/runtime%20dependencies-0-7C6CFF?style=flat-square)](pom.xml)

A business process that spans several systems has to survive each of them
failing. Tandem gives you that as a definition you can read: a sequence of typed
steps, each with its own retry policy and its own way to undo itself, executed
against a log that lets an interrupted run pick up where it stopped.

```java
Workflow<Order, Receipt> checkout = Workflow.<Order>named("checkout")
        .retry(RetryPolicy.exponential(3, Duration.ofMillis(200)))
        .step("charge", (Order order, StepContext ctx) -> payments.charge(order), Codec.ofString())
        .compensate((chargeId, ctx) -> payments.refund((String) chargeId))
        .step("reserve", (String chargeId, StepContext ctx) -> stock.reserve(chargeId))
        .step("receipt", (Reservation r, StepContext ctx) -> new Receipt(r))
        .build();

RunResult<Receipt> result = new WorkflowEngine(new FileStore(Path.of(".tandem"))).run(checkout, order);
```

If `reserve` fails every attempt, the charge is refunded before the run returns.
If the process dies instead, running the same run id again replays the charge
from its recorded output and carries on. If it died inside the charge, before
anything was recorded, the resume stops there and asks rather than charging a
second time.

## Install

```xml
<dependency>
  <groupId>io.github.martinkm</groupId>
  <artifactId>tandem</artifactId>
  <version>1.1.0</version>
</dependency>
```

Published to GitHub Packages. Consuming it needs a `<repository>` entry pointing
at `https://maven.pkg.github.com/martin-k-m/tandem` and a GitHub token, because
GitHub Packages requires authentication even for public artifacts. See
[docs/install.md](docs/install.md).

## What is in it

| | |
| :-- | :-- |
| **Typed steps** | Each step's output is the next one's input, checked at compile time |
| **Retries** | Fixed or exponential, capped, with optional jitter, per step or per workflow |
| **Timeouts** | A step that hangs is abandoned rather than hanging the run |
| **Compensation** | Saga-style undo, in reverse order, continuing if one fails |
| **Durable resume** | Steps with a `Codec` record their output, and a resume replays it rather than running the step |
| **Stores** | In-memory and append-only file, or your own implementation |
| **Observability** | Every event reaches listeners and the store, so metrics and audit fall out |
| **Inspection** | Read what a store holds, and a small CLI over it, without running anything |
| **Scheduling** | Run later, or repeat at a fixed delay |

## Finding what a crash left behind

`resume` needs a run id, a workflow and the original input, and a crash keeps
only the run id. So the engine can find them for you:

```java
for (RecoverableRun<String, String> run : engine.recoverable(checkout)) {
    switch (run.state()) {
        case RESUMABLE -> engine.resume(run);
        case IN_DOUBT  -> alertSomebody(run.runId(), run.stepInDoubt().orElseThrow());
        case FAILED, COMPLETED -> { }
    }
}
```

`IN_DOUBT` means the process died inside a recorded step, so the side effect may
or may not have happened. Tandem stops rather than guessing: guessing means
either charging twice or never charging, and only you can ask the payment
provider which it was.

## Looking without running

Recovery needs the definition, because continuing a run needs it. Looking is a
smaller question, so `WorkflowInspector` answers it from the store alone: which
runs it holds, what state each is in, and how far each got. It returns records
and never runs a step, resumes a run or writes to the store.

```java
WorkflowInspector inspector = new WorkflowInspector(new FileStore(Path.of(".tandem")));
for (WorkflowInspector.RunSummary run : inspector.list()) {
    System.out.println(run.runId() + " " + run.status());
}
inspector.describe("order-4417").ifPresent(run -> {
    System.out.println(run.workflowName().orElse("(unrecorded)") + " " + run.status());
    run.steps().forEach(step -> System.out.println("  " + step.name() + " " + step.outcome()));
});
```

There is a command line over it, pointed at a `FileStore` directory:

```sh
java -cp target/tandem-1.1.0.jar io.github.martinkm.tandem.InspectorCli list .tandem
java -cp target/tandem-1.1.0.jar io.github.martinkm.tandem.InspectorCli show .tandem order-4417
```

Two limits are worth stating. Working without the definition, the inspector
cannot see which steps carry a `Codec`, so a run that died inside a step with no
codec, one the engine would call `RESUMABLE` because repeating it is safe, is
reported `IN_DOUBT` here. It never resumes, so that is a label and not a
decision. And outputs come back in the store's encoded form, since decoding
needs the codec the definition holds.


## What is not

Distributed coordination, leader election, a worker protocol, dead letter
queues, a persistent timer service, parallel or branching steps, and versioning
of a definition against in-flight runs. Those are in
[docs/roadmap.md](docs/roadmap.md), absent rather than stubbed.

The two limits worth knowing before you adopt it: **a run executes on the thread
that called it**, so long workflows occupy that thread, and **scheduling is
in-process**, so two instances of your application both scheduling the same
recurring workflow will each fire it.

## The durability model

This is the part worth understanding, because it is the part that is explicit
rather than magic.

A step declared with a `Codec` has its output written to the store when it
succeeds. Resuming a run decodes that output and moves on without executing the
step. A step declared without one is executed again.

```java
.step("charge", chargeCard, Codec.ofString())   // recorded, replayed on resume
.step("format", formatReceipt)                  // pure, cheap to repeat
```

Tandem does not guess which kind a step is. Inferring it would mean either
re-running payment calls or silently skipping work that never happened, and both
of those are worse than asking you to say which one you meant.

### When the process dies inside a step

Running a step and recording its output are two writes to two systems. Nothing
short of a transaction spanning both can make them one, so there is an instant
where the work has happened and nothing says so.

Tandem writes the intent first. `STEP_STARTED` reaches the store before the step
is called, so a resume can see that a step was entered and never finished. When
it sees that, it stops at that step with a `StepInDoubtException` rather than
running it again. Whether the charge went through is a question only the payment
provider can answer, so you answer it:

```java
engine.confirmCompleted("order-4417", "charge", chargeId, Codec.ofString());  // it happened
engine.confirmNotCompleted("order-4417", "charge");                          // it did not
```

So the guarantee is not "never charged twice" under all circumstances, and any
library claiming that without a distributed transaction is overselling. It is
this: **a recorded step is never repeated by a resume unless you say it should
be, and an outcome the log cannot settle is surfaced instead of guessed.** The
residual window, and what each store does to it, is in
[docs/durability.md](docs/durability.md).

## Delivery guarantees

**Tandem is at-least-once. It is not exactly-once, and your steps still need to
be idempotent.**

That is the claim this kind of library is most often wrong about, so it is
settled here by demonstration rather than by prose.
[`DeliverySemanticsTest`](src/test/java/io/github/martinkm/tandem/DeliverySemanticsTest.java)
crashes for real: a child JVM performs a side effect and then calls
`Runtime.halt`, which ends the process immediately, with no shutdown hooks and
nothing flushed that the runtime had not already written. The parent then
recovers from the same store directory and counts how many times the side effect
actually happened.

| What happens | Times the side effect happened |
| :-- | :-- |
| Step **without** a `Codec`, crash just after the side effect | **twice** |
| Step **with** a `Codec`, crash just after the side effect | **once**, and the resume stops with `StepInDoubtException` |
| ...then you call `confirmCompleted` | **once**, and the run finishes |
| ...then you call `confirmNotCompleted` | **twice**, because that is what you asked for |
| `run()` called again with a run id that already **finished** | **twice** |

Read the last row twice. No crash is involved: passing a finished run's id back
to `run` resumes it, and resuming repeats every step that has no codec. Only
`resume(RecoverableRun)` refuses a completed run, because only it is handed the
classification that says so.

What Tandem gives you is narrower than exactly-once and more useful than
nothing: **a step you declared recorded is never repeated silently, and the
cases the log cannot settle become a question instead of a guess.** Getting from
there to an effect that happens exactly once is your side of the contract, and
Tandem hands you a stable idempotency key to do it with:
`StepContext.runId()` plus `stepName()`.

## Documentation

| Document | What it covers |
| :-- | :-- |
| [docs/install.md](docs/install.md) | Consuming the package from GitHub Packages |
| [docs/workflows.md](docs/workflows.md) | Defining steps, retries and compensation |
| [docs/durability.md](docs/durability.md) | Stores, codecs, and resuming a run |
| [docs/BENCHMARKS.md](docs/BENCHMARKS.md) | What it costs, on a named machine, with the scripts to re-run it |
| [docs/BUGS.md](docs/BUGS.md) | Defects found and fixed, with the test that caught each one |
| [docs/DECISIONS.md](docs/DECISIONS.md) | What was chosen, against what, and what it costs |
| [docs/roadmap.md](docs/roadmap.md) | What is not built |

## Development

```sh
mvn verify          # tests, plus the sources and javadoc jars
mvn test
bench/run.sh        # the benchmarks, no Maven and no JDK on the path required
```

Java 17 or newer. CI builds on 17 and 21 and fails if a runtime dependency ever
appears. `bench/run.sh` prints the machine it ran on alongside its results and
fetches a portable JDK into `bench/.jdk` if it cannot find one; the numbers it
produced for [docs/BENCHMARKS.md](docs/BENCHMARKS.md) are committed verbatim in
[bench/results.txt](bench/results.txt).

## License

[Apache-2.0](LICENSE)
