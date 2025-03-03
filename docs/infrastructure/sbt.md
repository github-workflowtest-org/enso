---
layout: developer-doc
title: Build Tools
category: infrastructure
tags: [infrastructure, build]
order: 1
---

# Build Tools

The project is built using the Scala Build Tool which manages dependencies
between the projects as well as external dependencies and allows for incremental
compilation. The build configuration is defined in
[`build.sbt`](../../build.sbt).

<!-- MarkdownTOC levels="2,3" autolink="true" -->

- [Incremental Compilation](#incremental-compilation)
- [Compile Hooks](#compile-hooks)
- [Helper Tasks](#helper-tasks)
  - [Graal and Flatc Version Check](#graal-and-flatc-version-check)
  - [Benchmarks](#benchmarks)
  - [Build Information](#build-information)
  - [Instruments Generation](#instruments-generation)
  - [Flatbuffers Generation](#flatbuffers-generation)
  - [Ensuring JARs Were Loaded](#ensuring-jars-were-loaded)
  - [Debugging Command](#debugging-command)
  - [Recompile Parser](#recompile-parser)
- [Native Image](#native-image)

<!-- /MarkdownTOC -->

## Incremental Compilation

To help wit build times, we do not want to rebuild the whole project with every
change, but to only recompile the files that have been affected by the change.
This is handled by sbt which under the hood uses
[zinc](https://github.com/sbt/zinc) (the incremental compiler for Scala). zinc
analyses the compiled files and detects dependencies between them to determine
which files have to be recompiled when something has been changed.

## Compile Hooks

There are some invariants that are specific to our project, so they are not
tracked by sbt, but we want to ensure that they hold to avoid cryptic errors at
compilation or runtime.

To check some state before compilation, we add our tasks as dependencies of
`Compile / compile / compileInputs` by adding the following to the settings of a
particular project.

```
Compile / compile / compileInputs := (Compile / compile / compileInputs)
        .dependsOn(preCompileHookTask)
        .value
```

Tasks that should be run before compilation, should be attached to the
`compileInputs` task. That is because the actual compilation process is ran in
the task `compileIncremental`. `Compile / compile` depends on
`compileIncremental` but if we add our dependency to `Compile / compile`, it is
considered as independent with `compileIncremental`, so sbt may schedule it to
run in parallel with the actual compilation process. To guarantee that our
pre-flight checks complete _before_ the actual compilation, we add them as a
dependency of `compileInputs` which runs _strictly before_ actual compilation.

To check some invariants _after_ compilation, we can replace the original
`Compile / compile` task with a custom one which does its post-compile checks
and returns the result of `(Compile / compile).value`.

## Helper Tasks

There are additional tasks defined in the [`project`](../../project) directory.
They are used by [`build.sbt`](../../build.sbt) to provide some additional
functionality.

### Graal and Flatc Version Check

[`EnvironmentCheck`](../../project/EnvironmentCheck.scala) defines a helper
function that can be attached to the default `Global / onLoad` state transition
to run a version check when loading the sbt project. This helper function
compares the version of JVM running sbt with GraalVM version defined in
[`build.sbt`](../../build.sbt) and the version of `flatc` installed in the
system with the Flatbuffers library version defined in
[`build.sbt`](../../build.sbt). If the versions do not match it reports an error
telling the user to change to the correct version.

### Benchmarks

[`BenchTasks`](../../project/BenchTasks.scala) defines configuration keys for
benchmarking.

### Build Information

[`BenchTasks`](../../project/BuildInfo.scala) records version information
including what git commit has been used for compiling the project. This
information is used by `enso --version`.

### Instruments Generation

Truffle annotation processor generates a file that registers instruments
provided by the runtime. Unfortunately, with incremental compilation, only the
changed instruments are recompiled and the annotation processor does not detect
this, so un-changed instruments get overwritten.

In the past we had a pre-compile task (see
[FixInstrumentsGeneration](https://github.com/enso-org/enso/blob/8ec2a92b770dea35e47fa9287dbdd1363aabc3c0/project/FixInstrumentsGeneration.scala))
that detected changes to instruments and if only one of them was to be
recompiled, it forced recompilation of all of them, to ensure consistency. This
workaround helped to avoid later runtime issues but sometimes triggered a
cascade of recompilations, which weren't clear to the end user. Instead, to
avoid overwriting entries in META-INF files, individual services were moved to
separate subprojects and during assembly of uber jar we concatenate meta files
with the same service name.

### Flatbuffers Generation

[`GenerateFlatbuffers`](../../project/GenerateFlatbuffers.scala) defines the
task that runs the Flatbuffer compiler `flatc` whenever the flatbuffer
definitions have been changed. It also makes sure that `flatc` is available on
PATH and that its version matches the version of the library. It reports any
errors.

### Debugging Command

[`WithDebugCommand`](../../project/WithDebugCommand.scala) defines a command
that allows to run a task with additional JVM-level flags.

### Recompile Parser

[`RecompileParser`](../../project/RecompileParser.scala) defines a task that can
be attached to the `compile` task in configurations of the `syntax` project.
This task ensures that the `syntax` project is recompiled whenever
`syntax-definition` changes.

## Native Image

[`NativeImage`](../../project/NativeImage.scala) task is described at
[Native Image](native-image.md).
