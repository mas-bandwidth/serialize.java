package serialize;

import java.nio.charset.StandardCharsets;

/**
 * Stream class for reading bitpacked data: a wrapper around
 * {@link BitReader} presenting the unified {@link BitStream} interface.
 *
 * The read side faces untrusted data: every refusal rule of STANDARD.md
 * binds here in every build mode. Out-of-range or truncated input returns
 * false; hostile bytes never throw. A failed read is terminal for the
 * stream — nothing after the failing operation has a defined position.
 */
public final class ReadStream implements BitStream
{
    private final BitReader reader;

    /**
     * @param buffer the buffer to read from. The array must extend at least
     *        8 bytes past {@code bytes}: the bit reader loads 64-bit windows
     *        at byte granularity. See {@link BitReader}.
     * @param bytes the number of bytes of packet data to read.
     */
    public ReadStream( byte[] buffer, int bytes )
    {
        reader = new BitReader( buffer, bytes );
    }

    /**
     * Rewinds the stream over the given buffer, the allocation-free reuse
     * surface: same contract as the constructor.
     * @param buffer the buffer to read from. The array must extend at least
     *        8 bytes past {@code bytes} — see {@link BitReader}.
     * @param bytes the number of bytes of packet data to read.
     */
    public void reset( byte[] buffer, int bytes )
    {
        reader.reset( buffer, bytes );
    }

    @Override public boolean isWriting() { return false; }

    @Override public boolean isReading() { return true; }

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

    private static boolean checkBits64( int bits )
    {
        assert bits > 0;
        assert bits <= 64;
        return true;
    }

