# Durability and resuming

The part of Tandem worth understanding properly, because it is explicit where
other frameworks are implicit.

## The problem

A workflow charges a card, reserves stock, then emails a receipt. The process
dies after the charge. You restart it. What happens to the charge?

Three answers are possible, and only one of them is right for any given step:

1. Charge again. Correct for a pure computation, catastrophic for a payment.
2. Skip it and carry on with the recorded result. Correct for the payment,
   wrong if the charge never actually completed.
3. Refuse to resume. Safe, useless.

Tandem cannot tell which of your steps is which. Nothing can, from the outside:
a method that returns a `String` looks identical whether it hashed something or
moved money. So Tandem asks you, once, per step.

## How you say it

A step declared with a `Codec` is **recorded**. Its output is written to the
store when it succeeds, and a resumed run decodes that output instead of running
the step:

```java
.step("charge", (Order o, StepContext ctx) -> payments.charge(o), Codec.ofString())
```

A step declared without one is **repeated** on resume:

```java
.step("format", (String id, StepContext ctx) -> "receipt-" + id)
```

That is the entire model. There is no annotation scanning, no determinism
checker, and no replay of a recorded execution history against your code. The
cost is that you have to think about each step once. The benefit is that the
behaviour is readable from the definition, and nothing surprising happens
because a framework guessed.

## Resuming

Resuming means passing a run id that already has history:

```java
RunResult<Receipt> first = engine.run(checkout, order, "order-4417");
// process dies

RunResult<Receipt> second = engine.run(checkout, order, "order-4417");
```

The second call emits `RUN_RESUMED`, replays each recorded step in order
(`STEP_REPLAYED`), and executes the first step that has no recorded output.
`StepContext.replaying()` is true throughout, so a step can behave differently
if it needs to.

A run id you have never used starts fresh. `engine.run(workflow, input)` without
one generates a UUID, so ordinary runs are never accidentally resumed.

## Codecs

Built in: `Codec.ofString()`, `ofInt()`, `ofLong()`, `ofDouble()`, `ofBoolean()`.

Anything else is two methods:

```java
Codec<Order> orders = new Codec<>() {
    public String encode(Order order) { return order.id() + ":" + order.total(); }
    public Order decode(String text) {
        String[] parts = text.split(":", 2);
        return new Order(parts[0], new BigDecimal(parts[1]));
    }
};
```

Encode into something you can still read in six months. The recorded value is
also what a human sees when debugging a stuck run.

There is no JSON mapper here on purpose: shipping one would put it in the
classpath of every application using Tandem, where it would compete with the one
they already have.

## Stores

| Store | Survives a restart | Use for |
| :-- | :-- | :-- |
| `InMemoryStore` | No | Tests, and workflows no more durable than the process |
| `FileStore` | Yes | A single machine that needs to resume after a crash |

`FileStore` writes one directory per run: an append-only `events.jsonl` and one
file per recorded step output. Append-only because a crash during a rewrite can
lose the whole history, while a torn append loses one line, and the reader skips
lines it cannot parse.

Outputs are written to a temporary file and moved into place, so a reader never
observes a half-written value.

It does not fsync per event. A machine losing power may lose the last few
events. That is the right trade for a workflow log and the wrong one for a
ledger; if you need the stronger guarantee, implement `WorkflowStore` over a
database.

## Writing your own store

Four methods, and it must be safe to call from several threads:

```java
public interface WorkflowStore {
    void append(WorkflowEvent event);
    List<WorkflowEvent> eventsFor(String runId);
    void saveOutput(String runId, String stepName, String encoded);
    Optional<String> loadOutput(String runId, String stepName);
}
```

A store failure propagates and fails the run. That is deliberate: durability is
the reason you chose a store, so losing it quietly would be worse than stopping.
Listener failures are swallowed instead, because observability breaking must not
break the process it watches.

## What this does not give you

Resuming is manual. Nothing scans for interrupted runs and restarts them, so
knowing that `order-4417` needs resuming is your application's job, from your
own records or by listing runs in your store.

There is also no protection against a definition changing between runs. Add a
step in the middle, resume an old run, and the recorded outputs are matched by
step name against the new shape. Versioning workflow definitions against
in-flight runs is on the [roadmap](roadmap.md) and is not solved here.
