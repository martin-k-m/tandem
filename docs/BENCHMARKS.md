# Benchmarks

Every number here comes from one recorded run of `bench/run.sh` on the machine
described below. The raw output of that run is committed as
[bench/results.txt](../bench/results.txt), including the environment block the
script prints, so no figure in this document is separated from the machine that
produced it.

Read the [caveats](#what-these-numbers-are-not) before quoting anything. The
short version: the machine was busy, absolute values move by up to 1.6x between
runs, and the ratios are the durable part.

## Environment

| | |
| :-- | :-- |
| CPU | Intel Core Ultra 9 285H, 16 physical / 16 logical cores, 2900 MHz nominal |
| RAM | 15.43 GB total, 2.26 GB free at the start of the run |
| OS | Windows 11 Pro, build 26200 |
| Storage | Timetec 35TT2280GEN4P-2TB, NVMe SSD |
| JDK | Temurin 21.0.12+8, 64-Bit Server VM, mixed mode, sharing |
| Working directory | `C:\Users\comma\AppData\Local\Temp`, on the SSD above |
| Idle | **No.** 80% CPU reported at the start of the run |
| Date | 2026-08-17T20:02:21Z |
| Commit | `6314968` plus the working tree of branch `durable-filestore` |

The machine was not idle. Another workload was running throughout, which is why
the p99 columns are wide and why the same benchmark run repeatedly gives file
figures spread over a factor of 1.6. This is stated rather than hidden, and the
[caveats](#what-these-numbers-are-not) say which conclusions survive it. The
earlier recorded run, against commit `f793748`, is in this file's history; where
a comparison with it matters below it was re-measured back to back on this
machine rather than quoted across runs.

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
| `InMemoryStore` | 8.4 µs | 48.9 µs | 12.9 µs | 1,190,476 |
| `FileStore`, `OS_BUFFERED` (the default) | 16.25 ms | 35.51 ms | 17.31 ms | **615** |
| `FileStore`, `SYNC_ON_EVERY_EVENT` | 53.78 ms | 95.77 ms | 60.73 ms | **186** |

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

**The durability guarantee costs about 3.3x on end-to-end step throughput and
about 9.5x on a single append.** The throughput ratio came out at 3.3x, 4.1x and
3.8x across three runs of that benchmark, which is the same neighbourhood as the
3.0 to 3.3x recorded when a benchmark-only store priced it.

The append multiplier is larger than the 4.5x recorded earlier, and the reason is
the other change in this pass rather than the fsync getting slower: an un-synced
append is now about half what it was, because the run directory is established
once per run instead of on every event, so the same fsync is being divided into
a smaller number.

One measured false start belongs here. The first version forced the run
directory on every write, and on Windows a directory cannot be opened as a
channel, so every step output threw an `IOException` that was caught and
ignored. That cost real time: synced throughput measured **83 steps/s** with the
per-write attempt, against 186 to 218 once the failure is learned once per
process. Directory forcing is therefore attempted once and turned off for the
process when the platform refuses it. That is a durability difference between
Linux and Windows and not only a speed one, and it is stated in
[durability.md](durability.md).

## Where the time goes

Measured rather than assumed. Each row is one component of `FileStore.append`,
timed on its own against an existing directory and an existing log, 20000
samples after 2000 warmup.

| Operation | Median | p99 | Mean |
| :-- | --: | --: | --: |
| Encode the event to a JSON line | 0.5 µs | 0.9 µs | 0.5 µs |
| `createDirectories` on a directory that exists | 57.6 µs | 279.8 µs | 75.5 µs |
| `exists()` on the id file | 14.0 µs | 73.0 µs | 17.9 µs |
| Open, append one line, close | 92.4 µs | 482.8 µs | 142.6 µs |
| `FileStore.append`, to a run already established | 99.0 µs | 480.8 µs | 155.0 µs |
| `FileStore.append`, the first event of a run | 2243.7 µs | 5122.0 µs | 2292.2 µs |

The interpretation, in order of size:

1. **Encoding is free.** 0.5 µs against 99 µs is half a percent of an append.
   The hand-written JSON writer is not worth a second thought, and replacing it
   with a library would not move this number.
2. **The file operation is now nearly all of it.** 92.4 µs of 99.0 µs is the
   open, the write and the close. An append is within about 10% of the raw file
   operation it contains. It used to be about twice it.
3. **The 40% that was `createDirectories` plus `exists()` is gone from the
   repeated path.** Those two rows, 57.6 and 14.0 µs, are still what they cost;
   they are simply no longer paid per event. `FileStore` establishes a run's
   directory once and caches it, which is
   [DECISIONS.md 5](DECISIONS.md#5-the-run-directory-is-established-once-per-run-and-cached).
4. **The cost moved rather than vanished, and the last row is where it went.**
   The first event of a run pays for a new directory, the temporary id file and
   the move, at 2.2 ms. A run of ten recorded steps pays that once against
   twenty-one appends.

The measured effect of the cache, taken back to back on this machine with only
the store's own code changed:

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
| `InMemoryStore` | 5.7 µs | 34.2 µs | 8.4 µs | 175,439 |
| `FileStore`, `OS_BUFFERED` (the default) | 82.5 µs | 371.2 µs | 106.2 µs | 12,121 |
| `FileStore`, `SYNC_ON_EVERY_EVENT` | 787.5 µs | 1508.9 µs | 818.8 µs | 1,270 |

A recorded step is two appends plus an output write, so 615 steps/s against
12,121 appends/s no longer divides as neatly as it once did: an append to an
established run is now much cheaper than the per-step work around it, which is
the output write, its temporary file and rename, and once per run the
directory.

`InMemoryStore` at 5.7 µs is not zero because it is synchronized and copies. It
is there as the floor: it is what a step costs when the store is not the
bottleneck, and it shows that at 615 steps/s essentially 100% of the time is the
store.

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
| 1 | 6 | 2.55 ms | 5.82 ms | 2.55 ms |
| 2 | 8 | 2.65 ms | 6.86 ms | 1.33 ms |
| 5 | 14 | 7.49 ms | 18.16 ms | 1.50 ms |
| 10 | 24 | 14.64 ms | 19.99 ms | 1.46 ms |
| 20 | 44 | 26.06 ms | 32.92 ms | 1.30 ms |
| 50 | 104 | 92.19 ms | 123.70 ms | 1.84 ms |
| 100 | 204 | 123.65 ms | 188.23 ms | 1.24 ms |
| 200 | 404 | 197.09 ms | 340.28 ms | 0.99 ms |
| 500 | 1004 | 447.55 ms | 740.12 ms | 0.90 ms |

**Recovery is linear in log length**, at roughly 0.9 to 1.8 ms per already
completed step, on top of a fixed cost of a couple of milliseconds. A run that
got 500 steps in takes about half a second to pick back up. The rightmost column
wandering between 0.9 and 1.8 ms is machine noise, not shape: the 50 step point
is the outlier and it sits above both its neighbours.

The shape is the useful part, and it is linear rather than quadratic because
nothing rescans. `eventsFor` reads the log once, `RunLog` folds it in one pass,
and each replayed step costs one `loadOutput`, which is one file read. The cost
is one file read per completed step, and file reads on this machine cost about
what the table says.

The blunt observation: **replaying a recorded step costs about as much as
executing a trivial one.** A step is 1.6 ms at 615 steps/s, and a replay is 0.9
to 1.8 ms. Recording a step does not make resuming it cheap in wall clock terms.
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

- **Not a quiet machine.** 80% CPU from an unrelated workload throughout. The
  p99 columns in particular measure that workload as much as they measure
  Tandem.
- **Not stable to their last digit.** Across the runs made for this pass,
  buffered step throughput came out at 610, 615, 784 and 835 steps/s and synced
  at 186, 192 and 218. Assume plus or minus 40% on any absolute figure. The
  **ratios between rows**, and the before-and-after pairs measured back to back,
  are what the conclusions rest on.
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
