/*
 * Copyright (c) 2014. Real Time Genomics Limited.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.rtg.reader;

import java.io.File;
import java.util.List;

import com.rtg.sam.SamBamConstants;
import com.rtg.sam.SamFilter;
import com.rtg.sam.SamUtils;
import com.rtg.util.diagnostic.NoTalkbackSlimException;

import htsjdk.samtools.SAMRecord;

/**
 * Allows formatting a CGI BAM file to SDF.
 */
public final class CgSamBamSequenceDataSource extends MappedSamBamSequenceDataSource {

  /**
   * Construct a pre-mapped SAM or BAM sequence data source from list of SAM or BAM files
   * @param files list of the SAM or BAM file to use as a sequence data source
   * @param flattenPaired if <code>paired</code> is false then this will load both arms into a single SDF
   * @param filter this filter will be applied to the sam records
   * @return SamBamSequenceDataSource the sequence data source for the inputs
   */
  public static CgSamBamSequenceDataSource fromInputFiles(List<File> files, boolean flattenPaired, SamFilter filter) {
    return new CgSamBamSequenceDataSource(new FileStreamIterator(files, null), flattenPaired, filter);
  }

  private CgSamBamSequenceDataSource(FileStreamIterator inputs, boolean flattenPaired, SamFilter filter) {
    super(inputs, true, flattenPaired, filter);
  }

  @Override
  protected SamSequence makeSamSequence(SAMRecord rec) {
    final int projectedSplit = rec.getAlignmentStart() * ((rec.getFlags() & SamBamConstants.SAM_MATE_IS_REVERSE) != 0 ? 1 : -1);
    final byte flags = SamSequence.getFlags(rec);
    final String expandedRead;
    final byte[] expandedQual;
    if (rec.getReadUnmappedFlag()) {
      expandedRead = new String(rec.getReadBases());
      expandedQual = rec.getBaseQualities();
    } else {
      final String gc = rec.getStringAttribute(SamUtils.ATTRIBUTE_CG_RAW_READ_INSTRUCTIONS);
      if (gc == null) {
        throw new NoTalkbackSlimException("SAM Record does not contain CGI read reconstruction attribute: " + rec.getSAMString());
      }
      final String gq = SamUtils.allowEmpty(rec.getStringAttribute(SamUtils.ATTRIBUTE_CG_OVERLAP_QUALITY));
      final String gs = SamUtils.allowEmpty(rec.getStringAttribute(SamUtils.ATTRIBUTE_CG_OVERLAP_BASES));
      final boolean legacyLegacy = gq.length() == gs.length() / 2;
      if (!legacyLegacy && gq.length() != gs.length()) {
        throw new NoTalkbackSlimException("Unexpected length of CG quality information: " + rec.getSAMString());
      }
      expandedRead = unrollLegacyRead(new String(rec.getReadBases()), gs, gc);
      if (expandedRead == null) {
        throw new NoTalkbackSlimException("Could not reconstruct read bases for record: " + rec.getSAMString());
      }
      final String samQualities = new String(FastaUtils.rawToAsciiQuality(rec.getBaseQualities()));
      final boolean hasQuality = samQualities.length() != 0;
      if (!hasQuality) {
        expandedQual = null;
      } else if (legacyLegacy) {
        expandedQual = FastaUtils.asciiToRawQuality(unrollLegacyLegacyQualities(samQualities, gq, gc));
      } else {
        expandedQual = FastaUtils.asciiToRawQuality(unrollLegacyRead(samQualities, gq, gc));
      }
    }

    final byte[] readBytes = CgUtils.unPad(expandedRead.getBytes(), !rec.getReadNegativeStrandFlag());
    final byte[] readQual = CgUtils.unPad(expandedQual, !rec.getReadNegativeStrandFlag());

    return new SamSequence(rec.getReadName(), readBytes, readQual, flags, projectedSplit);
  }

  /**
   * Extract an integer from the start of a string.
   * @param s string containing the integer.
   * @param end last position of integer (0 based exclusive).
   * @return the integer at the start of the string
   */
  private static int stringToInt(final String s, final int start, final int end) {
    int t = 0;
    for (int k = start; k < end; k++) {
      t *= 10;
      t += s.charAt(k) - '0';
    }
    return t;
  }

