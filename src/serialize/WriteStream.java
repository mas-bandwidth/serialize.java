package serialize;

import java.nio.charset.StandardCharsets;

/**
 * Stream class for writing bitpacked data: a wrapper around
 * {@link BitWriter} presenting the unified {@link BitStream} interface.
 *
 * Writes assume trusted data: every contract here is a debug assert (run
 * with -ea), never a runtime refusal — the reference's writer-trusted
 * doctrine.
 */
public final class WriteStream implements BitStream
{
    private final BitWriter writer;

    /**
     * @param buffer the buffer to write to.
     * @param bytes the number of bytes in the buffer. Must be a multiple of 8,
     *        because the bit writer stores qwords to memory.
     */
    public WriteStream( byte[] buffer, int bytes )
    {
        writer = new BitWriter( buffer, bytes );
    }

    /**
     * Rewinds the stream over the given buffer, the allocation-free reuse
     * surface: same contract as the constructor.
     * @param buffer the buffer to write to.
     * @param bytes the number of bytes in the buffer. Must be a multiple of 8.
     */
    public void reset( byte[] buffer, int bytes )
    {
        writer.reset( buffer, bytes );
    }

    @Override public boolean isWriting() { return true; }

    @Override public boolean isReading() { return false; }

    @Override
    public boolean serializeBits( IntRef value, int bits )
    {
        assert bits > 0;
        assert bits <= 32;
        writer.writeBits( value.value, bits );
        return true;
    }

    @Override
    public boolean serializeBits64( LongRef value, int bits )
    {
        assert bits > 0;
        assert bits <= 64;
        if ( bits <= 32 )
        {
            writer.writeBits( (int) value.value, bits );
        }
        else
        {
            // low dword first, then the high remainder
            writer.writeBits( (int) value.value, 32 );
            writer.writeBits( (int) ( value.value >>> 32 ), bits - 32 );
        }
        return true;
    }

    // internal int write shared by the composed operations (string lengths, relative tiers)
    private void writeInt( int value, int min, int max )
    {
        assert min <= max;
        assert value >= min;
        assert value <= max;
        int bits = SerializeUtil.bitsRequired( min, max );
        if ( bits == 0 )
        {
            return;                 // degenerate range: the value IS the range, nothing to send
        }
        // subtract in the unsigned domain: wraps when the range is wider than 2^31
        writer.writeBits( value - min, bits );
    }

    @Override
    public boolean serializeInt( IntRef value, int min, int max )
    {
        writeInt( value.value, min, max );
        return true;
    }

    @Override
    public boolean serializeInt64( LongRef value, long min, long max )
    {
        assert min <= max;
        assert value.value >= min;
        assert value.value <= max;
        int bits = SerializeUtil.bitsRequired64( min, max );
        if ( bits == 0 )
        {
            return true;            // degenerate range: the value IS the range, nothing to send
        }
        // subtract in the unsigned domain: wraps when the range is wider than 2^63
        long unsignedValue = value.value - min;
        if ( bits <= 32 )
        {
            writer.writeBits( (int) unsignedValue, bits );
        }
        else
        {
            // low dword first, then the high remainder
            writer.writeBits( (int) unsignedValue, 32 );
            writer.writeBits( (int) ( unsignedValue >>> 32 ), bits - 32 );
        }
        return true;
    }

    @Override
    public boolean serializeInt128( Ref<Int128Value> value, Int128Value min, Int128Value max )
    {
        assert min.compareTo( max ) < 0;
        assert value.value.compareTo( min ) >= 0;
        assert value.value.compareTo( max ) <= 0;
        int bits = SerializeUtil.bitsRequired128( min.toUnsigned(), max.toUnsigned() );
        // subtract in the unsigned domain: wraps when the range is wider than 2^127
        UInt128Value offset = value.value.toUnsigned().subtract( min.toUnsigned() );
        writeGroups128( offset, bits );
        return true;
    }

