package serialize;

import java.util.Arrays;

/**
 * Reads bitpacked integer values from a buffer, reconstructing the exact
 * sequence of bit reads that wrote it.
 *
 * Effectively branchless: each read loads a 64-bit little-endian window from
 * the current byte position and shifts by the bit remainder. There is no
 * refill branch; the only scratch state is the sixteen-byte tail a tight
 * buffer is read through.
 *
 * Any buffer size is supported. For the fastest reads, keep at least 8 bytes
 * of slack in the array past the data — for example, read packets into a
 * large buffer and read them out of it in place — and every window load comes
 * straight from the array. Without slack, {@link #reset} copies the final
 * bytes of the data into a small zero-padded tail window, and reads near the
 * end of the buffer load from that instead. Either way the load is a single
 * window: slack (or padding) bytes are loaded but never interpreted, because
 * bits past the end of the data cannot reach the output of a read, so they
 * can never influence a decoded value or an accept/reject decision.
 */
public final class BitReader
{
    // the reach of a window load: 8 data bytes hold any [1,32] field at any
    // bit offset. The widest window a legal read can ask for starts at the
    // last byte of the data, which is why the tail is two windows long.
    private static final int WINDOW_BYTES = 8;

    private byte[] data;
    private long numBits;
    private long bitsRead;
    private int tailBase;                                       // byte index the tail window is based at
    private final byte[] tail = new byte[WINDOW_BYTES * 2];     // zero-padded copy of the final data bytes (arrays with less than WINDOW_BYTES of slack)

    /**
     * Creates a bit reader over the given buffer.
     * @param data the bitpacked data to read. Any array size works; at least
     *        8 bytes of slack past {@code bytes} is fastest — see the class
     *        comment.
     * @param bytes the number of bytes of bitpacked data to read.
     */
    public BitReader( byte[] data, int bytes )
    {
        reset( data, bytes );
    }

    /**
     * Rewinds the reader over the given buffer, the allocation-free reuse
     * surface: same contract as the constructor. When the array has less than
     * 8 bytes of slack past the data, the final bytes are copied into the
     * zero-padded tail window so that every read has a whole window of
     * readable bytes beneath it.
     * @param data the bitpacked data to read. Any array size works; at least
     *        8 bytes of slack past {@code bytes} is fastest — see the class
     *        comment.
     * @param bytes the number of bytes of bitpacked data to read.
     */
    public void reset( byte[] data, int bytes )
    {
        assert checkReset( data, bytes );
        this.data = data;
        this.numBits = (long) bytes * 8;
        this.bitsRead = 0;
        if ( data.length - bytes < WINDOW_BYTES )
        {
            // no slack past the data: the final bytes live in the zero-padded
            // tail window, and reads at or past tailBase load from it. The
            // padding is never interpreted, so zeros here produce exactly the
            // same outputs as slack bytes would.
            int copied = Math.min( bytes, WINDOW_BYTES );
            this.tailBase = bytes - copied;
            System.arraycopy( data, tailBase, tail, 0, copied );
            Arrays.fill( tail, copied, tail.length, (byte) 0 );
        }
        else
        {
            this.tailBase = Integer.MAX_VALUE;                  // every window load comes straight from the array
        }
    }

    private static boolean checkReset( byte[] data, int bytes )
    {
        assert data != null;
        assert bytes >= 0;
        assert bytes <= data.length;
        return true;
    }

    /** Would reading this many bits read past the end of the buffer? */
    public boolean wouldReadPastEnd( int bits )
    {
        return bitsRead + bits > numBits;
    }

    /**
     * Read bits from the bit buffer.
     * @param bits the number of bits to read, in [1,32].
     * @return the value read, in [0, 2^bits - 1], as an unsigned value in an int.
     */
    public int readBits( int bits )
    {
        // the contract lives in its own method so the hot path stays small
        // enough for the JIT to inline: an assert's bytecode is carried even
        // when -ea is absent, and it counts against inlining thresholds
        assert checkReadBits( bits );

        // loads up to 7 bytes past the last data byte: from the array when it
        // has the slack, otherwise from the zero-padded tail window. One
        // well-predicted branch — an array with slack never takes the tail.
        int i = (int) ( bitsRead >> 3 );
        long window = i < tailBase ? (long) BitWriter.LONG_LE.get( data, i ) : (long) BitWriter.LONG_LE.get( tail, i - tailBase );

        int output = (int) ( ( window >>> ( (int) ( bitsRead & 7 ) ) ) & ( ( 1L << bits ) - 1 ) );

        bitsRead += bits;

        return output;
    }

    private boolean checkReadBits( int bits )
    {
        assert bits > 0;
        assert bits <= 32;
        assert bitsRead + bits <= numBits;
        return true;
    }

    /**
     * Read an align, skipping ahead to the next byte boundary and verifying
     * the padding bits are zero.
     * @return true if the padding was zero (or the stream was already
     *         aligned), false otherwise.
     */
    public boolean readAlign()
    {
        int remainderBits = (int) ( bitsRead % 8 );
        if ( remainderBits != 0 )
        {
            int value = readBits( 8 - remainderBits );
            assert bitsRead % 8 == 0;
            if ( value != 0 )
            {
                return false;
            }
        }
        return true;
    }

    /** Read bytes from the bitpacked data. The bit index must be byte aligned. */
    public void readBytes( byte[] destination, int bytes )
    {
        assert checkReadBytes( bytes );

        System.arraycopy( data, (int) ( bitsRead >> 3 ), destination, 0, bytes );

        bitsRead += (long) bytes * 8;
    }

    private boolean checkReadBytes( int bytes )
    {
        assert getAlignBits() == 0;
        assert bitsRead + (long) bytes * 8 <= numBits;
        return true;
    }

    /** The number of zero pad bits an align would read right now, in [0,7]. */
    public int getAlignBits()
    {
        return (int) ( ( 8 - bitsRead % 8 ) % 8 );
    }

    /** The number of bits read so far. */
    public long getBitsRead()
    {
        return bitsRead;
    }

    /** The number of bits still available to read. */
    public long getBitsRemaining()
    {
        return numBits - bitsRead;
    }
}
