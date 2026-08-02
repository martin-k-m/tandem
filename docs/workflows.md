# Defining workflows

A workflow is a named sequence of steps where each step's output is the next
one's input. The builder carries that type forward, so a mismatch is a compile
error rather than a `ClassCastException` in production.

```java
Workflow<String, Integer> workflow = Workflow.<String>named("parse")
        .step("trim", (String raw, StepContext ctx) -> raw.trim())
        .step("toNumber", (String text, StepContext ctx) -> Integer.parseInt(text))
        .build();
```

The type witness on `named` fixes the input type. Definitions are immutable and
hold no run state, so one is safely shared across threads and reused for as many
runs as you like.

## Steps

A step is any `Fn(input, context) -> output` that may throw:

```java
@FunctionalInterface
public interface Step<I, O> {
    O run(I input, StepContext context) throws Exception;
}
```

`throws Exception` on purpose: a step calls ordinary blocking code, and forcing
each one to wrap every checked exception would add noise without adding a
decision. What a thrown exception means is decided by the step's retry policy.

`StepContext` carries the run id, the workflow and step names, the 1-based
`attempt()`, and `replaying()`. `attempt()` is the useful one, since it lets a
step behave differently on a retry.

### A step that only throws

If a lambda's body always throws, javac has no return value to infer the output
type from and settles on `Object`, which then will not match the workflow type
you declared. Say the type explicitly:

```java
.<String>step("always-fails", (String in, StepContext ctx) -> {
    throw new IllegalStateException("nope");
})
```

This comes up in tests and in terminal failure steps, and nowhere else.

## Retries

```java
RetryPolicy.none()                                      // one attempt
RetryPolicy.fixed(4, Duration.ofMillis(250))            // same delay each time
RetryPolicy.exponential(5, Duration.ofMillis(100))      // doubling, capped at 1 minute
```

Adjust with `withMultiplier`, `withMaxDelay` and `withJitter`. Policies are
immutable, so a shared default cannot be mutated by whoever uses it.

Attempt numbers are 1-based, and the first attempt never waits. A policy with
`maxAttempts == 1` never retries.

`exponential` caps at one minute by default. Uncapped doubling reaches absurd
delays fast: ten attempts from one second is over eight minutes for the final
wait alone, which is rarely what anyone meant.

Jitter only ever reduces a delay, never extends it. Set it when many workers
retry the same failing dependency, or they all wake at the same instant and hit
it together.

Apply a policy to everything that follows, or to one step:

```java
Workflow.<String>named("api")
        .retry(RetryPolicy.exponential(3, Duration.ofMillis(100)))   // from here on
        .step("fetch", fetch)
        .step("parse", parse, RetryPolicy.none())                    // this one only
```

`retry` reads forward and does not reach backwards, so ordering is not a trap.

## Compensation

When a later step fails, completed steps are undone in reverse order:

```java
.step("charge", charge, Codec.ofString())
.compensate((chargeId, ctx) -> payments.refund((String) chargeId))
.step("reserve", reserve)
.compensate((reservation, ctx) -> stock.release(reservation))
.step("ship", ship)
```

If `ship` fails, `reserve` is released and then the charge is refunded. This is
the saga pattern: there is no transaction spanning a payment provider and a
warehouse, so the rollback has to be something you write.

`compensate` attaches to the step immediately before it, and calling it first is
an error rather than a silent no-op.

A compensation that throws is recorded and the remaining ones still run.
Stopping there would leave more undone than continuing does.

The output arrives as `Object`, because compensations are stored alongside
type-erased steps. Cast it. This is the one place the type safety stops, and
tightening it would mean a second type parameter on the builder for a payoff
that did not seem worth it.

## Running

```java
WorkflowEngine engine = new WorkflowEngine();               // in-memory store
RunResult<Integer> result = engine.run(workflow, "  42 ");

if (result.succeeded()) {
    System.out.println(result.outputOrThrow());
} else {
    log.error("run {} failed", result.runId(), result.failure());
}
```

A failed run is a value, not a thrown exception. Workflows fail as part of doing
their job, and forcing a try/catch around the common case makes branching on it
awkward. `outputOrThrow()` is there when you do want the throw.

Runs execute on the calling thread. For durability and resuming, see
[durability.md](durability.md).

## Observability

```java
engine.listener(WorkflowListener.logging());
engine.listener(event -> metrics.counter(event.type().name()).increment());
```

Every event reaches both the listeners and the store, so an audit trail falls
out of the log without extra work. `RunResult.events()` and `eventsOfType` are
usually how you assert on a run in a test.

Listeners are called on the thread running the step, so a slow one slows the
workflow. An exception thrown by a listener is swallowed: observability failing
must not fail the process it is watching. A store failure is not swallowed,
because durability is the reason you chose a store.

## Scheduling

```java
try (Scheduler scheduler = new Scheduler(engine)) {
    scheduler.after(workflow, input, Duration.ofMinutes(5));
    scheduler.every(reconcile, "nightly", Duration.ofHours(24));
}
```

The gap for `every` is measured from the end of one run to the start of the
next, not start to start. At a fixed rate, a run that overruns its period makes
the next start immediately, and a slow dependency turns into a stampede.

Scheduler threads are daemons, so a scheduler left open will not keep the JVM
alive. It is in-process and does not survive a restart, and two instances of
your application will each fire the same recurring workflow.
