# Benchmarks

Every number here comes from one recorded run of `bench/run.sh` on the machine
described below. The raw output of that run is committed as
[bench/results.txt](../bench/results.txt), including the environment block the
script prints, so no figure in this document is separated from the machine that
produced it.

Read the [caveats](#what-these-numbers-are-not) before quoting anything. The
short version: the machine was idle this time, the file figures reproduce within
about 1.15x between runs, and the ratios are still the durable part.

## Environment

| | |
| :-- | :-- |
| CPU | Intel Core Ultra 9 285H, 16 physical / 16 logical cores, 2900 MHz nominal |
| RAM | 15.43 GB total, 3.25 GB free at the start of the run |
| OS | Windows 11 Pro, build 26200 |
| Storage | Timetec 35TT2280GEN4P-2TB, NVMe SSD |
| JDK | Temurin 21.0.12+8, 64-Bit Server VM, mixed mode, sharing |
| Working directory | `C:\Users\comma\AppData\Local\Temp`, on the SSD above |
| Idle | **Yes.** 6% CPU reported at the start of the run |
| Date | 2026-08-17T21:42:15Z |
| Commit | `e6bd123` on branch `durable-filestore` |

This run was taken with nothing else on the machine. The previous recording was
made while an unrelated workload held about 80% of the CPU, which is why it
warned that the same benchmark gives file figures spread over a factor of 1.6.
Most of that was the busy machine, but not all of it. Three idle runs were taken
back to back. Every row of the append and breakdown tables reproduces within
about 1.1x across them, and two rows do not: buffered step throughput came out
at 866, 995 and 1,187 steps/s, and the first append of a run at 1159, 829 and
865 µs. Those two are worth 1.4x on their own and are called out again in the
[caveats](#what-these-numbers-are-not). The run recorded here is the third.

The earlier recorded runs, against commits `f793748` and `6314968`, are in this
file's history; where a comparison with one matters below it was re-measured
back to back on this machine rather than quoted across runs.

## Reproducing

```sh
bench/run.sh > bench/results.txt     # all four benchmarks
bench/run.sh append                  # one of: append, throughput, recovery, breakdown
```

Maven is not required and neither is a JDK on the path. `bench/run.sh` uses
`JAVA_HOME` or `javac` if it finds one, and otherwise calls
`bench/bootstrap-jdk.sh`, which fetches a portable Temurin 21 into `bench/.jdk`
and touches nothing outside it. The run above used a Temurin 21 supplied through
`JAVA_HOME`.

The benchmark sources are in [bench/src](../bench/src). They compile against the
main sources directly and are not part of the published jar.

## Methodology

**This is not JMH.** It is a plain harness: an explicit warmup loop, then a
timed loop around `System.nanoTime`, then percentiles over the samples. There is
no fork per trial, no blackhole, no dead code elimination analysis and no
statistical treatment beyond the percentiles. The one concession to the JIT is
that every timed lambda returns a value which is kept live afterwards, so a call
cannot be elided as unobserved.

That would be unacceptable for measuring a tight arithmetic loop. It is
acceptable here because every operation measured is dominated by system calls
and file I/O, at hundreds of microseconds against a JIT noise floor of tens of
nanoseconds. The harness is roughly three orders of magnitude away from the
thing it would get wrong.

- **Warmup** is explicit and stated per benchmark: 2000 iterations for the
  append benchmarks, 200 runs for throughput, 10 per point for recovery. The
  fsync rows use fewer, because an fsync per event makes twenty thousand
  samples a matter of minutes; where a row took fewer samples that is printed
  next to it, since a percentile over fewer samples is a weaker claim.
- **Median and p99** are reported, by nearest rank on the sorted samples. The
  mean is shown beside them because the gap between mean and median is itself
  informative about how much the machine interfered.
- **Timing unit** is one operation: one `append` call, one whole `run` of a
  workflow, one recovery.

## Durable step throughput

A workflow of 10 steps, **every one of them carrying a `Codec`**, which is the
expensive case: a recorded step writes a `STEP_STARTED` event, then its output,
then a `STEP_SUCCEEDED` event. 2000 runs sampled after 200 warmup runs.

| Store | Median per run | p99 per run | Mean per run | Steps/s at the median |
| :-- | --: | --: | --: | --: |
| `InMemoryStore` | 6.8 µs | 63.9 µs | 12.6 µs | 1,470,588 |
| `FileStore`, `OS_BUFFERED` (the default) | 8.42 ms | 15.81 ms | 8.71 ms | **1,187** |
| `FileStore`, `SYNC_ON_EVERY_EVENT` | 29.64 ms | 36.49 ms | 30.05 ms | **337** |

The synced row is 20 warmup and 200 samples rather than 200 and 2000, because an
fsync per event makes two thousand runs a matter of minutes. The harness prints
that next to the row, and used not to.

### What the disk is actually doing

**`FileStore` fsyncs when you ask it to, and by default does not.** The
`OS_BUFFERED` row is not a measure of the disk: it is a measure of how fast this
operating system will open, append to and close a file, with the bytes handed to
the page cache and nothing forced to the device. A power cut can lose the last
events; a process crash cannot, because the writes already reached the OS.

The `SYNC_ON_EVERY_EVENT` row prices what the default declines to buy. It writes
the same bytes through the same encoder and then calls `FileChannel.force(true)`
on every event and every step output. It forces metadata as well as data,
because a log whose bytes reached the device while the length covering them did
not is a zero-byte log after a power cut. Both rows are the shipped store, which
they were not in the previous recording: the fsync used to live in a
benchmark-only `SyncedFileStore` under `bench/`, and that class is gone now that
the real store does the thing it was standing in for.

**The durability guarantee costs about 3.5x on end-to-end step throughput and
about 8.2x on a single append.** The throughput ratio came out at 2.8x, 3.2x and
3.5x across the three idle runs, which is the same neighbourhood as the 3.0 to
3.3x recorded when a benchmark-only store priced it, and below the 3.3x to 4.1x
the busy machine reported. It rose across the three because the buffered row
rose while the synced row did not, so read this as a range rather than a point.

The append multiplier is larger than the 4.5x recorded before the run directory
was cached, and the reason is that change rather than the fsync getting slower:
an un-synced append is now about half what it was, because the run directory is
established once per run instead of on every event, so the same fsync is being
divided into a smaller number. It came out at 7.9x, 7.3x and 8.2x across the
three idle runs, against 9.5x on the busy one.

One measured false start belongs here. The first version forced the run
directory on every write, and on Windows a directory cannot be opened as a
channel, so every step output threw an `IOException` that was caught and
ignored. That cost real time: synced throughput measured **83 steps/s** with the
per-write attempt, against 186 to 218 once the failure is learned once per
process. Both of those were taken back to back on the busy machine; the idle run
above reports 337. Directory forcing is therefore attempted once and turned off for the
process when the platform refuses it. That is a durability difference between
Linux and Windows and not only a speed one, and it is stated in
[durability.md](durability.md).

## Where the time goes

Measured rather than assumed. Each row is one component of `FileStore.append`,
timed on its own against an existing directory and an existing log, 20000
samples after 2000 warmup.

| Operation | Median | p99 | Mean |
| :-- | --: | --: | --: |
| Encode the event to a JSON line | 0.5 µs | 0.7 µs | 0.5 µs |
| `createDirectories` on a directory that exists | 47.2 µs | 159.1 µs | 52.4 µs |
| `exists()` on the id file | 12.5 µs | 35.4 µs | 13.8 µs |
| Open, append one line, close | 63.3 µs | 192.1 µs | 72.0 µs |
| `FileStore.append`, to a run already established | 66.6 µs | 198.1 µs | 75.4 µs |
| `FileStore.append`, the first event of a run | 864.8 µs | 1815.5 µs | 863.9 µs |

The interpretation, in order of size:

1. **Encoding is free.** 0.5 µs against 67 µs is under one percent of an append.
   The hand-written JSON writer is not worth a second thought, and replacing it
   with a library would not move this number.
2. **The file operation is now nearly all of it.** 63.3 µs of 66.6 µs is the
   open, the write and the close. An append is within about 5% of the raw file
   operation it contains. It used to be about twice it.
3. **The 40% that was `createDirectories` plus `exists()` is gone from the
   repeated path.** Those two rows, 47.2 and 12.5 µs, are still what they cost;
   they are simply no longer paid per event. `FileStore` establishes a run's
   directory once and caches it, which is
   [DECISIONS.md 5](DECISIONS.md#5-the-run-directory-is-established-once-per-run-and-cached).
4. **The cost moved rather than vanished, and the last row is where it went.**
   The first event of a run pays for a new directory, the temporary id file and
   the move, at 0.86 ms. A run of ten recorded steps pays that once against
   twenty-one appends. It is also the least reproducible row in the table, at
   1159, 829 and 865 µs across the three idle runs, while no other row in it
   moved by more than about 1.05x.

The measured effect of the cache, taken back to back on this machine with only
the store's own code changed. Both columns predate the idle run and were taken
while the machine was busy, so the ratio is the part to read, not either
absolute:

| | Before | After |
| :-- | --: | --: |
| Append latency, median | 179.3 µs | 93.1 µs |
| `FileStore.append` in the breakdown table, median | 183.6 µs | 90.7 µs |
| Step throughput, median | 595 steps/s | 610 steps/s |

**An append halved. A short run did not move.** That is not a disappointment, it
is what the arithmetic says: the throughput benchmark starts a fresh run per
iteration, so it pays the establishing cost once either way, and the 20 appends
it saves out of 21 are a small share of a run dominated by output writes and
temporary files. The 40% figure was always about appends within an established
run, and ten steps is not many appends.

## Append latency

The unit everything is built from. 20000 samples after 2000 warmup, except the
fsync row, which took 2000 samples after 200 warmup.

| Store | Median | p99 | Mean | Appends/s at the median |
| :-- | --: | --: | --: | --: |
| `InMemoryStore` | 5.7 µs | 35.0 µs | 8.4 µs | 175,439 |
| `FileStore`, `OS_BUFFERED` (the default) | 73.0 µs | 251.7 µs | 84.7 µs | 13,699 |
| `FileStore`, `SYNC_ON_EVERY_EVENT` | 595.9 µs | 1041.5 µs | 628.4 µs | 1,678 |

A recorded step is two appends plus an output write, so 1,187 steps/s against
13,699 appends/s no longer divides as neatly as it once did: an append to an
established run is now much cheaper than the per-step work around it, which is
the output write, its temporary file and rename, and once per run the
directory.

`InMemoryStore` at 5.7 µs is not zero because it is synchronized and copies. It
is there as the floor: it is what a step costs when the store is not the
bottleneck, and it shows that at 1,187 steps/s essentially 100% of the time is
the store.

## Recovery time against log length

A run records L steps and then fails on one more, which leaves it resumable.
The timed call runs the same id against a definition whose last step succeeds:
it reads the whole log, folds it, replays L recorded outputs from L separate
files, and executes the one step that has no output yet. That is exactly what a
restart does.

The setup used to be a run executed to completion and then re-run against a
longer definition. That worked only because `run` would resume a run that had
already succeeded, which was
[bug 10](BUGS.md#10-run-with-a-finished-run-id-resumed-it-and-repeated-its-unrecorded-steps),
and the benchmark broke the moment it was fixed. Two events per point are new
because the failing step writes a started and a failed event of its own.

`FileStore` with the default policy. 60 samples per point after 10 warmup.

| Steps already done | Events in the log | Median | p99 | Median per step |
| --: | --: | --: | --: | --: |
| 1 | 6 | 1.57 ms | 3.82 ms | 1.57 ms |
| 2 | 8 | 1.64 ms | 2.85 ms | 0.82 ms |
| 5 | 14 | 5.31 ms | 9.84 ms | 1.06 ms |
| 10 | 24 | 8.46 ms | 14.69 ms | 0.85 ms |
| 20 | 44 | 15.23 ms | 34.91 ms | 0.76 ms |
| 50 | 104 | 87.74 ms | 101.17 ms | 1.75 ms |
| 100 | 204 | 120.05 ms | 144.26 ms | 1.20 ms |
| 200 | 404 | 198.85 ms | 218.59 ms | 0.99 ms |
| 500 | 1004 | 404.59 ms | 448.35 ms | 0.81 ms |

**Recovery is linear in log length**, at roughly 0.8 to 1.8 ms per already
completed step, on top of a fixed cost of a millisecond or two. A run that got
500 steps in takes about four tenths of a second to pick back up. The rightmost
column wandering between 0.8 and 1.8 ms is machine noise, not shape: the 50 step
point is the outlier and it sits above both its neighbours, in all three idle
runs.

The shape is the useful part, and it is linear rather than quadratic because
nothing rescans. `eventsFor` reads the log once, `RunLog` folds it in one pass,
and each replayed step costs one `loadOutput`, which is one file read. The cost
is one file read per completed step, and file reads on this machine cost about
what the table says.

The blunt observation: **replaying a recorded step costs about as much as
executing a trivial one.** A step is 0.84 ms at 1,187 steps/s, and a replay is
0.8 to 1.8 ms. Recording a step does not make resuming it cheap in wall clock
terms.
What it buys is that the side effect does not happen again, which is the entire
point and is worth far more than the milliseconds. If your steps are real work
against real remote systems, the replay is free by comparison. If your steps are
arithmetic, `FileStore` is the wrong store and you should not have given them
codecs.

There is also a second cost that this benchmark does not measure, and it is the
one likelier to bite:
[`WorkflowEngine.recoverable(workflow)`](../src/main/java/io/github/martinkm/tandem/WorkflowEngine.java)
reads the log of **every run in the store**, not just the unfinished ones, so a
store nobody prunes makes the recovery scan slower in proportion to its whole
history. That is documented in
[docs/durability.md](durability.md#it-lists-what-the-store-holds) and is a
reason to archive completed runs.

## What these numbers are not

- **A quiet machine this time, which the previous recording was not.** 6% CPU at
  the start, against 80% before. The p99 columns are correspondingly narrower and
  are now mostly Tandem rather than mostly the neighbours.
- **Still not stable to their last digit.** Across the three idle runs, buffered
  step throughput came out at 866, 995 and 1,187 steps/s and synced at 312, 312
  and 337. Most other rows hold within about 1.1x, but that buffered row is worth
  1.4x on its own and it rose monotonically across three consecutive runs, which
  looks more like the file system warming than like noise. It was not chased
  down. Assume plus or minus 20% on any absolute figure and more on that row. The
  **ratios between rows**, and the before-and-after pairs measured back to back,
  are still what the conclusions rest on.
- **Not JMH,** as set out under [Methodology](#methodology).
- **Not cross-platform.** These are Windows 11 numbers on NTFS. File operation
  costs are the dominant term in almost every row here, and they are the term
  that differs most between operating systems. A Linux run would very likely
  show a much cheaper un-synced append and therefore a much larger fsync
  multiplier. I did not measure one, so I am not going to put a figure on it.
  One thing is known rather than guessed: forcing the run directory works on
  Linux and cannot work on Windows, so `SYNC_ON_EVERY_EVENT` is a slightly
  stronger guarantee on Linux than the numbers here were taken under. The test
  suite was run on both, 115 passing on each.
- **Not concurrent.** Every benchmark is single threaded. Tandem runs a workflow
  on the calling thread, so throughput under many concurrent runs is a different
  question, and one this harness does not answer.
- **Not measuring your steps.** The benchmark's steps append one character to a
  string. Real steps call remote systems and will dominate everything above.
  These numbers describe Tandem's own overhead, which is the only part Tandem
  controls.
