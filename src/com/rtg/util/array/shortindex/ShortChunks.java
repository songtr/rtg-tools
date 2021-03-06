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
package com.rtg.util.array.shortindex;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

import com.rtg.util.array.ExtensibleIndex;
import com.rtg.util.array.IndexType;
import com.rtg.util.integrity.Exam;

/**
 * Break array of shorts into chunks to fit within java convention that indices must
 * be ints.
 */
public final class ShortChunks extends ShortIndex implements ExtensibleIndex {

  private final int mChunkBits;

  private final int mChunkSize;

  private final int mChunkMask;

  private short[][] mArray;

  private long mTotalLength;

  /**
   * Constructs an index by splitting into array chunks.
   *
   * @param length of the index being created.
   */
  public ShortChunks(final long length) {
    this(length, CHUNK_BITS);
  }

  /**
   * Constructs an index by splitting into array chunks.
   * This version sets the size of the chunks - it should only be used for testing.
   * @param length of the index being created.
   * @param chunkBits number of bits used for an entry in a chunk.
   */
  public ShortChunks(final long length, final int chunkBits) {
    super(length);
    assert chunkBits > 0 && chunkBits <= 30;
    mChunkBits = chunkBits;
    mChunkSize = 1 << mChunkBits;
    mChunkMask = mChunkSize - 1;

    final long ch = (length + mChunkSize - 1) / mChunkSize;
    if (ch > Integer.MAX_VALUE) {
      //throw new RuntimeException("length requested too long length=" + length + " mChunkSize=" + mChunkSize);
      throw new RuntimeException();
    }
    final int chunks = (int) ch;
    mArray = new short[chunks][];
    long left = mLength;
    for (int i = 0; i < chunks; ++i) {
      final int assignedLength = left <= mChunkSize ? (int) left :  mChunkSize;
      assert assignedLength > 0;
      mArray[i] =  new short[assignedLength];
      left -= assignedLength;
    }
    assert left == 0;
    mTotalLength = mLength;
    assert integrity();
  }

  private ShortChunks(long length, int chunkBits, int chunkSize, int chunkMask, short[][] array, long totalLength) {
    super(length);
    mChunkBits = chunkBits;
    mChunkSize = chunkSize;
    mChunkMask = chunkMask;
    mArray = array;
    mTotalLength = totalLength;
  }

  @Override
  public short getShort(final long index) {
    final int chunk = (int) (index >> mChunkBits);
    final int offset = (int) (index & mChunkMask);
    return mArray[chunk][offset];
  }

  @Override
  public void swap(final long index1, final long index2) {
    final int chunk1 = (int) (index1 >> mChunkBits);
    final int offset1 = (int) (index1 & mChunkMask);

    final int chunk2 = (int) (index2 >> mChunkBits);
    final int offset2 = (int) (index2 & mChunkMask);
    final short temp = mArray[chunk1][offset1];
    mArray[chunk1][offset1] = mArray[chunk2][offset2];
    mArray[chunk2][offset2] = temp;
  }

  @Override
  public void setShort(final long index, final short value) {
    final int chunk = (int) (index >> mChunkBits);
    final int offset = (int) (index & mChunkMask);
    mArray[chunk][offset] = value;
  }

  /**
   * Get the chunk size. Should only be used for testing.
   *
   * @return the chunk size.
   */
  int chunkSize() {
    return mChunkSize;
  }

  /**
   * Allocate at least length entries.
   * @param length minimum number of entries to be allocated.
   * @return initial position of the start of the newly allocated entries.
   */
  @Override
  public long extendBy(final long length) {
    if (length < 0) {
      throw new IllegalArgumentException("length=" + length);
    }
    final long res = mLength;
    final long target = mLength + length;
    while (mTotalLength < target) {
      final long i = mTotalLength >>> mChunkBits;
    if (i >= mArray.length) {
      final long newSize = (mArray.length + 1) * 2;
      if (newSize > Integer.MAX_VALUE) {
        //I wonder what will be the earliest date it will be possible to get to here
        //throw new RuntimeException("Attempting to allocate too large a chunk array. newSize=" + newSize);
        throw new RuntimeException();
      }
      mArray = Arrays.copyOf(mArray, (int) newSize);
    }
    final int ii = (int) i;
    if (mArray[ii] == null) {
      final int size = (int) Math.min(mChunkSize, target);
      mArray[ii] = new short[size];
      mTotalLength += size;
    } else {
      //short subarray
      final short[] newSubArray;
      final int newLength;
      if (mTotalLength < mChunkSize) {
        //use standard 3 / 2 size increase to avoid giant increase in small array
        final long threeovertwo = mArray[ii].length * 3L / 2;
        final long atLeast = Math.max(threeovertwo, target);
        newLength = (int) Math.min(mChunkSize, atLeast); //chunk size is int -> result is int
        assert newLength == mChunkSize || newLength == atLeast;
      } else {
        newLength = mChunkSize;
      }
      newSubArray = new short[newLength];


      final short[] arr = mArray[ii];
      final int lenArr = arr.length;
      System.arraycopy(arr, 0, newSubArray, 0, lenArr);
      mTotalLength += newLength - lenArr;
      mArray[ii] = newSubArray;
    }
    }
    mLength = target;
    assert integrity();
    return res;
  }

