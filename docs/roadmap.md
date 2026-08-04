# Roadmap

What Tandem does not do. Everything here is absent rather than stubbed: no
method accepts a call and quietly does nothing, so anything that compiles
against 0.1 works in 0.1.

## Not implemented

| Area | Status | Notes |
| :-- | :-- | :-- |
| Parallel and branching steps | Planned | The definition is a straight line today. Fan-out with a join is the most requested shape and the most likely next addition. |
| Distributed coordination | Planned | Several machines cooperating on one run needs a worker protocol and leases. Large, and the reason the scheduler is honest about being in-process. |
| Leader election for schedules | Planned | Without it, two application instances each fire the same recurring workflow. |
| Persistent timers | Planned | A delay that survives a restart, rather than living in a `ScheduledExecutorService`. |
| Definition versioning | Planned | Resuming an old run against an edited definition matches recorded outputs by step name and does not detect that the shape changed. |
| Dead letter queue | Planned | Runs that exhausted their retries are recorded in the log but not collected anywhere for triage. |
| Automatic recovery scan | Planned | Nothing looks for interrupted runs and restarts them. Resuming is a call your application makes. |
| Metrics exporters | Not planned | `WorkflowListener` is the hook. Wiring it to Micrometer or OpenTelemetry belongs in your application, not in a dependency-free library. |
| A JSON mapper | Not planned | It would land in every consumer's classpath and compete with theirs. `Codec` is two methods. |
| Human tasks and approvals | Not planned | A workflow that waits days for a person is a different product. |

## The threading question

A run executes on the thread that calls it. For short workflows that is exactly
right: no scheduling, no context switching, and a stack trace that reads
straight through your steps.

For long ones it is a real limit. A workflow that waits an hour occupies a
thread for an hour. A step timeout bounds how long any single step may take, but
it does not change where the run executes: the calling thread waits either way. The current answer is to run it on an executor you control,
which works but does not survive a restart. Persistent timers and a worker
protocol are what would actually fix it, which is why both are on the list.

## Ordering

Parallel steps come first. They are self-contained, do not require a
distribution story, and are the thing most workflows reach for after a straight
line stops being enough.

Distributed execution is the largest item and would reshape most of the others.
It is deliberately not being started before the single-node model is worn in.

## Not a goal

Being Temporal. Temporal solves distributed durable execution with a server, a
worker protocol, and deterministic replay of your code. That is a good design
and a heavy one. Tandem is for applications that want durable, compensating
workflows inside a JVM they already run, without adopting a cluster.

If you need what Temporal does, use Temporal.
