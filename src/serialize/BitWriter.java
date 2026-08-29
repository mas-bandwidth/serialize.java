package serialize;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Bitpacks unsigned integer values to a buffer.
 *
 * Values are written to a 64-bit scratch word least-significant-bit first.
 * Once the scratch fills to 64 bits it is stored to memory as a little-endian
 * qword; the bits that spilled past 64 carry over into the next scratch.
 *
 * The hot state is two fields: the scratch word and the bit cursor. The
 * scratch bit count and the store offset are derived from the cursor
 * ({@code bitsWritten & 63} and the cursor's qword-aligned byte position),
 * which keeps the write path small enough for the JIT to inline everywhere.
 *
 * IMPORTANT: the buffer size must be a multiple of 8 bytes, because words are
 * stored to memory 8 bytes at a time. Bytes past the end of the written data
 * are only ever written as zeros.
 */
public final class BitWriter
{
    static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle( long[].class, ByteOrder.LITTLE_ENDIAN );

    private byte[] data;
    private long scratch;
    private long numBits;
    private long bitsWritten;

    /**
     * Creates a bit writer over the given buffer.
     * @param data the buffer to fill with bitpacked data.
     * @param bytes the size of the buffer in bytes. Must be a multiple of 8.
     */
    public BitWriter( byte[] data, int bytes )
    {
        reset( data, bytes );
    }

    /**
     * Rewinds the writer over the given buffer, the allocation-free reuse
     * surface: same contract as the constructor.
     * @param data the buffer to fill with bitpacked data.
     * @param bytes the size of the buffer in bytes. Must be a multiple of 8.
     */
    public void reset( byte[] data, int bytes )
    {
        assert checkReset( data, bytes );
        this.data = data;
        this.numBits = (long) bytes * 8;
        this.bitsWritten = 0;
        this.scratch = 0;
    }

    private static boolean checkReset( byte[] data, int bytes )
    {
        assert data != null;
        assert ( bytes % 8 ) == 0;
        assert bytes <= data.length;
        return true;
    }

    /**
     * Write the low {@code bits} bits of {@code value} to the buffer.
     * @param value the value to write, in [0, 2^bits - 1]; the high int bits
     *        beyond {@code bits} must be zero when interpreted unsigned.
     * @param bits the number of bits to write, in [1,32].
     */
    public void writeBits( int value, int bits )
    {
        long unsignedValue = value & 0xFFFFFFFFL;

        // the contract lives in its own method so the hot path stays small
        // enough for the JIT to inline: an assert's bytecode is carried even
        // when -ea is absent, and it counts against inlining thresholds
        assert checkWriteBits( unsignedValue, bits );

        int scratchBits = (int) bitsWritten & 63;

        scratch |= unsignedValue << scratchBits;

        if ( scratchBits + bits >= 64 )
        {
            LONG_LE.set( data, (int) ( bitsWritten >>> 3 ) & ~7, scratch );
            // recover the bits that spilled past 64. scratchBits + bits >= 64 with bits <= 32 implies the shift is in [1,32]
            scratch = unsignedValue >>> ( 64 - scratchBits );
        }

        bitsWritten += bits;
    }

    private boolean checkWriteBits( long unsignedValue, int bits )
    {
        assert bits > 0;
        assert bits <= 32;
        assert bitsWritten + bits <= numBits;
        assert unsignedValue <= ( ( 1L << bits ) - 1 );
        return true;
    }

    /**
     * Write an alignment to the bit stream, padding zeros until the bit index
     * is a multiple of 8. If already aligned, nothing is written.
     */
    public void writeAlign()
    {
        int remainderBits = (int) ( bitsWritten % 8 );
        if ( remainderBits != 0 )
        {
            writeBits( 0, 8 - remainderBits );
            assert ( bitsWritten % 8 ) == 0;
        }
    }

    /**
     * Write an array of bytes to the bit stream. The bit index must be byte
     * aligned. The body is fused, matching the reference: one qword store
     * flushes the partial scratch word, one bulk copy lands the payload at
     * the byte cursor, and one qword load reloads the trailing partial word
     * into the scratch masked to its tail bits.
     */
    public void writeBytes( byte[] source, int bytes )
    {
        assert checkWriteBytes( bytes );

        // the head: the partial scratch word goes to the buffer whole — its high
        // bytes are zero, and the payload copy below overwrites exactly those.
        if ( ( bitsWritten & 63 ) != 0 )
        {
            LONG_LE.set( data, (int) ( bitsWritten >>> 3 ) & ~7, scratch );
        }

        // the body: the whole payload, straight in at the byte cursor
        System.arraycopy( source, 0, data, (int) ( bitsWritten >> 3 ), bytes );

        bitsWritten += (long) bytes * 8;

        // the tail: reload the trailing partial word into the scratch, masked to
        // its tail bits, so later writes pack into it exactly as if its bytes had
        // gone through the packer.
        int tailBits = (int) ( bitsWritten % 64 );
        if ( tailBits != 0 )
        {
            long word = (long) LONG_LE.get( data, (int) ( bitsWritten >>> 3 ) & ~7 );
            scratch = word & ( ( 1L << tailBits ) - 1 );
        }
        else
        {
            scratch = 0;
        }
    }

    private boolean checkWriteBytes( int bytes )
    {
        assert bytes >= 0;
        assert bitsWritten + (long) bytes * 8 <= numBits;
        assert ( bitsWritten % 8 ) == 0;                        // byte aligned
        return true;
    }

    /**
     * Flush any remaining bits to memory. Call once after writing, or the
     * last word of data will not reach the buffer. Stateless and idempotent:
     * it stores the partial scratch word and changes nothing.
     */
    public void flushBits()
    {
        if ( ( bitsWritten & 63 ) != 0 )
        {
            LONG_LE.set( data, (int) ( bitsWritten >>> 3 ) & ~7, scratch );
        }
    }

    /** The number of zero pad bits an align would write right now, in [0,7]. */
    public int getAlignBits()
    {
        return (int) ( ( 8 - ( bitsWritten % 8 ) ) % 8 );
    }

    /** The number of bits written so far. */
    public long getBitsWritten()
    {
        return bitsWritten;
    }

    /** The number of bits still available to write. */
    public long getBitsAvailable()
    {
        return numBits - bitsWritten;
    }

    /** The buffer this writer writes to. */
    public byte[] getData()
    {
        return data;
    }

    /**
     * The number of bytes of meaningful data written: the bit count rounded
     * up to a byte. This is the size of the packet to send. Call
     * {@link #flushBits} first.
     */
    public long getBytesWritten()
    {
        return ( bitsWritten + 7 ) / 8;
    }
}
