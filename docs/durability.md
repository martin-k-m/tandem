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
3. Refuse to resume. Safe, and useless as a general policy.

Tandem cannot tell which of your steps is which. Nothing can, from the outside:
a method that returns a `String` looks identical whether it hashed something or
moved money. So Tandem asks you, once, per step.

The third answer is not useless in one narrow case: when the log itself cannot
say whether the charge completed. Tandem refuses there and only there, which is
[the section on dying mid-step](#when-the-process-dies-inside-a-step).

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

## When the process dies inside a step

Running a step and recording its output are two writes, to the system the step
talks to and to your store. They cannot be made one write without a transaction
spanning both, which a library sitting inside your JVM does not have. So there
is an instant where the card has been charged and nothing has recorded it.

What Tandem does is write the intent before the work, not after it:

```
STEP_STARTED   charge     <- in the store before the step is called
   ...the step runs, the card is charged...
saveOutput     charge     <- the recorded output
STEP_SUCCEEDED charge     <- only once the output is safely down
```

A resume reads the log. Three shapes are possible for a recorded step:

| What the log holds | What resume does |
| :-- | :-- |
| A saved output | Replays it. The step does not run. |
| No started event | Runs the step. Nothing happened yet. |
| Started events that all have an outcome, no output | Runs the step. The last attempt is known to have failed, which is the same thing a retry within one run does. |
| A started event with no outcome, no output | Stops. |

That third row is the crash window, and stopping there is the whole point. The
run fails with a `StepInDoubtException` carrying the run id and the step name,
and emits `STEP_IN_DOUBT`. Compensations do not run: they would undo steps whose
recorded outputs are still in the store, so a later resume would replay values
that no longer stand. The run is waiting for a decision, not being abandoned.

### Settling it

Look at the system the step talked to, then tell the engine what you found:

```java
RunResult<Receipt> result = engine.run(checkout, order, "order-4417");

if (result.failure() instanceof StepInDoubtException doubt) {
    if (payments.chargeExistsFor(order)) {
        engine.confirmCompleted(doubt.runId(), doubt.stepName(), chargeId, Codec.ofString());
    } else {
        engine.confirmNotCompleted(doubt.runId(), doubt.stepName());
    }
    result = engine.run(checkout, order, "order-4417");
}
```

A run stopped this way is a failed `RunResult` like any other, not a throw, so
the check is on `failure()`.

`confirmCompleted` records the output you supply, so the next resume replays it.
`confirmNotCompleted` records the step as failed, which is what it was, so the
next resume runs it again. Both write to the store, so the decision survives a
restart and shows up in the audit trail as `confirmed out of band`.

### The residual guarantee, stated exactly

- A step with a `Codec` is **never repeated by a resume** unless you called
  `confirmNotCompleted` for it.
- An outcome the log cannot settle is **surfaced, not guessed**.
- Detection is only as good as the store's durability at the moment
  `STEP_STARTED` is written. `FileStore` does not fsync, so a power loss, as
  opposed to a process crash, can lose that line and with it the doubt. A store
  over a database that commits the event synchronously does not have that gap.
- A step **without** a `Codec` is repeated after a crash with no questions
  asked, because declaring it without one is how you said that was fine.
- None of this makes a non-idempotent remote call safe on its own. It makes the
  ambiguity visible. An idempotency key on the call itself is still the right
  thing to do, and Tandem gives you a stable one: `StepContext.runId()` plus
  `stepName()`.

## Recovering after a crash

`resume` needs the run id, the `Workflow` object and the original input. A crash
keeps only the first of those, and nothing could enumerate what was left
unfinished, so durable resume was unusable in exactly the situation it exists
for. `recoverable` closes that.

```java
WorkflowEngine engine = new WorkflowEngine(new FileStore(dir));

for (RecoverableRun<String, String> run : engine.recoverable(checkout)) {
    switch (run.state()) {
        case RESUMABLE -> engine.resume(run);
        case IN_DOUBT  -> alertSomebody(run.runId(), run.stepInDoubt().orElseThrow());
        case FAILED, COMPLETED -> { }
    }
}
```

The input comes back with the run, which is why stores persist it: without it
there is nothing to resume with. It is written through the same codec as any
step output, and never inferred, for the same reason.

### The four states

| State | Meaning |
|---|---|
| `COMPLETED` | finished, nothing to do |
| `FAILED` | ran to a failure and compensated |
| `RESUMABLE` | stopped between steps, safe to continue |
| `IN_DOUBT` | stopped **inside** a recorded step |

`IN_DOUBT` is the one worth understanding. A step wrote `STEP_STARTED`, then the
process died before its output was recorded, so the side effect may or may not
have happened. Tandem cannot tell, and neither can anything else after the fact:
that is what the two-phase write buys, the ability to know that you do not know.
Resuming one raises `StepInDoubtException` rather than guessing, because
guessing means either charging a card twice or never charging it, and picking
silently is worse than stopping.

Deciding what to do about it is the caller's, since only the caller can ask the
payment provider whether the charge landed.

### Run ids survive the round trip

`listRuns` gives back the id you used, not the directory name. `FileStore`
escapes an id into a file name and records the id beside the events, so the
answer is a read rather than a second implementation of the escaping.

The escaping is reversible, which is the point: a character that cannot go in a
file name becomes `_` and its four hex digits, and `_` is escaped too, so a name
that passed through untouched never contains one and an escaped name always
does. Distinct ids therefore get distinct directories, and ids made of letters,
digits, `-` and an interior `.` stay readable on disk.

### It lists what the store holds

`recoverable` walks everything in the store, so a store nobody prunes grows and
the call gets slower in proportion to what it holds rather than to what needs
recovering. Archive or delete finished runs.


## Codecs

Built in: `Codec.ofString()`, `ofInt()`, `ofLong()`, `ofDouble()`, `ofBoolean()`,
and `ofEnum(Type.class)`, which records an enum by its constant name.

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

Settling a step left in doubt is manual too. Tandem cannot query your payment
provider, so a run stopped by `StepInDoubtException` stays stopped until
something calls `confirmCompleted` or `confirmNotCompleted`. Making that
automatic would mean guessing, which is the thing the stop exists to avoid.

There is also no protection against a definition changing between runs. Add a
step in the middle, resume an old run, and the recorded outputs are matched by
step name against the new shape. Versioning workflow definitions against
in-flight runs is on the [roadmap](roadmap.md) and is not solved here.
