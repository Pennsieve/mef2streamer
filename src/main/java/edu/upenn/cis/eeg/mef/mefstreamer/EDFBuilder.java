package edu.upenn.cis.eeg.mef.mefstreamer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import edu.upenn.cis.db.mefview.services.TimeSeriesPage;

public class EDFBuilder {

    // Frame types
    private static final int CHANNEL_META  = 1;
    private static final int SEGMENT_START = 2;
    private static final int SAMPLES_INT32 = 3;
    private static final int SEGMENT_END   = 4;
    private static final int END           = 5;

    private final File[] files;
    private final String directoryPath;
    private final String subjectid;
    private final int numsignals;

    public EDFBuilder(File[] mefFiles, String directoryPath, String subjectid, int numsignals) {
        this.files = mefFiles;
        this.directoryPath = directoryPath;
        this.subjectid = subjectid;
        this.numsignals = numsignals;

        System.err.println("JAVA: EDFBuilder init with " + (files == null ? 0 : files.length) + " files");
    }

    /** Little JSON string escaper (Java 8–11 friendly). */
    static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        int cu = c;
                        for (int sh = 12; sh >= 0; sh -= 4) {
                            int v = (cu >> sh) & 0xF;
                            sb.append((char)(v < 10 ? '0' + v : 'a' + (v - 10)));
                        }
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Builds the CHANNEL_META payload.
     *
     * The sample frames carry raw A/D counts, so "unit" is reported as counts.
     * voltage_conversion_factor is the microvolts-per-count scale from the MEF
     * header: consumers must multiply samples by it to obtain microvolts.
     * Without it the counts are not interpretable as a physical measurement.
     */
    static String channelMetaJson(String channelName, String typeStr, String description,
                                  double lowCutHz, double highCutHz, double rateHz,
                                  double voltageConversionFactor,
                                  long reportedStartUs, long reportedEndUs) {
        return "{"
                + "\"name\":" + json(channelName) + ","
                + "\"type\":" + json(typeStr) + ","
                + "\"description\":" + json(description) + ","
                + "\"unit\":" + json("counts") + ","
                + "\"voltage_conversion_factor\":" + voltageConversionFactor + ","
                + "\"low_cut_hz\":" + lowCutHz + ","
                + "\"high_cut_hz\":" + highCutHz + ","
                + "\"rate_hz\":" + rateHz + ","
                + "\"reported_start_us\":" + reportedStartUs + ","
                + "\"reported_end_us\":" + reportedEndUs
                + "}";
    }

    /** Length-prefixed frame writer to stdout: [type:1][len:4 LE][payload]. */
    static final class Framer {
        private final BufferedOutputStream out;
        private final byte[] header = new byte[5];

        Framer(OutputStream stdout) {
            // modest buffer for low latency; we also flush every frame while debugging
            this.out = new BufferedOutputStream(stdout, 64 * 1024);
        }

        void send(int type, byte[] payload) throws IOException {
            send(type, payload, 0, payload.length);
        }

        void send(int type, byte[] payload, int off, int len) throws IOException {
            // header: type (1) + length (4 LE)
            header[0] = (byte) type;
            header[1] = (byte) (len       & 0xFF);
            header[2] = (byte) ((len >> 8)  & 0xFF);
            header[3] = (byte) ((len >> 16) & 0xFF);
            header[4] = (byte) ((len >> 24) & 0xFF);
            out.write(header, 0, 5);
            if (len > 0) out.write(payload, off, len);
            out.flush(); // IMPORTANT: force delivery so Python sees frames immediately
        }

        void send(int type, ByteBuffer buf) throws IOException {
            buf.flip();
            byte[] a = new byte[buf.remaining()];
            buf.get(a);
            send(type, a, 0, a.length);
        }

        void flush() throws IOException { out.flush(); }
    }

