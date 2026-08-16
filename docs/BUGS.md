# Bugs worth writing down

Defects I found in Tandem and fixed, with enough detail that you can check the
claim rather than take my word for it. Every one of them has a commit and a
regression test, and I confirmed each test fails with only its fix reverted.

I keep this file because the bugs are the most interesting thing about the
project. Three of the nine below are the same failure wearing different clothes:
**work that already happened happens again after a restart**, which is the one
thing a durable workflow engine exists to prevent. Three more are the store
damaging or losing what it was given: a log that destroyed more of itself than
it had to, a run that died over the spelling of an error message, and a race
that threw away most of a burst of concurrent events. The last three are a type
inference trap and two flaky tests.

The four in the first section were found by a property test rather than by me,
which is the reason that test exists.

---

## Found by the property test

I had example-based tests for the store: a run does this, so the store should
say that. They all passed. So I wrote
[`TandemDurabilityPropertyTest`](../src/test/java/io/github/martinkm/tandem/TandemDurabilityPropertyTest.java),
which goes the other way: it generates run ids, step names and error messages
nobody would think to write down, and it damages the log on purpose instead of
using it correctly. It found four bugs on the first run.

All four were fixed in
[`f793748`](https://github.com/martin-k-m/tandem/commit/f793748).

### 1. One torn byte at the end made the whole log unreadable

**Symptom.** A crash that tore the last append in the middle of a multi-byte
character did not cost you the last event. It cost you every event in the run.
`eventsFor` threw instead of returning, so a run that was 99% recorded could not
be read, listed, classified or resumed at all.

**Root cause.** `eventsFor` read the file with
`Files.readAllLines(file, StandardCharsets.UTF_8)`. That decoder is strict, and
it is strict about the *file*, not about the line. One malformed byte sequence
anywhere in it throws `MalformedInputException` and you get nothing back. The
per-line `try/catch` that was supposed to contain the damage sat inside the
loop, and the decode had already failed before the loop began.

The comment at the top of `FileStore` says the format is append-only so that
"a torn append loses only the last line, and the reader skips lines it cannot
parse". It did not do that. The whole justification for the file format rested
on a property the reader did not have.

**How it was caught.**
`TandemDurabilityPropertyTest.everyTruncationOfALogReadsBackAsAPrefixOfIt`. It
writes a log, then truncates it to every possible byte length in turn and
asserts that what reads back is a prefix of what went in. Truncating inside a
multi-byte character is not an edge case you have to be clever to hit; it is
most of the byte offsets in a log containing any non-ASCII text at all. The line
that mattered was the one that made the failure unmistakable:

```
java.io.UncheckedIOException: could not read ...\torn\events.jsonl
Caused by: java.nio.charset.MalformedInputException: Input length = 1
```

`could not read`, not `could not parse line 47`. The reader was refusing the
file.

**Fix.** Read the bytes and decode leniently, so undecodable bytes become
replacement characters that stay inside the line they belong to, where the
existing per-line parse drops them:

```java
String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
for (String line : text.split("\\R")) {
```

**Regression test.** `everyTruncationOfALogReadsBackAsAPrefixOfIt`. Reverting
only `FileStore.java` makes it fail with the exception above; the rest of the
property test still passes, so the test is pinned to this bug and not to a
neighbouring one.

### 2. Two different run ids could share one directory

**Symptom.** Two runs with different ids appended to the same event log and
resumed from each other's history. A run could come back having replayed steps
it never ran.

**Root cause.** `safeName` replaced every character that cannot go in a file
name with `_`, and then, because that is not injective, appended a hash of the
original to any name it had touched. The flaw is that the result was itself a
legal run id. `"a/b"` was stored as `"a_b-17234"`, and a run genuinely called
`"a_b-17234"` needed no escaping, so it was stored under `"a_b-17234"` too. The
escaping had a fixed point where it collided with the identity.

**How it was caught.**
`TandemDurabilityPropertyTest.distinctRunIdsNeverShareAHistory`, which generates
pairs of distinct ids and asserts they never see each other's events. The
assertion message is the whole bug in one line:

```
another run id wrote into this run's log
==> expected: <[from-awkward]> but was: <[from-awkward, from-escaped]>
```

One run's log contained the other run's event.

**Fix.** Escape rather than replace. A character that cannot be a file name
becomes `_` followed by its four hex digits, which is reversible and therefore
cannot collide. That only holds if `_` is itself escaped, so `_` came out of the
pass-through set even though filesystems are perfectly happy with it: an
untouched name now never contains `_`, an escaped one always does, and the two
can never meet.

**Regression test.** `distinctRunIdsNeverShareAHistory`.

### 3. Concurrent appends to one run threw, and lost most of their events

**Symptom.** Two threads appending to the same run at the same time, which the
`WorkflowStore` contract explicitly permits, killed one of the runs with an
`IOException` about a file it had no interest in.

**Root cause.** `recordId` writes the run id beside the events once per run. It
checked whether the file existed and then moved a temporary into place, which is
check-then-act. Two threads could both find it missing, both write a temporary,
and the loser's `Files.move` threw `FileAlreadyExistsException`. Because store
failures are fatal by design, a run died over a race whose loser had nothing to
fix: the winner had written the identical bytes, because it is the same run id.

**How it was caught.**
`TandemDurabilityPropertyTest.concurrentAppendsToOneRunAllLandWhole`, which
fires 320 appends at one run from several threads and counts what comes back.
Reverting the fix on my machine:

```
an event was lost or torn by a concurrent append
==> expected: <320> but was: <125>
```

195 of 320 appends lost. I want to flag that number, because I had previously
recorded this bug as losing 7 of 320. Both are true and the difference is the
machine: the window widens under load, and the run above was on a busy laptop.
If you reproduce this, expect anywhere in that range. The bug is not
"occasionally drops an event", it is "drops as many as the scheduler lets it".

**Fix.** Drop `REPLACE_EXISTING` so the move fails when it loses, then treat
losing as the non-event it is:

```java
Files.move(temporary, file);
} catch (FileAlreadyExistsException lostTheRace) {
    // Someone else recorded it first, with the same content.
}
```

**Regression test.** `concurrentAppendsToOneRunAllLandWhole`.

### 4. A lone surrogate killed the run

**Symptom.** A run failed with an encoding error instead of doing its work,
because of the *spelling* of an error message it was trying to record.

**Root cause.** A lone surrogate is what you are left with when something
truncates a string through the middle of an emoji, which is a thing log
pipelines and message-length limits do constantly. It is not a character any
charset can encode. `JsonLine` wrote characters below `0x20` as escapes and
everything else literally, so a lone surrogate went into the line as itself, and
`Files.writeString` threw `UnmappableCharacterException`. A `detail` field
carries whatever an exception message happened to hold, so this was reachable
from any step that failed with a truncated message.

**How it was caught.**
`TandemDurabilityPropertyTest.aLoneSurrogateSurvivesTheLogRatherThanBreakingIt`:

```
java.io.UncheckedIOException: could not append to ...\surrogate\events.jsonl
Caused by: java.nio.charset.UnmappableCharacterException: Input length = 1
```

**Fix.** Escape surrogates as well as controls, so they become four hex digits
that decode back to exactly the char that went in, and a well-formed pair still
round-trips as a pair:

```java
if (c < 0x20 || Character.isSurrogate(c)) {
    out.append(String.format("\\u%04x", (int) c));
```

**Regression test.** `aLoneSurrogateSurvivesTheLogRatherThanBreakingIt`. Note
that reverting this fix also breaks two of the log-damage tests, because they
write generated text that contains surrogates. Reverting `FileStore` alone
leaves this test passing, which is how I confirmed the two fixes are
independent.

---

## Found by reasoning about the crash window

Both fixed in
[`7bf26db`](https://github.com/martin-k-m/tandem/commit/7bf26db), which I wrote
after reading my own README and not believing it.

### 5. A crash inside a step charged the card twice

**Symptom.** The process dies inside a recorded step. You restart. The step runs
again, and its side effect happens a second time. The README's first example is
`.step("charge", chargeCard)`.

**Root cause.** There was no record that a step had been entered. The output was
persisted after `attempt()` returned, so a crash between the side effect and
that write left a log in which the step simply had not happened. A resume read
that log, saw no output and no evidence of an attempt, and correctly concluded
from what it could see that the step still needed running. The information that
would have said otherwise was never written down.

**Fix.** Write the intent before the work. `STEP_STARTED` now reaches the store
before the step is called, and the output is written before `STEP_SUCCEEDED`, so
a resume can distinguish "never started" from "started and never accounted for".
The second case stops the run with `StepInDoubtException` rather than guessing.

This narrows the window; it does not close it, and I have been careful never to
claim otherwise. Recording an intent and doing the work are two writes to two
systems. What it buys is the ability to know that you do not know, which is
worth more than it sounds: the alternative is silently charging twice.

**Regression tests.** `WorkflowEngineTest.aResumeRefusesToRepeatARecordedStepThatCrashedMidway`,
`theIntentRecordSurvivesARestart`, and
`aStepWhoseOutputCannotBeRecordedIsNotLoggedAsSucceeded`.

This is also the bug that
[`DeliverySemanticsTest`](../src/test/java/io/github/martinkm/tandem/DeliverySemanticsTest.java)
now demonstrates end to end with a real crash rather than a simulated one: a
child JVM performs the side effect and calls `Runtime.halt`, and the parent
recovers and counts. See [Delivery semantics](#delivery-semantics) below.

### 6. `FileStore` escaped run ids on write and not on read

**Symptom.** The same failure as the one above, without needing a crash. Any run
id containing a character outside `[A-Za-z0-9._-]` wrote its events to one
directory and looked for them in another. `eventsFor` came back empty, the
engine concluded the run was fresh, and **every recorded step ran a second
time.**

**Root cause.** `append` and `saveOutput` built their paths through `safeName`.
`eventsFor` and `loadOutput` did not:

```java
Path file = root.resolve(runId).resolve("events.jsonl");    // before
Path file = root.resolve(safeName(runId)).resolve(...);     // after
```

An asymmetry between the write path and the read path, which is a shape worth
learning to recognise: it is invisible in any test that only round-trips through
one store instance with a well-behaved id, and every test I had did exactly
that.

**A second defect fell out of the same line.** Because reads did not sanitise,
`eventsFor("../../x")` resolved outside the store root. The existing directory
traversal test covered step names only. Escaping every path through one function
closed both.

**Fix.** Every path is built through `safeName`, reads included.

**Regression tests.** `StoreTest.fileStoreReadsBackARunIdThatNeededSanitising`,
`fileStoreDoesNotLetARunIdEscapeTheDirectory`, and
`fileStoreKeepsDistinctRunIdsApartWhenTheySanitiseAlike`. Each confirmed failing
with only the `FileStore` change reverted.

---

## Smaller ones

### 7. A step lambda that only throws did not compile as the type it declared

**Symptom.** A lambda whose body always throws gave `javac` nothing to infer the
step's output type from, so the type variable resolved to `Object` and the built
workflow did not match its declared type. Anyone writing a deliberately failing
terminal step hits this immediately.

**Root cause.** Ordinary Java inference, not a Tandem defect as such, but a
usability defect in an API whose whole selling point is that steps are typed.

**Fix.** [`4c2d0a2`](https://github.com/martin-k-m/tandem/commit/4c2d0a2). An
explicit type witness at the call site, and it is documented, because the next
person will hit it too.

**Regression test.** The affected cases in `WorkflowEngineTest`, which now
compile.

### 8. A test that failed about one full-suite run in ten

Not a product bug, but it belongs here, because a suite that fails one run in
ten trains you to re-run instead of read, and then you miss a real one.

**Symptom.** `StepTimeoutTest.aTimeoutIsRetriedLikeAnyOtherFailure` failed
intermittently, roughly 1 run in 9.

**Root cause.** The test gave a step a 150 ms budget. That budget has to be far
shorter than the deliberate hang and far longer than the time it takes to hand a
step to a thread from a pool. The second margin is the one that bit: on a loaded
machine, scheduling the attempt that was supposed to answer instantly took more
than 150 ms, and the run failed having never run the step late at all. The test
was measuring the machine.

**Fix.** In [`f793748`](https://github.com/martin-k-m/tandem/commit/f793748):
raise the budget to 800 ms, with a comment saying which of the two margins is
the tight one, so nobody tunes it back down.

### 9. A second flaky test, and it was worse than the first

Found while running the suite for this pass, on a machine that happened to be
busy. It is the same species as bug 8 and I am recording it separately because
of how it behaved: **it failed 15 times out of 15 under load, and had passed
cleanly an hour earlier on the same commit.** A flake that is 100% reproducible
while the machine is loaded and 0% when it is not is the kind that gets
dismissed as "works on my machine" in both directions.

**Symptom.** `SchedulerTest.closingAnOwnedSchedulerStopsFurtherRuns` failed with
`the schedule kept firing after close() ==> expected: <0> but was: <1>`.

**Root cause.** Two independent timing assumptions, both wrong.

```java
scheduler.every(workflow, "go", Duration.ofMillis(5));
Thread.sleep(30);
scheduler.close();
int afterClose = runs.get();     // sampled the instant close() returned
```

The expected value of `0` gives the first one away: **the schedule had not fired
even once in those 30 ms.** The test was passing for the wrong reason on a fast
machine and proving nothing about `close` on a slow one. Then the sample taken
the moment `close` returned was overtaken by the firing that was already in
flight, because `close` calls `shutdownNow`, which interrupts running tasks
rather than waiting for them. So the count moved after it had been read.

The file's own class javadoc says these tests "assert on outcomes reached
through a latch rather than on wall-clock timing". This one test did not follow
the convention its own file describes.

**Fix.** Follow it. Wait on a latch for proof the schedule is actually live,
then close, then let an in-flight firing settle before sampling:

```java
assertTrue(firedTwice.await(10, TimeUnit.SECONDS), "the schedule never started firing");
scheduler.close();
Thread.sleep(200);               // let an interrupted firing finish
int afterClose = runs.get();
Thread.sleep(200);
assertEquals(afterClose, runs.get());
```

**Regression test.** The test itself. It failed 15 of 15 runs before the change
and passed 8 of 8 after it, on the same loaded machine within a few minutes.

**Worth saying:** `Scheduler.close` really does only stop *further* runs. It does
not wait for a firing in progress, and it cannot force one to stop, because
`shutdownNow` interrupts and interruption is a request. That is the correct
behaviour for an `AutoCloseable` and it is now what the test asserts, rather than
the stronger property it used to assume.

---

## Delivery semantics

Not a bug, but the thing most likely to become one in somebody's production
system, so it is settled by demonstration rather than by prose. See
[`DeliverySemanticsTest`](../src/test/java/io/github/martinkm/tandem/DeliverySemanticsTest.java)
and the [delivery guarantees section of the README](../README.md#delivery-guarantees).

Tandem delivers **at-least-once**, not exactly-once. The test proves it with a
real crash: a child JVM performs a side effect and then calls `Runtime.halt`,
which ends the process without shutdown hooks and without flushing anything the
runtime had not already written. The parent recovers from the same directory and
counts how many times the side effect happened.

| Case | Side effects after recovery |
| :-- | :-- |
| Step without a `Codec`, crash after the side effect | **2.** It runs again. |
| Step with a `Codec`, crash after the side effect | **1.** The resume stops with `StepInDoubtException`. |
| ...then `confirmCompleted` | **1.** The run finishes without repeating the step. |
| ...then `confirmNotCompleted` | **2.** The step runs again, because you asked. |
| Completed run id passed to `run()` again, no crash involved | **2.** See below. |

That last row is the sharp edge I would most expect someone to cut themselves
on, and it needs no crash at all. `engine.run(workflow, input, someFinishedRunId)`
resumes that run, and resuming repeats every step without a codec. Only
`engine.resume(RecoverableRun)` refuses a completed run, because only it was
handed the classification that says the run is finished. It is covered by
`DeliverySemanticsTest.rerunningACompletedRunIdRepeatsItsUnrecordedSteps`.

## How to check any of this

```sh
mvn test                                              # the whole suite
mvn test -Dtest=TandemDurabilityPropertyTest          # bugs 1 to 4
mvn test -Dtest=DeliverySemanticsTest                 # the delivery semantics
mvn test -Dtest=StoreTest,WorkflowEngineTest          # bugs 5 and 6
```

To confirm a fix is really what makes its test pass, revert the one file named
in the entry and run that test alone. That is how each entry above was checked,
and it is why entries 1 and 4 note which tests move together and which do not.
