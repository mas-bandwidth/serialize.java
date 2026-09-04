package serialize;

import java.nio.charset.StandardCharsets;

/**
 * Stream class for computing a conservative bound on the size a message
 * would occupy, without producing bytes.
 *
 * A measure is a bound, not the packet size: it must report a size
 * sufficient to serialize the message at any starting bit position, so every
 * alignment-performing operation — align, bytes, string — charges the worst
 * case of 7 bits, and everything else charges exact width. Exact-from-zero
 * accounting is non-conforming (STANDARD.md, The Measure Stream).
 *
 * A measure refuses nothing at runtime: it sits on the trusted side of the
 * boundary, and misuse is debug asserts only, like the write path.
 */
public final class MeasureStream implements BitStream
{
    private long bitsWritten;

    public MeasureStream()
    {
        bitsWritten = 0;
    }

    /** Rewinds the measure to zero bits, the allocation-free reuse surface. */
    public void reset()
    {
        bitsWritten = 0;
    }

    @Override public boolean isWriting() { return true; }

    @Override public boolean isReading() { return false; }

    // the contracts of the hot operations live in their own methods so the
    // hot bodies stay small enough for the JIT to inline: an assert's
    // bytecode is carried even when -ea is absent, and it counts against
    // inlining thresholds

    private static boolean checkBits32( int bits )
    {
        assert bits > 0;
        assert bits <= 32;
        return true;
    }

    // the same width bound the write stream carries, on the caller's 64-bit
    // value: a measure runs the writer's contracts so a non-conforming call is
    // diagnosed on every writing stream. SerializeUtil.valueFitsInBits is the
    // one place the rule lives.
    private static boolean checkBits64( long value, int bits )
    {
        assert bits > 0;
        assert bits <= 64;
        assert SerializeUtil.valueFitsInBits( value, bits );
        return true;
    }

    private static boolean checkInt( int value, int min, int max )
    {
        assert min <= max;
        assert value >= min;
        assert value <= max;
        return true;
    }

    private static boolean checkInt64( long value, long min, long max )
    {
        assert min <= max;
        assert value >= min;
        assert value <= max;
        return true;
    }

    @Override
    public boolean serializeBits( IntRef value, int bits )
    {
        assert checkBits32( bits );
        bitsWritten += bits;
        return true;
    }

    @Override
    public boolean serializeBits64( LongRef value, int bits )
    {
        assert checkBits64( value.value, bits );
        bitsWritten += bits;
        return true;
    }

    @Override
    public boolean serializeInt( IntRef value, int min, int max )
    {
        assert checkInt( value.value, min, max );
        bitsWritten += SerializeUtil.bitsRequired( min, max );
        return true;
    }

    @Override
    public boolean serializeInt64( LongRef value, long min, long max )
    {
        assert checkInt64( value.value, min, max );
        bitsWritten += SerializeUtil.bitsRequired64( min, max );
        return true;
    }

    @Override
    public boolean serializeInt128( Ref<Int128Value> value, Int128Value min, Int128Value max )
    {
        assert min.compareTo( max ) <= 0;
        assert value.value.compareTo( min ) >= 0;
        assert value.value.compareTo( max ) <= 0;
        bitsWritten += SerializeUtil.bitsRequired128( min.toUnsigned(), max.toUnsigned() );
        return true;
    }

    @Override public boolean serializeUint8( IntRef value )  { bitsWritten += 8;  return true; }

    @Override public boolean serializeUint16( IntRef value ) { bitsWritten += 16; return true; }

    @Override public boolean serializeUint32( IntRef value ) { bitsWritten += 32; return true; }

    @Override public boolean serializeUint64( LongRef value ) { bitsWritten += 64; return true; }

    @Override public boolean serializeUint128( Ref<UInt128Value> value ) { bitsWritten += 128; return true; }

    @Override
    public boolean serializeBool( BoolRef value )
    {
        bitsWritten += 1;
        return true;
    }

    @Override
    public boolean serializeFloat( FloatRef value )
    {
        bitsWritten += 32;
        return true;
    }

    @Override
    public boolean serializeDouble( DoubleRef value )
    {
        bitsWritten += 64;
        return true;
    }

    @Override
    public boolean serializeCompressedFloat( FloatRef value, float min, float max, float resolution )
    {
        // the measure runs the writer's derivation so the same debug asserts
        // guard a non-conforming declaration or value on every writing stream
        long maxIntegerValue = SerializeUtil.compressedFloatMaxIntegerValue( min, max, resolution );
        assert value.value - value.value == 0.0f;
        bitsWritten += SerializeUtil.bitsRequired( 0, (int) maxIntegerValue );
        return true;
    }

