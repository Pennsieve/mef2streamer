/*
 * Copyright 2014 Trustees of the University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.upenn.cis.eeg.mef.mefstreamer;

import static com.google.common.collect.Lists.newArrayList;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.util.List;

import com.google.common.primitives.Ints;

import edu.upenn.cis.db.mefview.services.TimeSeriesPage;
import edu.upenn.cis.thirdparty.RED;
import edu.upenn.cis.thirdparty.RED.HeaderOfRED;
import edu.upenn.cis.thirdparty.RED.PageAndBytesRead;

/**
 * For reading forward through a mef file. Once a client passes in a
 * {@code RandomAccessFile} they should not read it again until they are
 * finished with {@code MEFStreamer}. One option is to let go of your reference
 * to the {@code RandomAccessFile} - but make sure to call {{@link #close()} if
 * you go that route.
 * 
 * @author Sam Donnelly
 
 */

public final class MEFStreamer implements Closeable {

	private int blocksRead = 0;
	private final MefHeader2 mefHeader;
	private final RED red = new RED();
	private final DataInputStream mefInputStream;
	private final boolean decompressData;
	/**
	 * Streams through mef data. Useful if you don't need random access into the
	 * file.
	 * 
	 * {@code mefInput}'s file pointer will be moved. It is imperative that
	 * clients do not move the file pointer while use a {@code MEFStreamer}.
	 * 
	 * @param mefInput the mef file to read
	 * 
	 * @throws IOException if thrown will reading the mef file
	 */
	public MEFStreamer(RandomAccessFile mefInput) throws IOException {
		this(mefInput, true);
	}

	/**
	 * Streams through mef data. Useful if you don't need random access into the
	 * file.
	 * 
	 * {@code mefInput}'s file pointer will be moved. It is imperative that
	 * clients do not move the file pointer while use a {@code MEFStreamer}.
	 * 
	 * @param mefInput the mef file
	 * @param decompressData if true then decompress the data, otherwise the
	 *            {@code TimeSeriesPage}s will have no data, only start and end
	 *            times
	 * 
	 * @throws IOException if thrown will reading the mef file
	 */
	public MEFStreamer(
			RandomAccessFile mefInput,
			boolean decompressData) throws IOException {
		this(Channels.newInputStream(mefInput.getChannel()), decompressData);
	}
	
	/**
	 * Streams through mef data. Useful if you don't need random access into the
	 * file.
	 * 
	 * {@code mefInputStream}'s pointer will be moved. It is imperative that
	 * clients do not move the pointer while use a {@code MEFStreamer}.
	 * 
	 * @param mefInputStream the mef file stream
	 * @param decompressData if true then decompress the data, otherwise the
	 *            {@code TimeSeriesPage}s will have no data, only start and end
	 *            times
	 * 
	 * @throws IOException if thrown will reading the mef file
	 */
	public MEFStreamer(
			InputStream mefInputStream,
			boolean decompressData) throws IOException {
		this.mefInputStream = new DataInputStream(mefInputStream);
		byte[] headerBytes = new byte[1024];
		this.mefInputStream.readFully(headerBytes);
		this.mefHeader = new MefHeader2(headerBytes);

		// Reject anything that isn't MEF2 before decoding a single block. Nothing
		// downstream checks the version, and the header fields sit at fixed
		// offsets, so another format parses "successfully" into nonsense and
		// streams out as plausible-looking samples. Failing here is the only
		// place that mistake is still cheap to catch.
		final int major = mefHeader.getHeaderVersionMajor();
		if (major != MefHeader2.HEADER_MAJOR_VERSION) {
			throw new IOException(
					"Unsupported MEF version: header reports major version " + major
							+ "." + mefHeader.getHeaderVersionMinor()
							+ ", this reads MEF" + MefHeader2.HEADER_MAJOR_VERSION + " only");
		}

		this.decompressData = decompressData;
	}

	/**
	 * Closes the client supplied {@code RandomAccessFile}.
	 * 
	 * @throws IOException from calling close.
	 */
	@Override
	public void close() throws IOException {
		mefInputStream.close();
	}
	
	public MefHeader2 getMEFHeader() {
		return mefHeader;
	}

	/**
	 * Get the next {@code noBlocks} mef blocks or less from the file. Returns
	 * less than {@code noBlocks} if there are less left. Returns an empty list
	 * if there are no more blocks.
	 * 
	 * @param noBlocks the number of blocks you want to read
	 * 
	 * @return the decompressed blocks that were read
	 * 
	 * @throws IOException if thrown when reading the file
	 */
	public List<TimeSeriesPage> getNextBlocks(int noBlocks)
			throws IOException {
		List<TimeSeriesPage> pages = newArrayList();
		final double samplingPeriodMicros = getSamplingPeriodMicros();
		for (int i = 0; i < noBlocks; i++) {

			if (!moreBlocks()) {
				return pages;
			}

			byte[] redHeaderBytes = new byte[HeaderOfRED.BYTES];
			mefInputStream.readFully(redHeaderBytes);

			HeaderOfRED redHeader = new HeaderOfRED(redHeaderBytes, 0);

			int lengthOfBlockBytes =
					Ints.checkedCast(
							redHeader.compressed_bytes());

			byte[] restOfBlock = new byte[lengthOfBlockBytes];
			mefInputStream.readFully(restOfBlock);

			byte[] blockBytes =
					new byte[redHeaderBytes.length + restOfBlock.length];
			System.arraycopy(
					redHeaderBytes,
					0,
					blockBytes,
					0,
					redHeaderBytes.length);
			System.arraycopy(
					restOfBlock,
					0,
					blockBytes,
					redHeaderBytes.
					length,
					restOfBlock.length);

			PageAndBytesRead pageAndBytesRead =
					red.decodePage(
							blockBytes,
							0,
							samplingPeriodMicros,
							decompressData);
			pages.add(pageAndBytesRead.page);
			blocksRead++;
		}
		return pages;
	}

	/**
	 * Get the total number of blocks in the mef file.
	 * 
	 * @return the total number of blocks in the mef file
	 */
	public long getNumberOfBlocks() {
		return mefHeader.getNumberOfIndexEntries();
	}
	
	public double getSamplingPeriodMicros() {
		return 1E6 / mefHeader.getSamplingFrequency();
	}
	
	public long getRecordingStartTimeUutc() {
		return mefHeader.getRecordingStartTime();
	}

	/**
	 * Tells us if there are any blocks left.
	 * 
	 * @return true if there are blocks left, false otherwise
	 */
	public boolean moreBlocks() {
		if (blocksRead < getNumberOfBlocks()) {
			return true;
		}
		return false;
	}

}