    public void build() throws IOException {
        final Framer fr = new Framer(System.out);

        if (files == null || files.length == 0) {
            System.err.println("JAVA: No input files.");
            fr.send(END, new byte[0]);
            return;
        }

        for (File inputFile : files) {
            if (inputFile == null) continue;
            System.err.println("JAVA: Opening MEF " + inputFile.getAbsolutePath());

            final String fileName = inputFile.getName();
            final String baseName = (fileName.lastIndexOf('.') > 0)
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            try (RandomAccessFile raf = new RandomAccessFile(inputFile.getAbsolutePath(), "r")) {
                MEFStreamer streamer = new MEFStreamer(raf);
                MefHeader2 header = streamer.getMEFHeader();

                final double rateHz = header.getSamplingFrequency();
                final double periodUsExact = 1_000_000.0 / rateHz;
                final long tolUs = Math.max(2L, (long) Math.ceil(periodUsExact / 4.0)); // conservative

                // Simple type by filename (fallback)
                final String channelName = baseName;
                final String lower = channelName.toLowerCase(Locale.ROOT);
                final String typeStr;
                final String description;
                if (lower.contains("grid")) { typeStr = "ECOG"; description = "Electrocorticography"; }
                else if (lower.contains("seeg") || lower.contains("depth")) { typeStr = "SEEG"; description = "Stereoelectroencephalography"; }
                else if (lower.contains("eeg")) { typeStr = "EEG"; description = "Electroencephalography"; }
                else if (lower.contains("eog")) { typeStr = "EOG"; description = "Electrooculography"; }
                else if (lower.contains("ecg") || lower.contains("ekg")) { typeStr = "ECG"; description = "Electrocardiography"; }
                else if (lower.contains("emg")) { typeStr = "EMG"; description = "Electromyography"; }
                else { typeStr = "Unknown"; description = "Unknown signal type"; }

                final double lowCut = header.getLowFrequencyFilterSetting();
                final double highCut = header.getHighFrequencyFilterSetting();
                final long reportedStartUs = header.getRecordingStartTime();
                final long reportedEndUs = header.getRecordingEndTime();
                final long totalBlocks = streamer.getNumberOfBlocks();

                System.err.printf("JAVA: Channel=%s rate=%.3fHz blocks=%d tolUs=%d%n",
                        channelName, rateHz, totalBlocks, tolUs);

                // ---- META ----
                final double voltageConversionFactor = header.getVoltageConversionFactor();
                String metaJson = channelMetaJson(channelName, typeStr, description, lowCut, highCut,
                        rateHz, voltageConversionFactor, reportedStartUs, reportedEndUs);
                System.err.println("JAVA: Sending CHANNEL_META for " + channelName);
                fr.send(CHANNEL_META, metaJson.getBytes(StandardCharsets.UTF_8));
                System.err.println("JAVA: Sent CHANNEL_META");

                // ---- Segment state ----
                boolean segmentOpen = false;
                long segStartUs = 0L;
                long segSamples = 0L;
                Long observedStartUs = null;
                long observedEndUs = Long.MIN_VALUE;

                // Page buffer for int32 payload
                byte[] pageBytes = new byte[0];
                ByteBuffer pageBuf = ByteBuffer.wrap(new byte[0]).order(ByteOrder.LITTLE_ENDIAN);

                long bytesSinceLog = 0L;
                final long LOG_EVERY_BYTES = 64L * 1024 * 1024; // 64 MB

                // ---- Stream pages ----
                int blockIdx = 0;
                for (TimeSeriesPage page : streamer.getNextBlocks((int) totalBlocks)) {
                    blockIdx++;
                    if (page == null) continue;
                    final long pageStartUs = page.timeStart;
                    final int n = (page.values == null ? 0 : page.values.length);
                    if (n <= 0) continue;

                    if (observedStartUs == null) observedStartUs = pageStartUs;

                    if (!segmentOpen) {
                        segStartUs = pageStartUs;
                        segSamples = 0L;
                        ByteBuffer segStart = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                        segStart.putLong(segStartUs).putDouble(rateHz);
                        System.err.printf("JAVA: SEGMENT_START t0=%d (block %d)%n", segStartUs, blockIdx);
                        fr.send(SEGMENT_START, segStart);
                        segmentOpen = true;
                    } else {
                        final long expectedNextStartUs = segStartUs + Math.round(segSamples * periodUsExact);
                        final long delta = Math.abs(pageStartUs - expectedNextStartUs);
                        if (delta > tolUs) {
                            final long segLastUs = segStartUs + Math.round((segSamples - 1) * periodUsExact);
                            ByteBuffer segEnd = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                            segEnd.putLong(segLastUs).putLong(segSamples);
                            System.err.printf("JAVA: GAP delta=%d us > tol=%d -> SEGMENT_END end_us=%d n=%d%n",
                                    delta, tolUs, segLastUs, segSamples);
                            fr.send(SEGMENT_END, segEnd);

                            // Start new segment
                            segStartUs = pageStartUs;
                            segSamples = 0L;
                            ByteBuffer segStart2 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                            segStart2.putLong(segStartUs).putDouble(rateHz);
                            System.err.printf("JAVA: SEGMENT_START t0=%d (block %d)%n", segStartUs, blockIdx);
                            fr.send(SEGMENT_START, segStart2);
                        }
                    }

                    // SAMPLES_INT32 for this page
                    final int needed = n * 4;
                    if (pageBytes.length < needed) {
                        pageBytes = new byte[Math.max(needed, 1 << 18)]; // >=256KB
                        pageBuf = ByteBuffer.wrap(pageBytes).order(ByteOrder.LITTLE_ENDIAN);
                    }
                    pageBuf.clear();
                    for (int v : page.values) pageBuf.putInt(v);
                    // send only used bytes
                    fr.send(SAMPLES_INT32, pageBytes, 0, needed);

                    segSamples += n;
                    bytesSinceLog += needed;

                    if (bytesSinceLog >= LOG_EVERY_BYTES) {
                        System.err.printf("JAVA: Wrote ~%d MB of samples in current segment%n", (bytesSinceLog / (1024 * 1024)));
                        bytesSinceLog = 0;
                    }

                    final long pageLastUs = pageStartUs + Math.round((n - 1) * periodUsExact);
                    if (pageLastUs > observedEndUs) observedEndUs = pageLastUs;
                }

                if (segmentOpen) {
                    final long segLastUs = segStartUs + Math.round((segSamples - 1) * periodUsExact);
                    ByteBuffer segEnd = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                    segEnd.putLong(segLastUs).putLong(segSamples);
                    System.err.printf("JAVA: SEGMENT_END end_us=%d n=%d%n", segLastUs, segSamples);
                    fr.send(SEGMENT_END, segEnd);
                }

                System.err.println("JAVA: Channel done: " + channelName);
            } catch (IOException ioe) {
                System.err.println("JAVA: ERROR reading " + inputFile + ": " + ioe);
                throw ioe;
            }
        }

        System.err.println("JAVA: All channels done; sending END");
        fr.send(END, new byte[0]);
        fr.flush();
    }
}
