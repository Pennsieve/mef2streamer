# CLAUDE.md — mef2streamer

Reads MEF2 files and streams decoded samples on stdout as length-prefixed binary
frames. Formerly `edfwriter`; the EDF writing is gone and none of it is coming
back. Its only consumer is `processor-mef-timeseries`, which builds this repo
into `mefstreamer.jar` and converts the frames to NWB.

MEF2 only — `MEFStreamer`'s constructor throws on any other header major
version. MEF3 is a different format entirely (directory-based `.mefd`/`.timd`/
`.segd`) and was never supported.

Java 8 source level, Maven, JUnit 4.

## Build / test / run

```
mvn package                  # jar-with-dependencies in target/
mvn test                     # 26 tests
java -jar target/mef2streamer-0.0.1-SNAPSHOT-jar-with-dependencies.jar <input-dir>
MEF_CHANNELS=A1,B2 java -jar ... <input-dir>    # convert a subset
```

Small real MEF fixtures for an end-to-end check live at
`../timeseries-processor/mef2_processor/tests/resources/Sin_10Hz.mef` and
`Sin_20Hz.mef` — two channels, ~30KB each, 800Hz. Copy them into a scratch
directory (the directory name becomes the subject ID) and run the jar against
it. `../migrationtools/mefs/` has real subject data but the files are ~600MB.

## Proving a change didn't alter the output

Don't compare `output.nwb` between runs — `nwb_writer.py` sets
`identifier=f"mef_{uuid.uuid4().hex[:8]}"`, so the NWB differs on every run even
with identical inputs and zero code changes.

Compare the jar's stdout instead. It is the entire output of this repo, it is
deterministic, and 4GB of it hashes in about 35 seconds:

```
git worktree add /tmp/old <baseline-sha>
(cd /tmp/old && mvn -B -o -q clean package -DskipTests)
java -jar /tmp/old/target/*-jar-with-dependencies.jar "$IN" 2>/dev/null | shasum -a 256
java -jar     ./target/*-jar-with-dependencies.jar "$IN" 2>/dev/null | shasum -a 256
```

If a commit intentionally changes trailing bytes, hash the common prefix of both
streams rather than the whole thing.

## Running it through processor-mef-timeseries locally

`processor-mef-timeseries/docker-compose.override.yml` (untracked, local only)
bind-mounts this repo's `target/mef2streamer-0.0.1-SNAPSHOT-jar-with-dependencies.jar`
over `/processor/mefstreamer.jar`. Compose merges it automatically, so
`mvn package` here then `make run` there exercises the working tree without the
Dockerfile's clone-from-GitHub. `MEF_CHANNELS` passes through from the host
shell.

## Environment

Nothing here talks to AWS or an API. It is a local, standalone jar. There is no
dev/prod distinction — the only environment that matters is the container
`processor-mef-timeseries` builds.

## The contract with processor-mef-timeseries

This is the whole public surface. Changing any of it breaks the processor:

- Main class `edu.upenn.cis.eeg.mef.mefstreamer.MEFStreamerMain`, declared in
  the assembly plugin manifest in `pom.xml`.
- Invoked as `java -jar mefstreamer.jar <INPUT_DIR>`, one positional arg.
- Artifact name. The processor's Dockerfile copies
  `mef2edf-0.0.1-SNAPSHOT-jar-with-dependencies.jar` by exact path, so the build
  emits a byte-identical copy under that legacy name via `maven-antrun-plugin`
  even though the artifactId is now `mef2streamer`. **Delete the alias and its
  antrun execution in the same change that updates the processor** — until then,
  removing it breaks that image build immediately, because `EDFWRITER_REF`
  defaults to `main`.
- Frame protocol `[type:1][len:4 LE][payload]`, types 1–5. See README.
- `CHANNEL_META` JSON keys. The processor reads `voltage_conversion_factor` and
  warns loudly if it is absent.