  @Override
  public void trim(long length) {
    if (length > mLength) {
      throw new IllegalArgumentException("length=" + length);
    }
    final long newArraySize = (length + mChunkMask) >>> mChunkBits;
    if (newArraySize < mArray.length) {
      mArray = Arrays.copyOf(mArray, (int) newArraySize);
    }
    final int offset = (int) ((length - 1) & mChunkMask) + 1;
    if (offset < mChunkSize) {
      final int ns = (int) (newArraySize - 1);
      mArray[ns] = Arrays.copyOf(mArray[ns], offset);
    }
    mTotalLength = length;
    mLength = length;
  }

  @Override
  public long getSigned(final long offset) {
    return getShort(offset);
  }

  @Override
  public void setSigned(final long index, final long value) {
    //High order bits must be zero
    assert  ((value >= 0 ? value : -value) & HIGH_MASK) == 0L : value;
    setShort(index, (short) value);
  }

  /**
   * Get the array length. Should only be used for testing.
   *
   * @return the array length.
   */
  int arrayLength() {
    return mArray.length;
  }

  /**
   * Get the total length. Should only be used for testing.
   *
   * @return the total length.
   */
  long totalLength() {
    return mTotalLength;
  }

  @Override
  public boolean integrity() {
    super.integrity();
    Exam.assertTrue(mChunkBits > 0 && mChunkBits <= 31);
    Exam.assertTrue((mChunkSize & mChunkMask) == 0);
    Exam.assertTrue(mChunkSize > 0);
    Exam.assertTrue(mChunkMask > 0);
    Exam.assertTrue(mChunkMask + 1 == mChunkSize);
    if (mArray == null) { //close call has been made.
      return true;
    }
    Exam.assertTrue(mLength + ":" + mTotalLength, 0 <= mLength && mLength <= mTotalLength);
    final long il = mTotalLength >>> mChunkBits;
    final long ml = il << mChunkBits;
    for (int i = 0; i < il; ++i) {
      final short[] arr = mArray[i];
      Exam.assertNotNull(arr);
      Exam.assertEquals(mChunkSize, arr.length);
    }
    if (mTotalLength == ml) {
      Exam.assertTrue(mArray.length == il || mArray[(int) il] == null);
    } else {
      Exam.assertEquals(mTotalLength - ml, mArray[(int) il].length);
    }
    for (int i = (int) il + 1; i < mArray.length; ++i) {
      Exam.assertTrue(mArray[i] == null);
    }
    return true;
  }

  @Override
  public boolean safeFromWordTearing() {
    return true;
  }


  @Override
  public void save(ObjectOutputStream dos) throws IOException {
    dos.writeInt(IndexType.CHUNKS.ordinal());
    dos.writeLong(mLength);
    dos.writeLong(mTotalLength);
    dos.writeInt(mChunkBits);
    dos.writeInt(mChunkSize);
    dos.writeInt(mChunkMask);
    dos.writeObject(mArray);
  }

  /**
   * Should only be called from {@link ShortCreate#loadIndex(java.io.ObjectInputStream)}
   * @param ois stream to load from
   * @return index loaded from stream
   * @throws IOException if an IO error occurs
   */
  public static ShortChunks loadIndex(ObjectInputStream ois) throws IOException {
    final long length = ois.readLong();
    final long totalLength = ois.readLong();
    final int bits = ois.readInt();
    final int size = ois.readInt();
    final int mask = ois.readInt();
    final short[][] data;
    try {
      data = (short[][]) ois.readObject();
    } catch (ClassNotFoundException e) {
      throw new IOException("Unrecognized index type", e);
    }
    return new ShortChunks(length, bits, size, mask, data, totalLength);
  }
}
