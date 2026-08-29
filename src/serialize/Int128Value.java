package serialize;

/**
 * Signed 128-bit value as (hi, lo) long halves: a thin two's-complement layer
 * over {@link UInt128Value}. Addition, subtraction and shifts share the
 * unsigned bit patterns; the signed pieces are the ordering (the high lane
 * compares signed) and the sign-extending long conversion.
 *
 * Instances are immutable, like the unsigned half of the pair.
 */
public final class Int128Value
{
    /** The low 64 bits. First, matching the little-endian layout and the wire order. */
    public final long lo;

    /** The high 64 bits. The top bit is the sign. */
    public final long hi;

    public static final Int128Value ZERO = new Int128Value( 0, 0 );

    /** INT128_MIN: only the sign bit set. */
    public static final Int128Value MIN_VALUE = new Int128Value( 0x8000000000000000L, 0 );

    /** INT128_MAX: every bit but the sign set. */
    public static final Int128Value MAX_VALUE = new Int128Value( 0x7FFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL );

    /** Constructs from explicit high and low halves. */
    public Int128Value( long hi, long lo )
    {
        this.hi = hi;
        this.lo = lo;
    }

    /** Sign-extends a long, exactly as native conversion to __int128 does. */
    public static Int128Value fromLong( long value )
    {
        return new Int128Value( value < 0 ? -1L : 0, value );
    }

    /** Bit-preserving conversion from the unsigned type. */
    public static Int128Value fromUnsigned( UInt128Value value )
    {
        return new Int128Value( value.hi, value.lo );
    }

    /** Bit-preserving conversion to the unsigned type. */
    public UInt128Value toUnsigned()
    {
        return new UInt128Value( hi, lo );
    }

    /** The low 64 bits, wrapping two's complement like a native narrowing conversion. */
    public long toLong()
    {
        return lo;
    }

    public boolean isNegative()
    {
        return hi < 0;
    }

    public Int128Value add( Int128Value other )
    {
        return fromUnsigned( toUnsigned().add( other.toUnsigned() ) );
    }

    public Int128Value subtract( Int128Value other )
    {
        return fromUnsigned( toUnsigned().subtract( other.toUnsigned() ) );
    }

    /** A logical shift of the bit pattern, matching native two's-complement hardware. */
    public Int128Value shiftLeft( int shift )
    {
        return fromUnsigned( toUnsigned().shiftLeft( shift ) );
    }

    /** Two's-complement negation. Negating MIN_VALUE wraps to itself, like native. */
    public Int128Value negate()
    {
        return fromUnsigned( toUnsigned().negate() );
    }

    /** Signed ordering: the high lanes compare signed, the low lanes break ties unsigned. */
    public int compareTo( Int128Value other )
    {
        int c = Long.compare( hi, other.hi );
        return ( c != 0 ) ? c : Long.compareUnsigned( lo, other.lo );
    }

    @Override
    public boolean equals( Object obj )
    {
        if ( !( obj instanceof Int128Value other ) )
        {
            return false;
        }
        return lo == other.lo && hi == other.hi;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode( lo ) * 31 + Long.hashCode( hi );
    }

    @Override
    public String toString()
    {
        return String.format( "0x%016X%016X", hi, lo );
    }
}
