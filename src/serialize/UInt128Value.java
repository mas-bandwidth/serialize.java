package serialize;

/**
 * Unsigned 128-bit value as (hi, lo) long halves — Java has no 128-bit integer
 * type, so this is the wire interchange type for serialize_uint128 and the
 * unsigned domain the 128-bit codecs run their offset math in.
 *
 * The operation set is exactly what the serialize paths and their tests need:
 * add, subtract, shifts, or, not, unsigned compare, conversions to and from
 * the 64-bit lanes. Two's-complement math on the halves matches native
 * unsigned __int128 bit for bit.
 *
 * Instances are immutable. The 128-bit paths allocate one small object per
 * operation — a Java language necessity (no user-definable value types at
 * this language level); the 64-bit hot paths allocate nothing.
 */
public final class UInt128Value
{
    /** The low 64 bits. First, matching the little-endian layout and the wire order. */
    public final long lo;

    /** The high 64 bits. */
    public final long hi;

    public static final UInt128Value ZERO = new UInt128Value( 0, 0 );

    /** Constructs from explicit high and low halves. */
    public UInt128Value( long hi, long lo )
    {
        this.hi = hi;
        this.lo = lo;
    }

    /** Zero-extends an unsigned 64-bit value held in a long. */
    public static UInt128Value fromUnsignedLong( long value )
    {
        return new UInt128Value( 0, value );
    }

    /** The low 64 bits, truncating like a native narrowing conversion. */
    public long toLong()
    {
        return lo;
    }

    public UInt128Value add( UInt128Value other )
    {
        long sumLo = lo + other.lo;
        long carry = ( Long.compareUnsigned( sumLo, lo ) < 0 ) ? 1 : 0;
        return new UInt128Value( hi + other.hi + carry, sumLo );
    }

    public UInt128Value subtract( UInt128Value other )
    {
        long diffLo = lo - other.lo;
        long borrow = ( Long.compareUnsigned( lo, other.lo ) < 0 ) ? 1 : 0;
        return new UInt128Value( hi - other.hi - borrow, diffLo );
    }

    /** Logical left shift. Shift counts outside [0,127] yield zero, matching the reference's emulated pair. */
    public UInt128Value shiftLeft( int shift )
    {
        if ( shift == 0 )
        {
            return this;
        }
        if ( shift > 0 && shift < 64 )
        {
            return new UInt128Value( ( hi << shift ) | ( lo >>> ( 64 - shift ) ), lo << shift );
        }
        if ( shift >= 64 && shift < 128 )
        {
            return new UInt128Value( lo << ( shift - 64 ), 0 );
        }
        return ZERO;
    }

    /** Logical right shift. Shift counts outside [0,127] yield zero, matching the reference's emulated pair. */
    public UInt128Value shiftRight( int shift )
    {
        if ( shift == 0 )
        {
            return this;
        }
        if ( shift > 0 && shift < 64 )
        {
            return new UInt128Value( hi >>> shift, ( lo >>> shift ) | ( hi << ( 64 - shift ) ) );
        }
        if ( shift >= 64 && shift < 128 )
        {
            return new UInt128Value( 0, hi >>> ( shift - 64 ) );
        }
        return ZERO;
    }

    public UInt128Value or( UInt128Value other )
    {
        return new UInt128Value( hi | other.hi, lo | other.lo );
    }

    public UInt128Value not()
    {
        return new UInt128Value( ~hi, ~lo );
    }

    /** Two's-complement negation. */
    public UInt128Value negate()
    {
        return ZERO.subtract( this );
    }

    /** Unsigned ordering: the high lanes compare unsigned, the low lanes break ties. */
    public int compareUnsigned( UInt128Value other )
    {
        int c = Long.compareUnsigned( hi, other.hi );
        return ( c != 0 ) ? c : Long.compareUnsigned( lo, other.lo );
    }

    @Override
    public boolean equals( Object obj )
    {
        if ( !( obj instanceof UInt128Value other ) )
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