  private static int nextCigarPos(String cigar, int start) {
    final int end = cigar.length();
    for (int k = start; k < end; k++) {
      switch (cigar.charAt(k)) {
        case 'S':
        case 'G':
          return k;
        default:
      }
    }
    return -1;
  }

  /**
   * Reconstructs a CG read from the flattened, plus the extended attributes
   * @param samRead the read bases
   * @param gs the additional bases from the overlap region
   * @param gc the SAM attribute specifying how to reconstruct the read
   * @return the unrolled read bases
   */
  public static String unrollLegacyRead(final String samRead, final String gs, final String gc) {
    final int overlap = gs.length();
    if (overlap == 0) {
      return samRead;
    } else {
      if (gc == null) {
        return null;
      }
      final StringBuilder sbRead = new StringBuilder();
      int lastCigarPos = 0;
      int readPos = 0;
      int attPos = 0;
      while (true) {
        final int cigarPos = nextCigarPos(gc, lastCigarPos);
        if (cigarPos == -1) {
          break;
        }
        final int cigarLen = stringToInt(gc, lastCigarPos, cigarPos);
        if (cigarLen == 0) {
          return null;
        }
        if (gc.charAt(cigarPos) == 'S') {
          sbRead.append(samRead.substring(readPos, readPos + cigarLen));
          lastCigarPos = cigarPos + 1;
          readPos = readPos + cigarLen;
        } else {
          final int consumed = cigarLen * 2;
          if (attPos + consumed > gs.length()) {
            return null;
          }
          sbRead.append(gs.substring(attPos, attPos + consumed));
          attPos = attPos + consumed;
          lastCigarPos = cigarPos + 1;
          readPos += cigarLen;
        }
      }
      if (readPos != samRead.length() || lastCigarPos != gc.length() || attPos != gs.length()) {
        return null;
      }
      return sbRead.toString();
    }
  }

  /**
   * Reconstructs CG read qualities from the flattened, plus the extended attributes. This is the
   * legacy (incorrect) interpretation, which interpreted the GQ attribute as having one set of overlap
   * qualities and obtained the other from the flattened representation.
   * @param samQualities the read qualities
   * @param gq the additional bases from the overlap region
   * @param gc the SAM attribute specifying how to reconstruct the read
   * @return the unrolled read bases
   */
  public static String unrollLegacyLegacyQualities(final String samQualities, final String gq, final String gc) {
    final int overlap = gq.length();
    final int gslength = overlap * 2;
    if (overlap == 0) {
      return samQualities;
    } else {
      if (gc == null) {
        return null;
      }
      final StringBuilder sbQual = new StringBuilder();
      int lastCigarPos = 0;
      int readPos = 0;
      int attPos = 0;
      int att2Pos = 0;
      while (true) {
        final int cigarPos = nextCigarPos(gc, lastCigarPos);
        if (cigarPos == -1) {
          break;
        }
        final int cigarLen = stringToInt(gc, lastCigarPos, cigarPos);
        if (cigarLen == 0) {
          return null;
        }
        if (gc.charAt(cigarPos) == 'S') {
          sbQual.append(samQualities.substring(readPos, readPos + cigarLen));
          lastCigarPos = cigarPos + 1;
          readPos = readPos + cigarLen;
        } else {
          final int consumed = cigarLen * 2;
          if (attPos + consumed > gslength) {
            return null;
          }
          sbQual.append(samQualities.substring(readPos, readPos + cigarLen));
          sbQual.append(gq.substring(att2Pos, att2Pos + cigarLen));
          att2Pos = att2Pos + cigarLen;
          attPos = attPos + consumed;
          lastCigarPos = cigarPos + 1;
          readPos += cigarLen;
        }
      }
      if (readPos != samQualities.length() || lastCigarPos != gc.length() || attPos != gslength) {
        return null;
      }
      return sbQual.toString();
    }
  }

}