    @Override
    public boolean serializeBits( IntRef value, int bits )
    {
        assert checkBits32( bits );
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return false;
        }
        value.value = reader.readBits( bits );
        return true;
    }

    @Override
    public boolean serializeBits64( LongRef value, int bits )
    {
        assert checkBits64( bits );
        if ( bits <= 32 )
        {
            if ( reader.wouldReadPastEnd( bits ) )
            {
                return false;
            }
            value.value = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else
        {
            // low dword first, then the high remainder — each group checked on its own,
            // matching the reference macro's composition from two 32-bit operations
            if ( reader.wouldReadPastEnd( 32 ) )
            {
                return false;
            }
            long lo = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 32 ) )
            {
                return false;
            }
            long hi = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
            value.value = ( hi << 32 ) | lo;
        }
        return true;
    }

    @Override
    public boolean serializeInt( IntRef value, int min, int max )
    {
        assert min <= max;
        int bits = SerializeUtil.bitsRequired( min, max );
        if ( bits == 0 )
        {
            value.value = min;              // degenerate range: the value IS the range
            return true;
        }
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return false;
        }
        int unsignedValue = reader.readBits( bits );
        if ( Integer.compareUnsigned( unsignedValue, max - min ) > 0 )
        {
            return false;
        }
        // add in the unsigned domain: wraps when the range is wider than 2^31
        value.value = unsignedValue + min;
        return true;
    }

    @Override
    public boolean serializeInt64( LongRef value, long min, long max )
    {
        assert min <= max;
        int bits = SerializeUtil.bitsRequired64( min, max );
        if ( bits == 0 )
        {
            value.value = min;              // degenerate range: the value IS the range
            return true;
        }
        // one truncation check for the whole value, matching the reference stream method
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return false;
        }
        long unsignedValue;
        if ( bits <= 32 )
        {
            unsignedValue = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else
        {
            long lo = Integer.toUnsignedLong( reader.readBits( 32 ) );
            long hi = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
            unsignedValue = ( hi << 32 ) | lo;
        }
        if ( Long.compareUnsigned( unsignedValue, max - min ) > 0 )
        {
            return false;
        }
        // add in the unsigned domain: wraps when the range is wider than 2^63
        value.value = unsignedValue + min;
        return true;
    }

    @Override
    public boolean serializeInt128( Ref<Int128Value> value, Int128Value min, Int128Value max )
    {
        assert min.compareTo( max ) < 0;
        UInt128Value unsignedMin = min.toUnsigned();
        UInt128Value unsignedMax = max.toUnsigned();
        int bits = SerializeUtil.bitsRequired128( unsignedMin, unsignedMax );
        // one truncation check for the whole value, matching the reference stream method
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return false;
        }
        UInt128Value offset = readGroups128( bits );
        if ( offset.compareUnsigned( unsignedMax.subtract( unsignedMin ) ) > 0 )
        {
            return false;
        }
        // add in the unsigned domain: wraps when the range is wider than 2^127
        value.value = Int128Value.fromUnsigned( offset.add( unsignedMin ) );
        return true;
    }

    // 32-bit groups, least significant first. The caller has already priced the
    // whole value against the stream end.
    private UInt128Value readGroups128( int bits )
    {
        long group0 = 0;
        long group1 = 0;
        long group2 = 0;
        long group3 = 0;
        if ( bits <= 32 )
        {
            group0 = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else if ( bits <= 64 )
        {
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            group1 = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
        }
        else if ( bits <= 96 )
        {
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            group1 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            group2 = Integer.toUnsignedLong( reader.readBits( bits - 64 ) );
        }
        else
        {
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            group1 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            group2 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            group3 = Integer.toUnsignedLong( reader.readBits( bits - 96 ) );
        }
        return new UInt128Value( ( group3 << 32 ) | group2, ( group1 << 32 ) | group0 );
    }

    @Override
    public boolean serializeUint8( IntRef value )
    {
        return serializeBits( value, 8 );
    }

    @Override
    public boolean serializeUint16( IntRef value )
    {
        return serializeBits( value, 16 );
    }

    @Override
    public boolean serializeUint32( IntRef value )
    {
        return serializeBits( value, 32 );
    }

    @Override
    public boolean serializeUint64( LongRef value )
    {
        return serializeBits64( value, 64 );
    }

    @Override
    public boolean serializeUint128( Ref<UInt128Value> value )
    {
        // the low 64-bit half first, then the high half — composed from 32-bit
        // groups with per-group truncation checks, matching the reference macro
        if ( reader.wouldReadPastEnd( 32 ) ) return false;
        long a = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) ) return false;
        long b = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) ) return false;
        long c = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) ) return false;
        long d = Integer.toUnsignedLong( reader.readBits( 32 ) );
        value.value = new UInt128Value( ( d << 32 ) | c, ( b << 32 ) | a );
        return true;
    }

    @Override
    public boolean serializeBool( BoolRef value )
    {
        if ( reader.wouldReadPastEnd( 1 ) )
        {
            return false;
        }
        value.value = reader.readBits( 1 ) != 0;
        return true;
    }

    @Override
    public boolean serializeFloat( FloatRef value )
    {
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return false;
        }
        // bit transparent: the read returns exactly the bits read — NaN payloads,
        // signaling NaNs, infinities, negative zero and denormals all pass through
        value.value = Float.intBitsToFloat( reader.readBits( 32 ) );
        return true;
    }

    @Override
    public boolean serializeDouble( DoubleRef value )
    {
        // two 32-bit groups with per-group truncation checks, matching the
        // reference macro's composition
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return false;
        }
        long lo = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return false;
        }
        long hi = Integer.toUnsignedLong( reader.readBits( 32 ) );
        value.value = Double.longBitsToDouble( ( hi << 32 ) | lo );
        return true;
    }

    @Override
    public boolean serializeCompressedFloat( FloatRef value, float min, float max, float resolution )
    {
        long maxIntegerValue = SerializeUtil.compressedFloatMaxIntegerValue( min, max, resolution );
        int bits = SerializeUtil.bitsRequired( 0, (int) maxIntegerValue );
        float delta = max - min;
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return false;
        }
        int integerValue = reader.readBits( bits );
        // reject an integer above maxIntegerValue smuggled into the bit headroom
        if ( Integer.toUnsignedLong( integerValue ) > maxIntegerValue )
        {
            return false;
        }
        value.value = SerializeUtil.compressedFloatReadValue( integerValue, maxIntegerValue, delta, min );
        return true;
    }

    @Override
    public boolean serializeBytes( byte[] data, int bytes )
    {
        if ( bytes < 0 )
        {
            return false;
        }
        if ( !serializeAlign() )
        {
            return false;
        }
        // compare in bytes rather than bits, consistent with the reference's bookkeeping
        if ( bytes > reader.getBitsRemaining() / 8 )
        {
            return false;
        }
        reader.readBytes( data, bytes );
        return true;
    }

    @Override
    public boolean serializeAlign()
    {
        int alignBits = reader.getAlignBits();
        if ( reader.wouldReadPastEnd( alignBits ) )
        {
            return false;
        }
        return reader.readAlign();
    }

    @Override
    public boolean serializeString( Ref<String> value, int bufferSize )
    {
        // the length, over [0, bufferSize-1]
        IntRef length = new IntRef();
        if ( !serializeInt( length, 0, bufferSize - 1 ) )
        {
            return false;
        }
        // the bytes, which align
        byte[] utf8 = new byte[length.value];
        if ( !serializeBytes( utf8, length.value ) )
        {
            return false;
        }
        // STANDARD.md, "Readers must refuse malformed string payloads".
        // Interior NUL first: a zero byte among the transmitted bytes gives the
        // payload two lengths, and everything between them rides invisibly.
        // NUL is valid UTF-8, so the validator below cannot catch it.
        for ( int i = 0; i < length.value; i++ )
        {
            if ( utf8[i] == 0 )
            {
                return false;
            }
        }
        if ( !SerializeUtil.isValidUtf8( utf8, length.value ) )
        {
            return false;
        }
        value.value = new String( utf8, StandardCharsets.UTF_8 );
        return true;
    }

    @Override
    public boolean serializeWideString( Ref<String> value, int bufferSize )
    {
        IntRef length = new IntRef();
        if ( !serializeInt( length, 0, bufferSize - 1 ) )
        {
            return false;
        }
        // each group is one UTF-16 code unit, and malformed payloads are refused
        // in every build mode: a group above 0xFFFF is not a code unit, an
        // interior NUL gives the payload two lengths, and the pair discipline
        // refuses a high surrogate without its low, a low with no high before
        // it, and a dangling high as the final group. Well-formed pairs pass —
        // they are how astral text travels.
        char[] output = new char[length.value];
        char pending = 0;                       // a high surrogate awaiting its pair
        boolean havePending = false;
        int outputIndex = 0;
        for ( int i = 0; i < length.value; i++ )
        {
            if ( reader.wouldReadPastEnd( 32 ) )
            {
                return false;
            }
            int character = reader.readBits( 32 );
            if ( Integer.compareUnsigned( character, 0xFFFF ) > 0 )
            {
                return false;                   // not a UTF-16 code unit: nothing conforming emits one
            }
            if ( character == 0 )
            {
                return false;                   // interior NUL: the two-lengths smuggling primitive
            }
            if ( havePending )
            {
                if ( character < 0xDC00 || character > 0xDFFF )
                {
                    return false;               // high surrogate without its low
                }
                output[outputIndex++] = pending;
                output[outputIndex++] = (char) character;
                havePending = false;
                continue;
            }
            if ( character >= 0xDC00 && character <= 0xDFFF )
            {
                return false;                   // low surrogate with no high before it
            }
            if ( character >= 0xD800 && character <= 0xDBFF )
            {
                pending = (char) character;
                havePending = true;
                continue;
            }
            output[outputIndex++] = (char) character;
        }
        if ( havePending )
        {
            return false;                       // the final group is a dangling high surrogate
        }
        value.value = new String( output, 0, outputIndex );
        return true;
    }

    @Override
    public boolean serializeIntRelative( int previous, IntRef current )
    {
        // the one-bit tier
        if ( reader.wouldReadPastEnd( 1 ) )
        {
            return false;
        }
        if ( reader.readBits( 1 ) != 0 )
        {
            // reconstruct in the unsigned domain: wraps near the type maximum
            current.value = previous + 1;
            return true;
        }

        // the bounded difference tiers
        for ( int tier = 0; tier < RELATIVE_TIER_MIN.length; tier++ )
        {
            if ( reader.wouldReadPastEnd( 1 ) )
            {
                return false;
            }
            if ( reader.readBits( 1 ) != 0 )
            {
                int tierMin = RELATIVE_TIER_MIN[tier];
                int tierMax = RELATIVE_TIER_MAX[tier];
                int bits = SerializeUtil.bitsRequired( tierMin, tierMax );
                if ( reader.wouldReadPastEnd( bits ) )
                {
                    return false;
                }
                int unsignedValue = reader.readBits( bits );
                if ( Integer.compareUnsigned( unsignedValue, tierMax - tierMin ) > 0 )
                {
                    return false;
                }
                // reconstruct in the unsigned domain: wraps near the type maximum
                current.value = previous + ( unsignedValue + tierMin );
                return true;
            }
        }

        // the final tier transmits current, not the difference, and the reader
        // must check the ordering the absolute form does not carry
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return false;
        }
        current.value = reader.readBits( 32 );
        if ( current.value <= previous )
        {
            return false;
        }
        return true;
    }

    static final int[] RELATIVE_TIER_MIN = { 2, 7, 24, 281, 4378 };
    static final int[] RELATIVE_TIER_MAX = { 6, 23, 280, 4377, 69914 };

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
        long rawRange = rawMax - rawMin;

        if ( minUnits == maxUnits )
        {
            value.value = rawMin;               // degenerate range: the value IS the range
            return true;
        }

        int bits = SerializeUtil.bitsRequired64( rawMin, rawMax );

        long offset;
        if ( bits <= 32 )
        {
            if ( reader.wouldReadPastEnd( bits ) )
            {
                return false;
            }
            offset = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else
        {
            // per-group truncation checks: the reference composes this path from
            // two stream-level 32-bit operations
            if ( reader.wouldReadPastEnd( 32 ) )
            {
                return false;
            }
            long lo = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 32 ) )
            {
                return false;
            }
            long hi = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
            offset = ( hi << 32 ) | lo;
        }

        // reject raw values outside [rawMin,rawMax] smuggled into the bit headroom — reject, never clamp
        if ( Long.compareUnsigned( offset, rawRange ) > 0 )
        {
            return false;
        }
        value.value = rawMin + offset;
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

        UInt128Value rawMin = Int128Value.fromLong( minUnits ).toUnsigned().shiftLeft( fractionBits );
        UInt128Value rawMax = Int128Value.fromLong( maxUnits ).toUnsigned().shiftLeft( fractionBits );
        UInt128Value rawRange = rawMax.subtract( rawMin );

        if ( minUnits == maxUnits )
        {
            // degenerate range: zero bits on every storage width (STANDARD.md)
            value.value = Int128Value.fromUnsigned( rawMin );
            return true;
        }

        int bits = SerializeUtil.bitsRequired64( minUnits, maxUnits ) + fractionBits;

        // per-group truncation checks: the reference composes the wide path from
        // stream-level 32-bit operations
        long group0 = 0;
        long group1 = 0;
        long group2 = 0;
        long group3 = 0;
        if ( bits <= 32 )
        {
            if ( reader.wouldReadPastEnd( bits ) ) return false;
            group0 = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else if ( bits <= 64 )
        {
            if ( reader.wouldReadPastEnd( 32 ) ) return false;
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 32 ) ) return false;
            group1 = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
        }
        else if ( bits <= 96 )
        {
            if ( reader.wouldReadPastEnd( 32 ) ) return false;
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( 32 ) ) return false;
            group1 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 64 ) ) return false;
            group2 = Integer.toUnsignedLong( reader.readBits( bits - 64 ) );
        }
        else
        {
            if ( reader.wouldReadPastEnd( 32 ) ) return false;
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( 32 ) ) return false;
            group1 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( 32 ) ) return false;
            group2 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 96 ) ) return false;
            group3 = Integer.toUnsignedLong( reader.readBits( bits - 96 ) );
        }
        UInt128Value offset = new UInt128Value( ( group3 << 32 ) | group2, ( group1 << 32 ) | group0 );

        // reject raw values outside [rawMin,rawMax] smuggled into the bit headroom — reject, never clamp
        if ( offset.compareUnsigned( rawRange ) > 0 )
        {
            return false;
        }
        value.value = Int128Value.fromUnsigned( rawMin.add( offset ) );
        return true;
    }

    @Override
    public int getAlignBits()
    {
        return reader.getAlignBits();
    }

    @Override
    public long getBitsProcessed()
    {
        return reader.getBitsRead();
    }

    @Override
    public long getBytesProcessed()
    {
        return ( reader.getBitsRead() + 7 ) / 8;
    }
}
