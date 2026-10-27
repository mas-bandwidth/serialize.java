package serialize.fuzz;

/**
 * Hands out bytes from the fuzz input, wrapping around when exhausted: the
 * reference's ValuePool. Every pass of the round trip builds its own pool
 * over the same bytes and regenerates the identical value sequence, so
 * nothing needs to be stored between passes.
 */
final class ValuePool
{
    private final byte[] data;
    private final int offset;
    private final int size;
    private int index;

    ValuePool( byte[] data, int offset, int size )
    {
        this.data = data;
        this.offset = offset;
        this.size = size;
        this.index = 0;
    }

    int nextByte()
    {
        if ( size == 0 )
        {
            return 0;
        }
        if ( index >= size )
        {
            index = 0;
        }
        return data[offset + index++] & 0xFF;
    }

    int nextUint32()
    {
        int value = 0;
        for ( int i = 0; i < 4; i++ )
        {
            value = ( value << 8 ) | nextByte();
        }
        return value;
    }

    long nextUint64()
    {
        return ( Integer.toUnsignedLong( nextUint32() ) << 32 ) | Integer.toUnsignedLong( nextUint32() );
    }
}
