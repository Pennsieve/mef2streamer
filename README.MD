# MEF streamer

Reads MEF2 files (time series EEG/iEEG data) and streams the decoded samples on
stdout as length-prefixed binary frames. This is how iEEG.org recordings get
into Pennsieve.

Despite the repo name, this no longer writes EDF files. It is consumed by
[processor-mef-timeseries](https://github.com/Pennsieve/processor-mef-timeseries),
which builds this repo into `mefstreamer.jar`, runs it as a subprocess, and
turns the frames into NWB.

## Running

```
java -jar mef2edf-0.0.1-SNAPSHOT-jar-with-dependencies.jar <input-dir>
```

`<input-dir>` is a folder of `.mef` files. Each file is one channel; the channel
name is the filename without its extension. The directory name is taken as the
subject ID.

Build with `mvn package`. The jar lands in `target/`.

## Selecting channels

Set `MEF_CHANNELS` to convert only some of the channels in the directory:

```
MEF_CHANNELS=A1,B2 java -jar mefstreamer.jar /data/input
```

- Comma-separated. Whitespace around names is ignored.
- Matched case-insensitively against the `.mef` filename minus its extension.
- Unset, empty, or all-blank means convert every channel.
- **If any requested channel has no matching file, nothing is converted.** The
  run prints a warning naming the missing channels and the available ones, then
  exits 1. Converting the subset that happened to match would look like a
  successful run that is quietly missing a channel.

## Output protocol

stdout carries only frames. Every diagnostic goes to stderr.

Each frame is `[type:1][length:4 LE][payload]`:

| Type | Name | Payload |
|---|---|---|
| 1 | `CHANNEL_META` | UTF-8 JSON describing the channel |
| 2 | `SEGMENT_START` | `int64` start time (µs) + `float64` rate (Hz) |
| 3 | `SAMPLES_INT32` | raw A/D counts, little-endian `int32` |
| 4 | `SEGMENT_END` | `int64` last sample time (µs) + `int64` sample count |
| 5 | `END` | empty; sent once after the last channel |

Samples are **raw A/D counts, not microvolts**. `CHANNEL_META` carries
`voltage_conversion_factor` (microvolts per count, from the MEF header);
consumers must multiply by it.

A channel is split into segments wherever the recording has a gap. A gap is a
block whose start time differs from the expected next sample time by more than a
quarter of one sampling period.

## EDF spec

The original EDF work was based on https://www.edfplus.info/specs/edf.html

## Useful tools (Mac OS)

- Hex Fiend for viewing binary files https://hexfiend.com/
- EDF browser https://formulae.brew.sh/cask/edfbrowser