The processor's Dockerfile clones this repo at `EDFWRITER_REF` (default `main`)
and builds it, so anything merged to main ships on the next processor image
build. There is no release or version pin by default.

## Gotchas

**stdout is the protocol.** Any `System.out.println` in the main path injects
bytes into the frame stream and desynchronises the reader. All diagnostics use
`System.err`. Several strays were fixed in the cleanup; don't reintroduce them.

**Samples are raw A/D counts, not microvolts.** `voltage_conversion_factor` in
`CHANNEL_META` is the only thing that makes them a physical measurement. Its
default of 1.0 is indistinguishable from a real 1.0 µV/count, so a header that
fails to populate silently yields counts passed off as microvolts.
`MefHeader2Test` pins the offset (456) and length (8).

**The repo used to carry three identical copies of `MefHeader2`** in different
packages. `MEFStreamer` resolved the same-package one by proximity, not import,
so editing the wrong copy changed nothing and produced no compile error. Only
`edu/upenn/cis/eeg/mef/mefstreamer/MefHeader2.java` survives. Do not add another.

**MEF2 is enforced, not assumed.** `MEFStreamer` throws unless the header's
major version is 2. Header fields sit at fixed offsets, so without that check
another format parses into nonsense and streams out as plausible samples —
a silent wrong answer. `MefHeader2` still has pre-2.0 parsing branches; they are
now unreachable through `MEFStreamer` and have no fixture. Don't re-enable them
without one.

**Only ten main source files are reachable from `main`.** The repo previously
held 160. To re-check the closure after adding code:

```
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
javac -d /tmp/reach -cp "$(cat /tmp/cp.txt)" -sourcepath src/main/java \
    src/main/java/edu/upenn/cis/eeg/mef/mefstreamer/MEFStreamerMain.java
find /tmp/reach -name '*.class'
```

javac pulls in exactly the transitive closure, so the `.class` files it emits
are the files that matter. Anything in `src/main/java` that doesn't show up is
dead.

**Nothing exits 0 on failure any more.** A missing argument, a bad path, no
`.mef` files, an unmatched `MEF_CHANNELS` name, or an IO error mid-stream all
exit 1. They used to print to stdout and exit 0, so a run that converted nothing
looked successful. Keep it that way.

**`mvn package -DskipTests` still compiles tests.** A test referencing a deleted
class breaks the processor's Docker build even though tests never run there.

**A stale `target/` can produce a bogus "bad class file … NoSuchFileException".**
Run `mvn clean` and it goes away.

## Don't do this

- Don't print to stdout outside the `Framer`.
- Don't add a second copy of `MefHeader2`, or of anything else, "for a different
  package".
- Don't add dependencies casually. The pom is down to guava, jackson-annotations,
  jsr305, and junit. It used to pull kafka, mongodb, tika, resteasy and GWT, none
  of which were reachable.
- Don't commit `.edf` output or other generated files to the repo root. A 19MB
  `output.edf` was tracked for a year.

## Questions

- **Pending cross-repo rename.** This repo is becoming `mef2streamer` on GitHub.
  Still to do in `processor-mef-timeseries`, as one change: the `git clone` URL
  and `api.github.com/repos/...` ADD in its Dockerfile, the `EDFWRITER_REF` build
  arg name, the `cp` of the jar (switch to the `mef2streamer-` name), and
  `docker-compose.yml`. Then drop the legacy-jar alias here. GitHub 301-redirects
  a renamed repo for both clone and API, so nothing breaks in the meantime.
- The processor's `_wait_for_process` logs a non-zero exit code but does not
  raise. A `MEF_CHANNELS` typo makes this jar exit 1 having sent no frames; the
  processor would then see an empty stream rather than a clear failure. Worth
  fixing on the processor side.
- `FrameStreamer` still takes `directoryPath`, `subjectid` and `numsignals` in
  its constructor and uses none of them. Left alone to keep this change focused.
