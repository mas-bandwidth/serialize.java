package serialize;

/**
 * Reads bitpacked integer values from a buffer, reconstructing the exact
 * sequence of bit reads that wrote it.
 *
 * Branchless: each read loads a 64-bit little-endian window from the current
 * byte position and shifts by the bit remainder. There is no scratch state
 * and no refill branch.
 *
 * IMPORTANT — the allocation contract, matching the C++ reference: the byte
 * array must extend at least 8 bytes past the end of the data being read,
 * because the reader loads 8-byte windows at byte granularity. The bytes past
 * the end are loaded but never interpreted: they can never influence a
 * decoded value or an accept/reject decision.
 */
public final class BitReader
{
    private final byte[] data;
    private final long numBits;
    private long bitsRead;

    /**
     * Creates a bit reader over the given buffer.
     * @param data the bitpacked data to read. The array must extend at least
     *        8 bytes past {@code bytes} — see the class comment.
     * @param bytes the number of bytes of bitpacked data to read.
     */
    public BitReader( byte[] data, int bytes )
    {
        assert data != null;
        assert bytes >= 0;
        assert data.length - bytes >= 8;        // the allocation contract: 8 bytes of slack past the data
        this.data = data;
        this.numBits = (long) bytes * 8;
        this.bitsRead = 0;
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
        assert bits > 0;
        assert bits <= 32;
        assert bitsRead + bits <= numBits;

        // loads up to 7 bytes past the last data byte: the allocation contract covers this
        long window = (long) BitWriter.LONG_LE.get( data, (int) ( bitsRead >> 3 ) );

        int output = (int) ( ( window >>> ( (int) ( bitsRead & 7 ) ) ) & ( ( 1L << bits ) - 1 ) );

        bitsRead += bits;

        return output;
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
        assert getAlignBits() == 0;
        assert bitsRead + (long) bytes * 8 <= numBits;

        System.arraycopy( data, (int) ( bitsRead >> 3 ), destination, 0, bytes );

        bitsRead += (long) bytes * 8;
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
