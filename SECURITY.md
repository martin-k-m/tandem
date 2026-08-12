# Security Policy

## Supported versions

Fixes land on the latest released version, published to GitHub Packages as
`io.github.martinkm:tandem`. There are no long-lived maintenance branches.

## Reporting a vulnerability

Please report suspected vulnerabilities privately rather than in a public
issue. Use GitHub's [private vulnerability reporting](https://github.com/martin-k-m/tandem/security/advisories/new)
for this repository, or email martinkmuskov@gmail.com.

Include a minimal workflow definition and the sequence that reproduces the
problem. You can expect an acknowledgement within a few days.

## Scope

Tandem is an embeddable library, not a service. It runs inside the host
application's process and has no network listener of its own. It ships with zero
runtime dependencies, so the supply-chain surface is the JDK and the build
toolchain alone — there is no transitive tree to audit.

The areas most worth scrutiny:

- **Durable state.** Runs are persisted so they can resume after a restart. A
  crafted or corrupted state record that causes tandem to execute the wrong step,
  skip a compensation, or read back a run as another is in scope. The library
  trusts the store it was given; treat that store as you would any datastore
  holding application state.
- **Workflow definitions** are code supplied by the host application, not
  untrusted input, and are trusted accordingly. If your application feeds
  externally-controlled data into a workflow, validate it at that boundary.
