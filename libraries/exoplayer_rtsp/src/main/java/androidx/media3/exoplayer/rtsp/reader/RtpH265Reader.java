/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.rtsp.reader;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.castNonNull;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.rtsp.RtpPacket;
import androidx.media3.exoplayer.rtsp.RtpPayloadFormat;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.NalUnitUtil;
import androidx.media3.extractor.TrackOutput;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Parses an H265 byte stream carried on RTP packets, and extracts H265 Access Units. Refer to
 * RFC7798 for more details.
 */
/* package */ final class RtpH265Reader implements RtpPayloadReader {

  private static final String TAG = "RtpH265Reader";

  private static final long MEDIA_CLOCK_FREQUENCY = 90_000;

  /** Offset of payload data within a FU payload. */
  private static final int FU_PAYLOAD_OFFSET = 3;

  /**
   * Aggregation Packet.
   *
   * @see <a
   *     href="https://datatracker.ietf.org/doc/html/draft-ietf-payload-rtp-h265-15#section-4.4.2">
   *     RFC7798 Section 4.4.2</a>
   */
  private static final int RTP_PACKET_TYPE_AP = 48;
  /**
   * Fragmentation Unit.
   *
   * @see <a
   *     href="https://datatracker.ietf.org/doc/html/draft-ietf-payload-rtp-h265-15#section-4.4.3">
   *     RFC7798 Section 4.4.3</a>
   */
  private static final int RTP_PACKET_TYPE_FU = 49;

  /** IDR NAL unit type. */
  private static final int NAL_IDR_W_RADL = 19;
  private static final int NAL_IDR_N_LP = 20;

  /** Scratch for Fragmentation Unit RTP packets. */
  private final ParsableByteArray fuScratchBuffer;

  private final ParsableByteArray nalStartCodeArray =
      new ParsableByteArray(NalUnitUtil.NAL_START_CODE);

  private final RtpPayloadFormat payloadFormat;

  private @MonotonicNonNull TrackOutput trackOutput;
  @C.BufferFlags private int bufferFlags;
  private long firstReceivedTimestamp;
  private int previousSequenceNumber;
  /** The combined size of a sample that is fragmented into multiple RTP packets. */
  private int fragmentedSampleSizeBytes;
  private long startTimeOffsetUs;

  /** Creates an instance. */
  public RtpH265Reader(RtpPayloadFormat payloadFormat) {
    this.payloadFormat = payloadFormat;
    fuScratchBuffer = new ParsableByteArray();
    firstReceivedTimestamp = C.TIME_UNSET;
    previousSequenceNumber = C.INDEX_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    trackOutput = extractorOutput.track(trackId, C.TRACK_TYPE_VIDEO);
    castNonNull(trackOutput).format(payloadFormat.format);
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {}

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker)
      throws ParserException {
    int payloadType;
    try {
      // RFC7798 Section 1.1.4. NAL Unit Header
      payloadType = (data.getData()[0] >> 1) & 0x3F; // Type - Bits 1 to 6, inclusive.
    } catch (IndexOutOfBoundsException e) {
      throw ParserException.createForMalformedManifest(/* message= */ null, e);
    }

    checkStateNotNull(trackOutput);
    if (payloadType >= 0 && payloadType < RTP_PACKET_TYPE_AP) {
      processSingleNalUnitPacket(data);
    } else if (payloadType == RTP_PACKET_TYPE_AP) {
      processAggregationPacket(data);
    } else if (payloadType == RTP_PACKET_TYPE_FU) {
      processFragmentationUnitPacket(data, sequenceNumber);
    } else {
      throw ParserException.createForMalformedManifest(
          String.format("RTP H265 payload type [%d] not supported.", payloadType),
          /* cause= */ null);
    }

    if (rtpMarker) {
      if (firstReceivedTimestamp == C.TIME_UNSET) {
        firstReceivedTimestamp = timestamp;
      }

      long timeUs = toSampleUs(startTimeOffsetUs, timestamp, firstReceivedTimestamp);
      trackOutput.sampleMetadata(
          timeUs,
          bufferFlags,
          fragmentedSampleSizeBytes,
          /* offset= */ 0,
          /* encryptionData= */ null);
      fragmentedSampleSizeBytes = 0;
    }

    previousSequenceNumber = sequenceNumber;
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    firstReceivedTimestamp = nextRtpTimestamp;
    fragmentedSampleSizeBytes = 0;
    startTimeOffsetUs = timeUs;
  }

  // Internal methods.

  /**
   * Processes Single NAL Unit packet (RFC7798 Section 4.4.1).
   *
   * <p>Outputs the single NAL Unit (with start code prepended) to {@link #trackOutput}. Sets {@link
   * #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processSingleNalUnitPacket(ParsableByteArray data) {
    // Example of a Single Nal Unit packet
    //     0                   1                   2                   3
    //     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |           PayloadHdr          |      DONL (conditional)       |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                                                               |
    //    |                  NAL unit payload data                        |
    //    |                                                               |
    //    |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                               :...OPTIONAL RTP padding        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    int numBytesInData = data.bytesLeft();
    fragmentedSampleSizeBytes += writeStartCode();
    trackOutput.sampleData(data, numBytesInData);
    fragmentedSampleSizeBytes += numBytesInData;

    int nalHeaderType = (data.getData()[0] >> 1) & 0x3F;
    bufferFlags = getBufferFlagsFromNalType(nalHeaderType);
  }

  /**
   * Processes an AP packet (RFC7798 Section 4.4.2).
   *
   * <p>Outputs the received aggregation units (with start code prepended) to {@link #trackOutput}.
   * Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processAggregationPacket(ParsableByteArray data) throws ParserException {
    throw ParserException.createForMalformedManifest(
        "need to implement processAggregationPacket",
        /* cause= */ null);
  }

  /**
   * Processes Fragmentation Unit packet (RFC7798 Section 4.4.3).
   *
   * <p>This method will be invoked multiple times to receive a single frame that is broken down
   * into a series of fragmentation units in multiple RTP packets.
   *
   * <p>Outputs the received fragmentation units (with start code prepended) to {@link
   * #trackOutput}. Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processFragmentationUnitPacket(ParsableByteArray data, int packetSequenceNumber) {
    //  Example of a FU packet
    //    0                   1                   2                   3
    //    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   |    PayloadHdr (Type=49)       |   FU header   | DONL (cond)   |
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-|
    //   | DONL (cond)   |                                               |
    //   |-+-+-+-+-+-+-+-+                                               |
    //   |                         FU payload                            |
    //   |                                                               |
    //   |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   |                               :...OPTIONAL RTP padding        |
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   FU header
    //   +---------------+
    //   |0|1|2|3|4|5|6|7|
    //   +-+-+-+-+-+-+-+-+
    //   |S|E|  FuType   |
    //   +---------------+
    //   Structure of HEVC NAL unit header
    //   +---------------+---------------+
    //   |0|1|2|3|4|5|6|7|0|1|2|3|4|5|6|7|
    //   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //   |F|   Type    |  LayerId  | TID |
    //   +-------------+-----------------+

    int tid =
        (data.getData()[1] & 0x7); // last 3 bits in byte 1 of payload header, RFC7798 Section 1.1.4
    int fuHeader = data.getData()[2];
    int nalUnitType = fuHeader & 0x3F;
    byte nalHeader[] = new byte[2];

    nalHeader[0] = (byte) (nalUnitType << 1); // RFC7798 Section 1.1.4
    // layerId must be zero according to RFC7798 Section 1.1.4, so copying the tid only
    nalHeader[1] = (byte) tid;
    boolean isFirstFuPacket = (fuHeader & 0x80) > 0;
    boolean isLastFuPacket = (fuHeader & 0x40) > 0;

    if (isFirstFuPacket) {
      // Prepends starter code.
      fragmentedSampleSizeBytes += writeStartCode();

      // Overwrite a few bytes in Rtp buffer to get HEVC NAL unit
      // Rtp Byte 0  -> Ignore
      // Rtp Byte 1  -> Byte 0 of HEVC NAL header
      // Rtp Byte 2  -> Byte 1 of HEVC NAL header
      // Rtp Payload -> HEVC NAL bytes, so leave them unchanged
      // Set data position from byte 1 as byte 0 was ignored
      data.getData()[1] = (byte) nalHeader[0];
      data.getData()[2] = (byte) nalHeader[1];
      fuScratchBuffer.reset(data.getData());
      fuScratchBuffer.setPosition(1);
    } else {
      // Check that this packet is in the sequence of the previous packet.
      int expectedSequenceNumber = (previousSequenceNumber + 1) % RtpPacket.MAX_SEQUENCE_NUMBER;
      if (packetSequenceNumber != expectedSequenceNumber) {
        Log.w(
            TAG,
            Util.formatInvariant(
                "Received RTP packet with unexpected sequence number. Expected: %d; received: %d."
                    + " Dropping packet.",
                expectedSequenceNumber, packetSequenceNumber));
        return;
      }

      // Setting position to ignore payload and FU header.
      fuScratchBuffer.reset(data.getData());
      fuScratchBuffer.setPosition(FU_PAYLOAD_OFFSET);
    }

    int fragmentSize = fuScratchBuffer.bytesLeft();
    trackOutput.sampleData(fuScratchBuffer, fragmentSize);
    fragmentedSampleSizeBytes += fragmentSize;

    if (isLastFuPacket) {
      bufferFlags = getBufferFlagsFromNalType(nalUnitType);
    }
  }

  private int writeStartCode() {
    nalStartCodeArray.setPosition(/* position= */ 0);
    int bytesWritten = nalStartCodeArray.bytesLeft();
    checkNotNull(trackOutput).sampleData(nalStartCodeArray, bytesWritten);
    return bytesWritten;
  }

  private static long toSampleUs(
      long startTimeOffsetUs, long rtpTimestamp, long firstReceivedRtpTimestamp) {
    return startTimeOffsetUs
        + Util.scaleLargeTimestamp(
            (rtpTimestamp - firstReceivedRtpTimestamp),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
  }

  @C.BufferFlags
  private static int getBufferFlagsFromNalType(int nalType) {
    return (nalType == NAL_IDR_W_RADL || nalType == NAL_IDR_N_LP) ? C.BUFFER_FLAG_KEY_FRAME : 0;
  }
}