    // 32-bit groups, least significant first: the shared wide-value convention
    private void writeGroups128( UInt128Value offset, int bits )
    {
        int group0 = (int) offset.lo;
        int group1 = (int) ( offset.lo >>> 32 );
        int group2 = (int) offset.hi;
        int group3 = (int) ( offset.hi >>> 32 );
        if ( bits <= 32 )
        {
            writer.writeBits( group0, bits );
        }
        else if ( bits <= 64 )
        {
            writer.writeBits( group0, 32 );
            writer.writeBits( group1, bits - 32 );
        }
        else if ( bits <= 96 )
        {
            writer.writeBits( group0, 32 );
            writer.writeBits( group1, 32 );
            writer.writeBits( group2, bits - 64 );
        }
        else
        {
            writer.writeBits( group0, 32 );
            writer.writeBits( group1, 32 );
            writer.writeBits( group2, 32 );
            writer.writeBits( group3, bits - 96 );
        }
    }

    @Override public boolean serializeUint8( IntRef value )  { writer.writeBits( value.value, 8 );  return true; }

    @Override public boolean serializeUint16( IntRef value ) { writer.writeBits( value.value, 16 ); return true; }

    @Override public boolean serializeUint32( IntRef value ) { writer.writeBits( value.value, 32 ); return true; }

    @Override
    public boolean serializeUint64( LongRef value )
    {
        return serializeBits64( value, 64 );
    }

    @Override
    public boolean serializeUint128( Ref<UInt128Value> value )
    {
        // the low 64-bit half first, then the high half, each as low-then-high 32-bit groups
        writer.writeBits( (int) value.value.lo, 32 );
        writer.writeBits( (int) ( value.value.lo >>> 32 ), 32 );
        writer.writeBits( (int) value.value.hi, 32 );
        writer.writeBits( (int) ( value.value.hi >>> 32 ), 32 );
        return true;
    }

    @Override
    public boolean serializeBool( BoolRef value )
    {
        writer.writeBits( value.value ? 1 : 0, 1 );
        return true;
    }

    @Override
    public boolean serializeFloat( FloatRef value )
    {
        writer.writeBits( Float.floatToRawIntBits( value.value ), 32 );
        return true;
    }

    @Override
    public boolean serializeDouble( DoubleRef value )
    {
        long bits = Double.doubleToRawLongBits( value.value );
        writer.writeBits( (int) bits, 32 );
        writer.writeBits( (int) ( bits >>> 32 ), 32 );
        return true;
    }

    @Override
    public boolean serializeCompressedFloat( FloatRef value, float min, float max, float resolution )
    {
        long maxIntegerValue = SerializeUtil.compressedFloatMaxIntegerValue( min, max, resolution );
        int bits = SerializeUtil.bitsRequired( 0, (int) maxIntegerValue );
        float delta = max - min;
        int code = SerializeUtil.compressedFloatWriteCode( value.value, min, delta, maxIntegerValue );
        writer.writeBits( code, bits );
        return true;
    }

    @Override
    public boolean serializeBytes( byte[] data, int bytes )
    {
        assert data != null;
        assert bytes >= 0;
        serializeAlign();
        writer.writeBytes( data, bytes );
        return true;
    }

    @Override
    public boolean serializeAlign()
    {
        writer.writeAlign();
        return true;
    }

    @Override
    public boolean serializeString( Ref<String> value, int bufferSize )
    {
        String string = value.value;
        assert string != null;
        // the writer's contract, debug only: well-formed UTF-16 in the source
        // string guarantees well-formed UTF-8 on the wire, and no interior NUL
        // keeps the wire length and the logical length the same
        assert SerializeUtil.isValidUtf16( string );
        assert SerializeUtil.hasNoInteriorNul( string );
        byte[] utf8 = string.getBytes( StandardCharsets.UTF_8 );
        int length = utf8.length;
        assert length < bufferSize;
        writeInt( length, 0, bufferSize - 1 );
        serializeAlign();
        writer.writeBytes( utf8, length );
        return true;
    }

    @Override
    public boolean serializeWideString( Ref<String> value, int bufferSize )
    {
        String string = value.value;
        assert string != null;
        // the writer's contract, debug only: no unpaired surrogates, no interior NUL
        assert SerializeUtil.isValidUtf16( string );
        assert SerializeUtil.hasNoInteriorNul( string );
        // Java strings are UTF-16 code units natively, which is exactly what the
        // wire carries: one 32-bit group per code unit, surrogate pairs as-is
        int length = string.length();
        assert length < bufferSize;
        writeInt( length, 0, bufferSize - 1 );
        for ( int i = 0; i < length; i++ )
        {
            writer.writeBits( string.charAt( i ), 32 );
        }
        return true;
    }

