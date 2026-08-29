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
 * IMPORTANT: the buffer size must be a multiple of 8 bytes, because words are
 * stored to memory 8 bytes at a time. Bytes past the end of the written data
 * are only ever written as zeros.
 */
public final class BitWriter
{
    static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle( long[].class, ByteOrder.LITTLE_ENDIAN );

    private final byte[] data;
    private long scratch;
    private final long numBits;
    private long bitsWritten;
    private int wordIndex;
    private int scratchBits;

    /**
     * Creates a bit writer over the given buffer.
     * @param data the buffer to fill with bitpacked data.
     * @param bytes the size of the buffer in bytes. Must be a multiple of 8.
     */
    public BitWriter( byte[] data, int bytes )
    {
        assert data != null;
        assert ( bytes % 8 ) == 0;
        assert bytes <= data.length;
        this.data = data;
        this.numBits = (long) bytes * 8;
        this.bitsWritten = 0;
        this.wordIndex = 0;
        this.scratch = 0;
        this.scratchBits = 0;
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

        assert bits > 0;
        assert bits <= 32;
        assert bitsWritten + bits <= numBits;
        assert unsignedValue <= ( ( 1L << bits ) - 1 );

        scratch |= unsignedValue << scratchBits;

        int newScratchBits = scratchBits + bits;

        if ( newScratchBits >= 64 )
        {
            LONG_LE.set( data, wordIndex * 8, scratch );
            wordIndex++;
            // recover the bits that spilled past 64. newScratchBits >= 64 with bits <= 32 implies the shift is in [1,32]
            scratch = unsignedValue >>> ( 64 - scratchBits );
            scratchBits = newScratchBits - 64;
        }
        else
        {
            scratchBits = newScratchBits;
        }

        bitsWritten += bits;
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
        assert bytes >= 0;
        assert bitsWritten + (long) bytes * 8 <= numBits;
        assert ( bitsWritten % 8 ) == 0;                        // byte aligned
        assert scratchBits == bitsWritten % 64;                 // mid-stream the scratch tracks the cursor

        // the head: the partial scratch word goes to the buffer whole — its high
        // bytes are zero, and the payload copy below overwrites exactly those.
        if ( scratchBits != 0 )
        {
            LONG_LE.set( data, wordIndex * 8, scratch );
        }

        // the body: the whole payload, straight in at the byte cursor
        System.arraycopy( source, 0, data, (int) ( bitsWritten >> 3 ), bytes );

        bitsWritten += (long) bytes * 8;
        wordIndex = (int) ( bitsWritten / 64 );

        // the tail: reload the trailing partial word into the scratch, masked to
        // its tail bits, so later writes pack into it exactly as if its bytes had
        // gone through the packer.
        int tailBits = (int) ( bitsWritten % 64 );
        if ( tailBits != 0 )
        {
            long word = (long) LONG_LE.get( data, wordIndex * 8 );
            scratch = word & ( ( 1L << tailBits ) - 1 );
        }
        else
        {
            scratch = 0;
        }
        scratchBits = tailBits;
    }

    /**
     * Flush any remaining bits to memory. Call once after writing, or the
     * last word of data will not reach the buffer.
     */
    public void flushBits()
    {
        if ( scratchBits != 0 )
        {
            assert scratchBits < 64;
            LONG_LE.set( data, wordIndex * 8, scratch );
            scratch = 0;
            scratchBits = 0;
            wordIndex++;
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
