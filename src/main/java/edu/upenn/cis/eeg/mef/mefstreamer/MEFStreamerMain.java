package edu.upenn.cis.eeg.mef.mefstreamer;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Main class to run the MEFStreamer application.
 *
 * stdout carries the binary frame stream and nothing else -- every message here
 * goes to stderr. A stray println on stdout lands in the middle of the protocol
 * and the reader on the other side desynchronises.
 */
public class MEFStreamerMain {

    public static void main(String[] args) {
        // Check for file path argument
        if (args.length < 1) {
            System.err.println("Usage: java <SubjPathdir>");
            System.exit(1);
        }

        String directoryPath = args[0];
        File directory = new File(directoryPath);
        String subjectid = directory.getName(); // Subject ID corresponds to the name of the directory mef files are within

        // Check if the provided path is a directory
        if (!directory.isDirectory()) {
            System.err.println("The provided path is not a valid directory.");
            System.exit(1);
        }

        // List all .mef files in the directory
        File[] mefFiles = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".mef"));

        if (mefFiles == null || mefFiles.length == 0) {
            System.err.println("No .mef files found in the specified directory.");
            System.exit(1);
        }

        // 1. Narrow to the channels named in MEF_CHANNELS, if it is set. A channel
        //    name that matches nothing is fatal: we stop before writing a single
        //    frame, so a typo can't pass downstream as a successful conversion
        //    that is quietly missing a channel.
        List<String> requestedChannels = ChannelFilter.parse(System.getenv(ChannelFilter.ENV_VAR));
        if (!requestedChannels.isEmpty()) {
            int found = mefFiles.length;
            try {
                mefFiles = ChannelFilter.select(mefFiles, requestedChannels);
            } catch (ChannelFilter.UnknownChannelException e) {
                System.err.println("JAVA: WARNING: " + ChannelFilter.ENV_VAR + ": " + e.getMessage());
                System.err.println("JAVA: Stopping without converting anything.");
                System.exit(1);
            }
            System.err.printf("JAVA: %s limits this run to %d of %d channel(s)%n",
                    ChannelFilter.ENV_VAR, mefFiles.length, found);
        }

        // Number of channels (aka the number of mef files we are converting)
        int numsignals = mefFiles.length;

        // 2. Stream the selected channels to stdout.
        FrameStreamer streamer = new FrameStreamer(mefFiles, directoryPath, subjectid, numsignals);

        try {
            streamer.build();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.err.println("Complete!");
        Instant instant = Instant.now();
        System.err.println("End Time: " + instant);
    }

}
