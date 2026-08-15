# Decisions

Things I chose, what I chose against, and why the alternative lost. Where a
decision has a cost I am still paying, it says so and, where I have measured it,
what the cost is.

## 1. The caller declares which steps are replayable, by supplying a codec

**Decision.** A step whose output is recorded and replayed on resume is the one
you gave a `Codec` to. A step without one is executed again. Tandem never infers
which kind a step is.

**Alternative considered.** Inferring it. Record everything by default and let
people opt out, or scan for an annotation, or look at whether the step's return
type is serialisable.

**Why it lost.** From the outside a method returning a `String` is identical
whether it hashed something or moved money. Every inference rule I could write
down was wrong in one of the two directions, and the two directions are not
symmetric: recording by default silently skips work that may never have
happened, and repeating by default charges cards twice. Being wrong quietly in
either direction is worse than making the author write eight extra characters
once per step. The benefit that came out of this is that the behaviour is
readable off the definition, with no framework state to reason about.

## 2. The event log is append-only JSON lines

**Decision.** One JSON object per line, appended, never rewritten. Hand-written
encoder, no JSON library.

**Alternatives considered.** A single file rewritten on each change, which is
the obvious thing; and a binary format with a length prefix and a checksum per
record, which is what a real WAL does.

**Why they lost.** Rewriting is the worst option under the failure Tandem exists
for: a crash midway through a rewrite can lose the entire history, whereas a
torn append loses one line. The binary format is genuinely better at detecting
damage, and I chose against it because the log is also the debugging interface.
When a run is stuck, `cat events.jsonl` is the first thing anybody does, and I
was not willing to trade that for a checksum. No JSON library because a runtime
dependency lands in the classpath of every application that uses Tandem, where
it competes with the one they already have; the [benchmark](BENCHMARKS.md#where-the-time-goes)
says encoding is 0.8 µs of a 437 µs append, so there was never any performance
argument on the other side either.

**The cost, and it bit me.** "A torn append loses only the last line" was a
property of the format that the *reader* did not actually have, because it
decoded strictly and one bad byte rejected the whole file. That is
[bug 1](BUGS.md#1-one-torn-byte-at-the-end-made-the-whole-log-unreadable). The
format was right; I had simply never tested the thing the format was chosen for.

## 3. One directory per run, named by an escape of the run id

**Decision.** `<root>/<escaped runId>/` holds `events.jsonl`, the encoded input,
an `id` file with the run id verbatim, and a `steps/` directory of encoded
outputs.

**Alternative considered.** One shared log for all runs, with the run id as a
field, which is how most append-only systems do it.

**Why it lost.** A shared log makes `eventsFor(runId)` a scan of everything ever
written, which is the call the engine makes on every single resume. Per-run
directories make that a read of exactly the run you asked for, and the
[recovery benchmark](BENCHMARKS.md#recovery-time-against-log-length) shows the
result is linear in that run's own length, not in the store's.

**The cost.** `listRuns` and `recoverable` go the other way: they are a
directory scan plus one open per run, and `recoverable` reads the log of every
run in the store including the finished ones. A store nobody prunes makes
recovery slower forever. That is documented in
[durability.md](durability.md#it-lists-what-the-store-holds), and "archive your
completed runs" is a real operational requirement I am pushing onto the user
rather than solving.

**The other cost.** Run ids become file names, which means an escaping, which
means the escaping has to be injective. Getting that wrong put two runs in one
directory ([bug 2](BUGS.md#2-two-different-run-ids-could-share-one-directory))
and got the read and write paths out of step
([bug 6](BUGS.md#6-filestore-escaped-run-ids-on-write-and-not-on-read)). Two of
my eight recorded bugs are the price of this layout choice.

## 4. `FileStore` does not fsync

**Decision.** Events are written and handed to the operating system. Nothing is
forced to the device.

**Alternative considered.** `FileChannel.force(true)` on every event, which is
what the word "durable" normally implies.

**Why it lost.** It costs about **3.3x on end-to-end step throughput and about
4.5x on a single append**, measured in
[BENCHMARKS.md](BENCHMARKS.md#what-the-disk-is-actually-doing): 558 steps/s
becomes 168 steps/s. For a workflow log that is the wrong trade. A process
crash, which is the failure people actually have, loses nothing either way,
because the writes already reached the OS. Only a power cut or a kernel panic
loses events, and a workflow that spans several remote systems has larger
problems in that scenario than its log.

**The cost, stated plainly.** This is the compromise I am least comfortable
with, because it is the one where the honest description and the marketing word
diverge. The whole `STEP_STARTED`-before-the-work mechanism, which is how Tandem
knows a step is in doubt rather than guessing, is only as good as the durability
of that line at the moment it is written. A power cut can lose the intent record
and with it the doubt, and the resume will then repeat the step believing it
never started. `FileStore` is the right store for a machine that crashes and the
wrong one for a machine that loses power, and if you need the stronger guarantee
the answer is a `WorkflowStore` over a database that commits the event
synchronously. This is stated in
[durability.md](durability.md#the-residual-guarantee-stated-exactly) and in
`FileStore`'s own javadoc, and it is not going to be quietly upgraded to
"durable" anywhere in the documentation.

## 5. The run directory is re-established on every append

**Decision.** `FileStore.append` calls `runDirectory`, which calls
`createDirectories` and then checks whether the `id` file exists, for every
event.

**Alternative considered.** Doing both once per run and caching the result in a
map keyed by run id.

**Why it lost, and this one is weak.** It lost on simplicity: a cache is
per-store mutable state that has to be correct under concurrent access, and it
would be wrong if anything outside the process removed a run directory. But I
should be honest that I did not weigh this against a measurement, because I had
not taken one.

**The cost, now measured.** About **175 µs of every 437 µs append, roughly 40%,
is spent on those two calls** ([breakdown](BENCHMARKS.md#where-the-time-goes)),
and both are no-ops after the first event of a run. That is a real inefficiency
in the hot path and I would take a fix for it. I have not made the change here
because this pass was about establishing evidence rather than changing
behaviour, and a cache in the store is a behaviour change that wants its own
tests for the concurrent case. It is written down so it does not get forgotten.

## 6. A store failure is fatal, a listener failure is not

**Decision.** An exception from the `WorkflowStore` propagates and fails the run.
An exception from a `WorkflowListener` is swallowed.

**Alternative considered.** Best-effort logging: catch store failures, carry on,
and let the run finish.

**Why it lost.** Durability is the reason you chose a store. A run that finished
successfully while its log was silently not being written is the worst possible
outcome, because it looks like the good one and you find out at recovery time.
Listeners are the mirror image: they are metrics and audit, and observability
breaking must not break the process it watches.

**The cost, and I have paid it.** "Fatal" means a store failure that has nothing
to do with your run still kills your run.
[Bug 3](BUGS.md#3-concurrent-appends-to-one-run-threw-and-lost-most-of-their-events)
is exactly that: two threads raced to write the `id` file, the loser's
`Files.move` threw, and a perfectly healthy run died over a race whose loser had
nothing to fix and where the winner had written identical bytes. Choosing fatal
means every spurious failure in the store is a lost run, which raises the bar on
the store not producing spurious failures. I still think it is the right choice,
but it is not a free one.

## 7. Compensation runs on failure, and deliberately does not run on doubt

**Decision.** When a step fails and the run gives up, completed steps are
compensated in reverse order, and a compensation that throws does not stop the
others. When a resume stops at a step in doubt, **no compensation runs at all.**

**Alternative considered.** Compensating on the in-doubt path too, on the
grounds that a stopped run is a failed run and failed runs get undone.

**Why it lost.** A run stopped in doubt is waiting for a decision, not
abandoned. Compensating it would undo steps whose recorded outputs are still
sitting in the store, so a later resume would replay values describing work that
had since been undone, and it would do so silently. Stopping and holding
everything in place is the only state from which both answers,
`confirmCompleted` and `confirmNotCompleted`, are still reachable.

Continuing after a failed compensation, rather than stopping, is the same
reasoning: stopping leaves more undone than continuing. A compensation that
throws gets its own event type, `STEP_COMPENSATION_FAILED`, rather than a note
on the compensated event, because a later resume has to tell a step that was
undone from one that may or may not have been, and reading that off a message
string is not a decision worth taking twice.

## 8. No external database, and no runtime dependencies at all

**Decision.** Tandem ships `InMemoryStore` and `FileStore`, an interface, and
zero runtime dependencies. CI fails the build if one ever appears.

**Alternative considered.** Shipping a JDBC store, which would make the fsync
problem in decision 4 somebody else's solved problem.

**Why it lost.** A JDBC store means a connection pool, a schema, a migration
story, and an opinion about which database. Every one of those is a thing the
adopting application already has and has already decided, and my version would
be worse than theirs and in the way. `WorkflowStore` is four methods; a team
with a database can implement it over their own connection pool in an afternoon,
and it will fit their transactions rather than fighting them.

**The cost.** The store people actually get out of the box is the one that does
not fsync, so the honest guarantee Tandem ships with is weaker than the one it
is capable of. Anybody who needs the strong version has to write code. I think
that is the right side of the trade, but it means "durable" in the README is
carrying the weight of decision 4's caveat, which is why that caveat appears in
four places.

## 9. A run executes on the calling thread

**Decision.** `engine.run(...)` runs the whole workflow on the thread that called
it. The engine holds no per-run state and is safe to share.

**Alternative considered.** An internal executor and a `Future`-returning API,
which is what most orchestration libraries do.

**Why it lost.** Owning threads means owning a shutdown story, a rejection
policy, a sizing decision and a set of questions about what happens to in-flight
runs when the pool closes, all of which are the adopting application's calls and
none of which Tandem is in a position to make. Running on the caller's thread
also keeps stack traces honest: a step's exception has the caller's frames under
it.

**The cost.** A long workflow occupies the caller's thread for its whole
duration, which the README states as one of the two limits worth knowing before
adopting it. The exception is a step with a timeout, which does need another
thread, because there is no interruptible form of "call this method"; that pool
is created lazily on first use and uses daemon threads, so a workflow with no
timeouts never pays for it and a forgotten `close()` never holds a JVM open.