    private static boolean checkBytes( byte[] data, int bytes )
    {
        assert data != null;
        assert bytes >= 0;
        return true;
    }

    @Override
    public boolean serializeBytes( byte[] data, int bytes )
    {
        assert checkBytes( data, bytes );
        serializeAlign();
        bitsWritten += (long) bytes * 8;
        return true;
    }

    @Override
    public boolean serializeAlign()
    {
        // worst case, always: alignment cost depends on the bit position the
        // message is later written at, which a measure does not know
        bitsWritten += getAlignBits();
        return true;
    }

    @Override
    public boolean serializeString( Ref<String> value, int bufferSize )
    {
        String string = value.value;
        assert string != null;
        assert SerializeUtil.isValidUtf16( string );
        assert SerializeUtil.hasNoInteriorNul( string );
        byte[] utf8 = string.getBytes( StandardCharsets.UTF_8 );
        int length = utf8.length;
        assert length < bufferSize;
        bitsWritten += SerializeUtil.bitsRequired( 0, bufferSize - 1 );
        serializeAlign();
        bitsWritten += (long) length * 8;
        return true;
    }

    @Override
    public boolean serializeWideString( Ref<String> value, int bufferSize )
    {
        String string = value.value;
        assert string != null;
        assert SerializeUtil.isValidUtf16( string );
        assert SerializeUtil.hasNoInteriorNul( string );
        int length = string.length();
        assert length < bufferSize;
        bitsWritten += SerializeUtil.bitsRequired( 0, bufferSize - 1 );
        bitsWritten += (long) length * 32;          // no alignment anywhere in this operation
        return true;
    }

    @Override
    public boolean serializeIntRelative( int previous, IntRef current )
    {
        assert previous >= 0;               // the domain: 0 to 2^31 - 1, previous and current alike
        assert previous < current.value;
        int difference = current.value - previous;

        bitsWritten += 1;                                           // the one-bit tier flag
        if ( difference == 1 )
        {
            return true;
        }
        for ( int tier = 0; tier < ReadStream.RELATIVE_TIER_MIN.length; tier++ )
        {
            bitsWritten += 1;                                       // this tier's flag
            if ( Integer.compareUnsigned( difference, ReadStream.RELATIVE_TIER_MAX[tier] ) <= 0 )
            {
                bitsWritten += SerializeUtil.bitsRequired( ReadStream.RELATIVE_TIER_MIN[tier],
                                                           ReadStream.RELATIVE_TIER_MAX[tier] );
                return true;
            }
        }
        bitsWritten += 32;                                          // the final tier: current as raw bits
        return true;
    }

    @Override
    public boolean serializeFixed( LongRef value, int integerBits, int fractionBits, long minUnits, long maxUnits )
    {
        assert integerBits >= 1;
        assert fractionBits >= 0;
        int width = integerBits + fractionBits;
        assert width == 8 || width == 16 || width == 32 || width == 64;
        assert minUnits <= maxUnits;
        assert SerializeUtil.fixedBoundsFit( integerBits, minUnits, maxUnits );

        long rawMin = minUnits << fractionBits;
        long rawMax = maxUnits << fractionBits;

        if ( minUnits == maxUnits )
        {
            assert value.value == rawMin;
            return true;                    // degenerate range: zero bits
        }

        assert Long.compareUnsigned( value.value - rawMin, rawMax - rawMin ) <= 0;
        bitsWritten += SerializeUtil.bitsRequired64( rawMin, rawMax );
        return true;
    }

    @Override
    public boolean serializeFixed128( Ref<Int128Value> value, int integerBits, int fractionBits, long minUnits, long maxUnits )
    {
        assert integerBits >= 1;
        assert fractionBits >= 0;
        assert integerBits + fractionBits == 128;
        assert minUnits <= maxUnits;
        assert SerializeUtil.fixedBoundsFit( integerBits, minUnits, maxUnits );

        if ( minUnits == maxUnits )
        {
            // degenerate range: zero bits on every storage width (STANDARD.md)
            assert value.value.toUnsigned().equals(
                Int128Value.fromLong( minUnits ).toUnsigned().shiftLeft( fractionBits ) );
            return true;
        }

        bitsWritten += SerializeUtil.bitsRequired64( minUnits, maxUnits ) + fractionBits;
        return true;
    }

    @Override
    public boolean serializeObject( Serializer object )
    {
        return object.serialize( this );
    }

    /** Always the worst case of 7: the measure does not know the final bit position. */
    @Override
    public int getAlignBits()
    {
        return 7;
    }

    @Override
    public long getBitsProcessed()
    {
        return bitsWritten;
    }

    @Override
    public long getBytesProcessed()
    {
        return ( bitsWritten + 7 ) / 8;
    }
}
