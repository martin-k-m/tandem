# Tandem

**Workflow orchestration for Java. Durable runs, retries, compensation and scheduling, with no runtime dependencies.**

[![CI](https://github.com/martin-k-m/tandem/actions/workflows/ci.yml/badge.svg)](https://github.com/martin-k-m/tandem/actions/workflows/ci.yml)
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
If the process dies instead, running the same run id again skips the charge,
because its output was recorded, and carries on from there.

## Install

```xml
<dependency>
  <groupId>me.blinkdev</groupId>
  <artifactId>tandem</artifactId>
  <version>0.1.0</version>
</dependency>
```

Published to GitHub Packages. Consuming it needs a `<repository>` entry pointing
at `https://maven.pkg.github.com/martin-k-m/tandem` and a GitHub token, because
GitHub Packages requires authentication even for public artifacts. See
[docs/install.md](docs/install.md).

## What is in 0.1

| | |
| :-- | :-- |
| **Typed steps** | Each step's output is the next one's input, checked at compile time |
| **Retries** | Fixed or exponential, capped, with optional jitter, per step or per workflow |
| **Compensation** | Saga-style undo, in reverse order, continuing if one fails |
| **Durable resume** | Steps with a `Codec` record their output and are not run twice |
| **Stores** | In-memory and append-only file, or your own implementation |
| **Observability** | Every event reaches listeners and the store, so metrics and audit fall out |
| **Scheduling** | Run later, or repeat at a fixed delay |

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
.step("charge", chargeCard, Codec.ofString())   // recorded, never charged twice
.step("format", formatReceipt)                  // pure, cheap to repeat
```

Tandem does not guess which kind a step is. Inferring it would mean either
re-running payment calls or silently skipping work that never happened, and both
of those are worse than asking you to say which one you meant.

## Documentation

| Document | What it covers |
| :-- | :-- |
| [docs/install.md](docs/install.md) | Consuming the package from GitHub Packages |
| [docs/workflows.md](docs/workflows.md) | Defining steps, retries and compensation |
| [docs/durability.md](docs/durability.md) | Stores, codecs, and resuming a run |
| [docs/roadmap.md](docs/roadmap.md) | What is not built |

## Development

```sh
mvn verify          # tests, plus the sources and javadoc jars
mvn test
```

Java 17 or newer. CI builds on 17 and 21 and fails if a runtime dependency ever
appears.

## License

[Apache-2.0](LICENSE)
