# edfwriter — MEF2 streamer

Reads MEF2 files (Multiscale Electrophysiology Format — EEG/iEEG time series)
and streams the decoded samples to stdout as length-prefixed binary frames. This
is how iEEG.org recordings get into Pennsieve.

**The name is a leftover.** This repo used to write EDF files. It no longer does,
and there is no EDF code left in it. It decodes MEF and emits a frame stream;
something downstream decides what to write.

## How it's used

The only consumer is
[processor-mef-timeseries](https://github.com/Pennsieve/processor-mef-timeseries).
Its Dockerfile clones this repo, builds it with Maven, and copies the result in
as `mefstreamer.jar`. At runtime the Python processor launches that jar as a
subprocess, reads the frames off its stdout, and assembles them into NWB.

```
 .mef files ──> [ this repo, as mefstreamer.jar ] ──frames on stdout──>
                                                    processor-mef-timeseries ──> output.nwb
```

The processor's `EDFWRITER_REF` build arg defaults to `main`, so **anything
merged to main here ships on the next processor image build.** There is no
release or tag step. Pin `EDFWRITER_REF` to a SHA if you need a build to stay
put.

## Build

```
mvn package        # target/mef2edf-0.0.1-SNAPSHOT-jar-with-dependencies.jar
mvn test
```

Java 8 source level, Maven, JUnit 4. The artifact name matters — the processor's
Dockerfile copies that exact path.

## Run

```
java -jar target/mef2edf-0.0.1-SNAPSHOT-jar-with-dependencies.jar <input-dir>
```

`<input-dir>` is a directory of `.mef` files. One file is one channel, and the
channel name is the filename without its extension (`EEG AD 01-Ref.mef` →
`EEG AD 01-Ref`). The directory name is read as the subject ID.

stdout is the frame stream and nothing else. All logging goes to stderr, so
redirect stdout to a file or a pipe — don't read it in a terminal.

## Selecting channels

`MEF_CHANNELS` limits the run to named channels. Unset or empty converts
everything in the directory.

```
MEF_CHANNELS=A1,B2 java -jar mefstreamer.jar /data/input

# names containing spaces are fine; quote the whole value
MEF_CHANNELS="EEG AD 01-Ref,EEG PT 01-Ref" java -jar mefstreamer.jar /data/input
```

- Comma-separated. Whitespace around each entry is trimmed; spaces *inside* a
  name are part of it.
- Matched case-insensitively against the `.mef` filename minus its extension —
  the same string that appears as `name` in `CHANNEL_META`.
- Output order follows the directory listing, not the order you list names in.
- **If any requested name matches no file, nothing is converted.** The run prints
  the missing names and the available ones to stderr and exits 1, before writing
  a single frame. Converting the subset that happened to match would look like a
  successful run that is quietly short a channel.

## Exit codes

`0` only on a complete conversion. `1` for: no argument, path is not a
directory, no `.mef` files found, an unmatched `MEF_CHANNELS` name, or an IO
error mid-stream.

Note for anyone reading the stream: a non-zero exit can arrive after frames have
already been written, so check the exit code rather than assuming a stream that
started cleanly finished cleanly.

## Output protocol

Every frame is `[type:1][length:4 LE][payload]`, with all multi-byte numbers
little-endian. Frames are flushed as they are produced.

| Type | Name | Payload |
|---|---|---|
| 1 | `CHANNEL_META` | UTF-8 JSON; opens a channel |
| 2 | `SEGMENT_START` | `int64` start time (µs) + `float64` rate (Hz) |
| 3 | `SAMPLES_INT32` | raw A/D counts, `int32` each |
| 4 | `SEGMENT_END` | `int64` last sample time (µs) + `int64` sample count |
| 5 | `END` | empty; sent once, after the last channel |

Channels are emitted one after another. A `CHANNEL_META` frame means the
previous channel is finished and a new one has begun; there is no explicit
channel-end frame. Within a channel there is one or more
`SEGMENT_START … SAMPLES_INT32 … SEGMENT_END` groups.

### CHANNEL_META

```json
{
  "name": "Sin_10Hz",
  "type": "Unknown",
  "description": "Unknown signal type",
  "unit": "counts",
  "voltage_conversion_factor": 0.030518043793392843,
  "low_cut_hz": 0.0,
  "high_cut_hz": 0.0,
  "rate_hz": 800.0,
  "reported_start_us": 1549986912000000,
  "reported_end_us": 1549986927000000
}
```

`reported_*` are the MEF header's own claimed bounds. They are not verified
against the blocks actually read, and can disagree with the segment times.

`low_cut_hz` and `high_cut_hz` come straight from the header and are frequently
`0.0`, meaning "not recorded" rather than "no filter".

### Samples are counts, not volts

`SAMPLES_INT32` carries raw A/D counts. `voltage_conversion_factor` is the
microvolts-per-count scale from the MEF header, and multiplying by it is the only
thing that turns the samples into a physical measurement.

Its default is `1.0`, which is indistinguishable from a genuine 1.0 µV/count. A
header that failed to populate therefore yields counts silently passed off as
microvolts. The processor warns when the field is missing entirely.

### Segments and gaps

A channel is split into segments wherever the recording has a gap. Each MEF
block's start time is compared to where the previous block's samples should have
ended; if it differs by more than a quarter of one sampling period, the current
segment is closed and a new one opened. A continuous recording produces exactly
one segment.

### Channel type

`type` and `description` are guessed from the filename — a case-insensitive
substring test, first match wins, in this order:

| Filename contains | type |
|---|---|
| `grid` | `ECOG` |
| `seeg` or `depth` | `SEEG` |
| `eeg` | `EEG` |
| `eog` | `EOG` |
| `ecg` or `ekg` | `ECG` |
| `emg` | `EMG` |
| *none of the above* | `Unknown` |

It is a guess from a filename, not a reading of the file. Order matters:
`EEG depth 3` is tagged `SEEG`, because `depth` is tested before `eeg`.

## Local development against the processor

To run the full pipeline against your working tree instead of the jar built from
GitHub, drop a `docker-compose.override.yml` into `processor-mef-timeseries`
that bind-mounts your local jar over the one in the image:

```yaml
services:
  processor:
    volumes:
      - ./data/input:/data/input
      - ./data/output:/data/output
      - ../edfwriter/target/mef2edf-0.0.1-SNAPSHOT-jar-with-dependencies.jar:/processor/mefstreamer.jar:ro
    environment:
      MEF_CHANNELS: "${MEF_CHANNELS:-}"
```

Compose merges that automatically, so `mvn package` here then `make run` there
picks it up with no extra flags.

## Verifying a change didn't alter the output

Don't diff `output.nwb` between runs — the processor stamps it with a fresh
`uuid4` each time, so two runs of identical code produce different files.

Compare this jar's stdout instead. It is deterministic and it is the whole output
of this repo:

```
java -jar target/mef2edf-0.0.1-SNAPSHOT-jar-with-dependencies.jar "$IN" 2>/dev/null | shasum -a 256
```

Build the baseline commit in a `git worktree` and hash it the same way.

## References

- MEF / RED compression: the decoder in `edu.upenn.cis.thirdparty.RED`
- NWB: https://www.nwb.org/
- EDF spec (historical — this repo no longer writes EDF):
  https://www.edfplus.info/specs/edf.html

Useful on macOS: [Hex Fiend](https://hexfiend.com/) for eyeballing the binary
stream.