    @Override
    public boolean serializeIntRelative( int previous, IntRef current )
    {
        assert previous < current.value;
        // subtract in the unsigned domain: wraps when the gap is wider than 2^31
        int difference = current.value - previous;

        boolean oneBit = difference == 1;
        writer.writeBits( oneBit ? 1 : 0, 1 );
        if ( oneBit )
        {
            return true;
        }

        boolean twoBits = Integer.compareUnsigned( difference, 6 ) <= 0;
        writer.writeBits( twoBits ? 1 : 0, 1 );
        if ( twoBits )
        {
            writeInt( difference, 2, 6 );
            return true;
        }

        boolean fourBits = Integer.compareUnsigned( difference, 23 ) <= 0;
        writer.writeBits( fourBits ? 1 : 0, 1 );
        if ( fourBits )
        {
            writeInt( difference, 7, 23 );
            return true;
        }

        boolean eightBits = Integer.compareUnsigned( difference, 280 ) <= 0;
        writer.writeBits( eightBits ? 1 : 0, 1 );
        if ( eightBits )
        {
            writeInt( difference, 24, 280 );
            return true;
        }

        boolean twelveBits = Integer.compareUnsigned( difference, 4377 ) <= 0;
        writer.writeBits( twelveBits ? 1 : 0, 1 );
        if ( twelveBits )
        {
            writeInt( difference, 281, 4377 );
            return true;
        }

        boolean sixteenBits = Integer.compareUnsigned( difference, 69914 ) <= 0;
        writer.writeBits( sixteenBits ? 1 : 0, 1 );
        if ( sixteenBits )
        {
            writeInt( difference, 4378, 69914 );
            return true;
        }

        // the final tier transmits current, not the difference
        writer.writeBits( current.value, 32 );
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

        // shift the whole-unit bounds into raw fixed point units: wraps two's complement in the unsigned 64-bit domain
        long rawMin = minUnits << fractionBits;
        long rawMax = maxUnits << fractionBits;
        long rawRange = rawMax - rawMin;

        if ( minUnits == maxUnits )
        {
            // degenerate range: the value IS the range, nothing to send
            assert value.value == rawMin;
            return true;
        }

        int bits = SerializeUtil.bitsRequired64( rawMin, rawMax );

        // subtract in the unsigned domain: wraps when the range is wider than 2^63
        long offset = value.value - rawMin;
        assert Long.compareUnsigned( offset, rawRange ) <= 0;

        if ( bits <= 32 )
        {
            writer.writeBits( (int) offset, bits );
        }
        else
        {
            // low dword first, then the high remainder
            writer.writeBits( (int) offset, 32 );
            writer.writeBits( (int) ( offset >>> 32 ), bits - 32 );
        }
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

        // shift the whole-unit bounds into raw fixed point units in the unsigned 128-bit domain
        UInt128Value rawMin = Int128Value.fromLong( minUnits ).toUnsigned().shiftLeft( fractionBits );
        UInt128Value rawMax = Int128Value.fromLong( maxUnits ).toUnsigned().shiftLeft( fractionBits );
        UInt128Value rawRange = rawMax.subtract( rawMin );

        if ( minUnits == maxUnits )
        {
            // degenerate range: zero bits on every storage width (STANDARD.md)
            assert value.value.toUnsigned().equals( rawMin );
            return true;
        }

        // the wire cost, computed in the 64-bit domain: the range in whole units is
        // exact in 64 bits, and the shift adds exactly fractionBits to its bit length
        int bits = SerializeUtil.bitsRequired64( minUnits, maxUnits ) + fractionBits;

        UInt128Value offset = value.value.toUnsigned().subtract( rawMin );
        assert offset.compareUnsigned( rawRange ) <= 0;

        writeGroups128( offset, bits );
        return true;
    }

    @Override
    public int getAlignBits()
    {
        return writer.getAlignBits();
    }

    /** Flush the last scratch word to memory. Call after the final operation, before reading the data. */
    public void flush()
    {
        writer.flushBits();
    }

    /** The buffer this stream writes to. */
    public byte[] getData()
    {
        return writer.getData();
    }

    @Override
    public long getBitsProcessed()
    {
        return writer.getBitsWritten();
    }

    @Override
    public long getBytesProcessed()
    {
        return writer.getBytesWritten();
    }
}
