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
the bugs recorded in BUGS.md are the price of this layout choice.

## 4. `FileStore` does not fsync by default, and the caller can say otherwise

**Decision.** `FileStore` takes a `Durability`. `OS_BUFFERED`, the default,
writes and hands the bytes to the operating system. `SYNC_ON_EVERY_EVENT` forces
every event and every step output to the device before returning, metadata
included, and on Linux forces the run directory as well so a newly created log
is not just its contents.

**Alternatives considered.** Leaving it as it was, with no fsync available at
all and a benchmark-only store standing in for the version nobody could use. And
making the fsync always on, which is what the word "durable" normally implies.

**Why they lost.** Not offering it lost because the guarantee was unreachable
from the shipped jar: the store priced at 3.3x lived in `bench/`, so the only
way to buy durability was to write your own store. Always on lost because the
price is real and the failure it buys against is narrow. `SYNC_ON_EVERY_EVENT`
costs about **3.3x on end-to-end step throughput and about 9.5x on a single
append** ([BENCHMARKS.md](BENCHMARKS.md#what-the-disk-is-actually-doing)): 615
steps/s becomes 186. A process crash, which is the failure people actually have,
loses nothing either way, because the writes already reached the OS. Only a power
cut or a kernel panic loses events. Defaulting to the expensive answer would
have made every existing user pay several times over for a failure most of them
do not face, and would have changed the cost of an upgrade rather than the
choice available in it.

**Why the caller and not Tandem.** Which failure you are buying against is a
property of the deployment, not of the library: the same workflow is a laptop
process on one machine and a rack with no UPS on another. This is the one thing
in Tandem I could not decide correctly on someone else's behalf, and it is small
enough to be a constructor argument rather than a second store.

**The cost, stated plainly.** The default is still the weaker guarantee, so
"durable" in the README still carries this caveat, and the whole
`STEP_STARTED`-before-the-work mechanism is only as good as the durability of
that line at the moment it is written. A power cut can lose the intent record
and with it the doubt, and the resume will then repeat the step believing it
never started. `SYNC_ON_EVERY_EVENT` closes that on Linux and narrows it on
Windows, where a directory cannot be forced at all: a run's first event has a
window the platform does not let a store close. That is stated in
[durability.md](durability.md#the-residual-guarantee-stated-exactly) and in
`FileStore`'s own javadoc rather than quietly upgraded to "durable".

## 5. The run directory is established once per run and cached

**Decision.** `FileStore` remembers the run directories it has established, in a
`ConcurrentHashMap` keyed by run id, so `createDirectories` and the `id` file
check happen on a run's first event and not on every one.

**Why it was not this before.** Simplicity: a cache is per-store mutable state
that has to be correct under concurrent access, and it is wrong if anything
outside the process removes a run directory. Both objections are real and both
are answered rather than dismissed.

**Concurrency.** `computeIfAbsent` does the establishing, so concurrent appends
to one run establish it once and a mapping is recorded only if the function
returned. `FileStoreCacheTest` is the test that exists because of this decision:
concurrent appends to one run, concurrent appends to distinct runs, two stores
over one root, and ids that need escaping. Breaking the cache so every run
shares one directory fails four of its five tests.

**Removal underneath it.** An append that fails drops the cached entry,
re-establishes the directory and tries once more, so the cache cannot turn a
recoverable state into a dead run. That is a test too.

**Growth.** The map is cleared when it passes 10,000 entries. It caches work
that is idempotent anyway, so the worst a clear costs is one extra
`createDirectories` per run.

**What it bought, measured.** On the same machine minutes apart, an append to an
established run went from **179.3 µs to 93.1 µs median**, and in the breakdown
table from 183.6 µs to 90.7 µs, which puts an append within about 10% of the raw
open-write-close it contains rather than at twice it.

**What it did not buy.** End-to-end step throughput barely moved: 595 steps/s
before, 610 after, which is inside the noise on this machine. That benchmark
runs each workflow under a fresh run id, so it pays the establishing cost once
per run either way and the saving is 20 appends out of 21 in a run that is
dominated by other file operations. The 40% figure was always about appends
within an established run, and a run of 10 steps is not many appends. The
honest summary is that this halved the cost of an append and left the cost of a
short run alone.

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

**The cost.** The store people get out of the box still defaults to the weaker
guarantee, so "durable" in the README carries the weight of decision 4's caveat,
which is why that caveat appears in four places. What has changed is that the
strong version is now a constructor argument rather than code somebody has to
write.

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

## 10. `run()` refuses a run that already succeeded

**Decision.** `run(workflow, input, runId)` throws if the run's log ends in
`RUN_SUCCEEDED`, the same refusal `resume(RecoverableRun)` already made.

**Alternative considered.** Leaving it, and documenting it harder. It was
documented: the README's delivery table, `DeliverySemanticsTest` and
[BUGS.md](BUGS.md) all said plainly that re-running a finished id repeats every
step without a codec.

**Why it lost.** Documenting a duplicate side effect is not the same as not
having one, and this one needed no crash: the same call that starts a run
repeats a finished one, so getting it wrong looks exactly like getting it right.
Refusing costs nothing that was worth doing, because the only work a resume of a
completed run performs is work that already happened.

**The cost.** Re-running a finished id against a longer definition used to be a
way to extend a completed run, and it is now refused. The recovery benchmark was
doing exactly that, which is how the change was noticed; it now builds its
resumable run by failing a step, which is what a real restart looks like. There
is no replacement for extending a finished run: give it a new id, or do not
finish it.
