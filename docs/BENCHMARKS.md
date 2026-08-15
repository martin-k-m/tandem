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
| RAM | 15.43 GB total, 2.52 GB free at the start of the run |
| OS | Windows 11 Pro, build 26200 |
| Storage | Timetec 35TT2280GEN4P-2TB, NVMe SSD |
| JDK | Temurin 21.0.12+8, 64-Bit Server VM, mixed mode, sharing |
| Working directory | `C:\Users\comma\AppData\Local\Temp`, on the SSD above |
| Idle | **No.** 79% CPU reported at the start of the run |
| Date | 2026-08-15T14:01:17Z |
| Commit | `f793748`, on branch `harden/evidence` |

The machine was not idle. Another workload was running throughout, which is why
the p99 columns are wide and why the same benchmark run three times gave file
figures spread over a factor of 1.6. This is stated rather than hidden, and the
[caveats](#what-these-numbers-are-not) say which conclusions survive it.

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
| `InMemoryStore` | 10.1 µs | 62.5 µs | 17.3 µs | 990,099 |
| `FileStore` (as shipped, no fsync) | 17.93 ms | 50.21 ms | 20.28 ms | **558** |
| `SyncedFileStore` (fsync per event) | 59.40 ms | 79.37 ms | 58.63 ms | **168** |

### What the disk is actually doing

**`FileStore` does not fsync, and never has.** Its own javadoc and
[docs/durability.md](durability.md) say so. The 558 steps/s row is therefore not
a measure of the disk: it is a measure of how fast this operating system will
open, append to and close a file, with the bytes handed to the page cache and
nothing forced to the device. A power cut can lose the last events; a process
crash cannot, because the writes already reached the OS.

The 168 steps/s row prices what `FileStore` declines to buy.
`SyncedFileStore` is a benchmark-only store, in
[bench/src](../bench/src/io/github/martinkm/tandem/SyncedFileStore.java), that
writes the same bytes through the same encoder and then calls
`FileChannel.force(true)` on every event and every step output. It forces
metadata as well as data, because a log whose bytes reached the device while the
length covering them did not is a zero-byte log after a power cut.

**The durability guarantee costs about 3.3x on end-to-end step throughput and
about 4.5x on a single append.** That ratio held across all three runs I made
(3.0x, 3.2x, 3.3x on throughput; 5.1x, 4.5x, 4.5x on append) while the absolute
numbers moved by 1.6x, so the ratio is the part worth quoting.

Two things make that multiplier smaller than it would be on a quieter design.
The un-synced baseline is already spending most of its time in the kernel, so
the fsync is added to something slow rather than to something fast. And
`SyncedFileStore` opens and closes a channel per append, as any store with no
per-run state must; a store holding an open channel per run would pay the fsync
without paying the open, so 168 steps/s is an upper bound on the cost of
durability, not the best achievable.

## Where the time goes

Measured rather than assumed. Each row is one component of `FileStore.append`,
timed on its own against an existing directory and an existing log, 20000
samples after 2000 warmup.

| Operation | Median | p99 | Mean |
| :-- | --: | --: | --: |
| Encode the event to a JSON line | 0.8 µs | 1.3 µs | 0.8 µs |
| `createDirectories` on a directory that exists | 142.9 µs | 460.8 µs | 161.2 µs |
| `exists()` on the id file | 32.1 µs | 141.9 µs | 36.8 µs |
| Open, append one line, close | 219.4 µs | 606.9 µs | 262.4 µs |
| `FileStore.append`, all of the above | 437.1 µs | 1134.6 µs | 518.4 µs |

The interpretation, in order of size:

1. **Encoding is free.** 0.8 µs against 437 µs is 0.2% of an append. The
   hand-written JSON writer is not worth a second thought, and replacing it with
   a library would not move this number.
2. **The file operation is about half.** 219 µs of 437 µs is the open, the
   write and the close. On this OS and this filesystem an append is expensive
   before anything is forced anywhere.
3. **About 175 µs, 40% of every append, is spent re-establishing things that
   only change on the first append of a run.** `FileStore.append` calls
   `runDirectory`, which calls `createDirectories` and then checks whether the
   id file exists, on every single event. Both are no-ops after the first event
   of a run and both cost real syscalls every time. This is a genuine
   inefficiency and it is written up as a known compromise in
   [DECISIONS.md](DECISIONS.md#5-the-run-directory-is-re-established-on-every-append).

The rows do not add exactly to the total: 0.8 + 142.9 + 32.1 + 219.4 = 395 µs
against a measured 437 µs. The remainder is path building and the fact that the
components were timed in a separate pass, under different momentary load. Treat
the breakdown as proportions, not as an equation.

Note also that this table's `FileStore.append` median, 437.1 µs, is higher than
the 344.3 µs in the append table from the same run. Same code, same process,
different minute. That gap is the clearest single illustration of how much the
busy machine moved things.

## Append latency

The unit everything is built from. 20000 samples after 2000 warmup, except the
fsync row, which took 2000 samples after 200 warmup.

| Store | Median | p99 | Mean | Appends/s at the median |
| :-- | --: | --: | --: | --: |
| `InMemoryStore` | 5.5 µs | 32.6 µs | 8.6 µs | 181,818 |
| `FileStore` (as shipped, no fsync) | 344.3 µs | 714.6 µs | 360.4 µs | 2,904 |
| `SyncedFileStore` (fsync per event) | 1543.2 µs | 2580.3 µs | 1592.8 µs | 648 |

A recorded step is two appends plus an output write, so 558 steps/s against
2904 appends/s is consistent: roughly 5 to 6 file operations per step, once the
output write and its temporary file and rename are counted.

`InMemoryStore` at 5.5 µs is not zero because it is synchronized and copies. It
is there as the floor: it is what a step costs when the store is not the
bottleneck, and it shows that at 558 steps/s essentially 100% of the time is the
store.

## Recovery time against log length

A run of L recorded steps is executed to completion. Then the same run id is run
again against a definition with one more step. The timed call reads the whole
log, folds it, replays L recorded outputs from L separate files, and executes
the one step that has no output yet. That is exactly what a restart does.

`FileStore` as shipped. 60 samples per point after 10 warmup.

| Steps already done | Events in the log | Median | p99 | Median per step |
| --: | --: | --: | --: | --: |
| 1 | 4 | 3.73 ms | 5.37 ms | 3.73 ms |
| 2 | 6 | 4.15 ms | 12.66 ms | 2.07 ms |
| 5 | 12 | 8.54 ms | 13.77 ms | 1.71 ms |
| 10 | 22 | 14.92 ms | 21.57 ms | 1.49 ms |
| 20 | 42 | 26.00 ms | 40.59 ms | 1.30 ms |
| 50 | 102 | 65.96 ms | 120.94 ms | 1.32 ms |
| 100 | 202 | 187.83 ms | 308.44 ms | 1.88 ms |
| 200 | 402 | 428.94 ms | 806.63 ms | 2.14 ms |
| 500 | 1002 | 1142.33 ms | 2430.28 ms | 2.28 ms |

**Recovery is linear in log length**, at roughly 1.3 to 2.3 ms per already
completed step, on top of a fixed cost of about 2 to 3 ms. A run that got 500
steps in takes about 1.1 seconds to pick back up. The rightmost column drifting
upward from 1.3 to 2.3 ms is mild and is within the run-to-run noise on this
machine: an earlier, quieter run of the same benchmark gave a flatter 1.2 to 1.5
ms per step across the whole range, with 500 steps at 715 ms.

The shape is the useful part, and it is linear rather than quadratic because
nothing rescans. `eventsFor` reads the log once, `RunLog` folds it in one pass,
and each replayed step costs one `loadOutput`, which is one file read. The cost
is one file read per completed step, and file reads on this machine cost about
what the table says.

The blunt observation: **replaying a recorded step costs about as much as
executing a trivial one.** A step is 1.8 ms at 558 steps/s, and a replay is 1.3
to 2.3 ms. Recording a step does not make resuming it cheap in wall clock terms.
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

- **Not a quiet machine.** 79% CPU from an unrelated workload throughout. The
  p99 columns in particular measure that workload as much as they measure
  Tandem.
- **Not stable to their last digit.** I ran the whole suite three times. The
  `FileStore` append median came out at 391.4, 242.6 and 344.3 µs; end-to-end
  throughput at 466, 554 and 558 steps/s. Assume plus or minus 40% on any
  absolute figure. The **ratios between rows** were stable to within 10% across
  all three runs, and those are what the conclusions rest on.
- **Not JMH,** as set out under [Methodology](#methodology).
- **Not cross-platform.** These are Windows 11 numbers on NTFS. File operation
  costs are the dominant term in almost every row here, and they are the term
  that differs most between operating systems. A Linux run would very likely
  show a much cheaper un-synced append and therefore a much larger fsync
  multiplier. I did not run one, so I am not going to put a figure on it.
- **Not concurrent.** Every benchmark is single threaded. Tandem runs a workflow
  on the calling thread, so throughput under many concurrent runs is a different
  question, and one this harness does not answer.
- **Not measuring your steps.** The benchmark's steps append one character to a
  string. Real steps call remote systems and will dominate everything above.
  These numbers describe Tandem's own overhead, which is the only part Tandem
  controls.
