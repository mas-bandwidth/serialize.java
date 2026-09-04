package serialize;

import java.nio.charset.StandardCharsets;

/**
 * Stream class for reading bitpacked data: a wrapper around
 * {@link BitReader} presenting the unified {@link BitStream} interface.
 *
 * The read side faces untrusted data: every refusal rule of STANDARD.md
 * binds here in every build mode. Out-of-range or truncated input returns
 * false; hostile bytes never throw.
 *
 * A refused read leaves its scalar destination unwritten — the holder cell
 * holds what it held before the call — except for a caller-owned buffer,
 * whose contents are unspecified after a refusal.
 *
 * A failed read is terminal: nothing after the failing operation has a
 * defined position, so the stream latches. Every read after the first
 * refusal returns false, consuming no bits and writing no destination, until
 * {@link #reset} points the stream at data again.
 */
public final class ReadStream implements BitStream
{
    private final BitReader reader;

    /**
     * The failure latch. The first refused read sets it, every later read on
     * this stream refuses without consuming a bit or writing a destination,
     * and only {@link #reset} clears it.
     */
    private boolean failed;

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
        failed = false;
    }

    @Override public boolean isWriting() { return false; }

    @Override public boolean isReading() { return true; }

    /** Has a read on this stream failed? Every read after the first refusal refuses. */
    public boolean isFailed()
    {
        return failed;
    }

    // Sets the latch and reports the refusal in one expression, so a refusal
    // reads as `return fail();` wherever it occurs.
    private boolean fail()
    {
        failed = true;
        return false;
    }

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
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        assert checkBits32( bits );
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return fail();
        }
        value.value = reader.readBits( bits );
        return true;
    }

    @Override
    public boolean serializeBits64( LongRef value, int bits )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        assert checkBits64( bits );
        if ( bits <= 32 )
        {
            if ( reader.wouldReadPastEnd( bits ) )
            {
                return fail();
            }
            value.value = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else
        {
            // low dword first, then the high remainder — each group checked on its own,
            // matching the reference macro's composition from two 32-bit operations
            if ( reader.wouldReadPastEnd( 32 ) )
            {
                return fail();
            }
            long lo = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 32 ) )
            {
                return fail();
            }
            long hi = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
            value.value = ( hi << 32 ) | lo;
        }
        return true;
    }

    @Override
    public boolean serializeInt( IntRef value, int min, int max )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        assert min <= max;
        int bits = SerializeUtil.bitsRequired( min, max );
        if ( bits == 0 )
        {
            value.value = min;              // degenerate range: the value IS the range
            return true;
        }
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return fail();
        }
        int unsignedValue = reader.readBits( bits );
        if ( Integer.compareUnsigned( unsignedValue, max - min ) > 0 )
        {
            return fail();
        }
        // add in the unsigned domain: wraps when the range is wider than 2^31
        value.value = unsignedValue + min;
        return true;
    }

    @Override
    public boolean serializeInt64( LongRef value, long min, long max )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
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
            return fail();
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
            return fail();
        }
        // add in the unsigned domain: wraps when the range is wider than 2^63
        value.value = unsignedValue + min;
        return true;
    }

    @Override
    public boolean serializeInt128( Ref<Int128Value> value, Int128Value min, Int128Value max )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        assert min.compareTo( max ) <= 0;
        UInt128Value unsignedMin = min.toUnsigned();
        UInt128Value unsignedMax = max.toUnsigned();
        int bits = SerializeUtil.bitsRequired128( unsignedMin, unsignedMax );
        if ( bits == 0 )
        {
            value.value = min;              // degenerate range: the value IS the range
            return true;
        }
        // one truncation check for the whole value, matching the reference stream method
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return fail();
        }
        UInt128Value offset = readGroups128( bits );
        if ( offset.compareUnsigned( unsignedMax.subtract( unsignedMin ) ) > 0 )
        {
            return fail();
        }
        // add in the unsigned domain: wraps when the range is wider than 2^127
        value.value = Int128Value.fromUnsigned( offset.add( unsignedMin ) );
        return true;
    }

    // 32-bit groups, least significant first, for a width of 1 to 128 bits.
    // The caller has already priced the whole value against the stream end and
    // has routed the zero-bit degenerate range away: the bit primitive reads
    // 1 to 32 bits per group.
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
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        // the low 64-bit half first, then the high half — composed from 32-bit
        // groups with per-group truncation checks, matching the reference macro
        if ( reader.wouldReadPastEnd( 32 ) ) return fail();
        long a = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) ) return fail();
        long b = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) ) return fail();
        long c = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) ) return fail();
        long d = Integer.toUnsignedLong( reader.readBits( 32 ) );
        value.value = new UInt128Value( ( d << 32 ) | c, ( b << 32 ) | a );
        return true;
    }

    @Override
    public boolean serializeBool( BoolRef value )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        if ( reader.wouldReadPastEnd( 1 ) )
        {
            return fail();
        }
        value.value = reader.readBits( 1 ) != 0;
        return true;
    }

    @Override
    public boolean serializeFloat( FloatRef value )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return fail();
        }
        // bit transparent: the read returns exactly the bits read — NaN payloads,
        // signaling NaNs, infinities, negative zero and denormals all pass through
        value.value = Float.intBitsToFloat( reader.readBits( 32 ) );
        return true;
    }

    @Override
    public boolean serializeDouble( DoubleRef value )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        // two 32-bit groups with per-group truncation checks, matching the
        // reference macro's composition
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return fail();
        }
        long lo = Integer.toUnsignedLong( reader.readBits( 32 ) );
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return fail();
        }
        long hi = Integer.toUnsignedLong( reader.readBits( 32 ) );
        value.value = Double.longBitsToDouble( ( hi << 32 ) | lo );
        return true;
    }

    @Override
    public boolean serializeCompressedFloat( FloatRef value, float min, float max, float resolution )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        long maxIntegerValue = SerializeUtil.compressedFloatMaxIntegerValue( min, max, resolution );
        int bits = SerializeUtil.bitsRequired( 0, (int) maxIntegerValue );
        float delta = max - min;
        if ( reader.wouldReadPastEnd( bits ) )
        {
            return fail();
        }
        int integerValue = reader.readBits( bits );
        // reject an integer above maxIntegerValue smuggled into the bit headroom
        if ( Integer.toUnsignedLong( integerValue ) > maxIntegerValue )
        {
            return fail();
        }
        value.value = SerializeUtil.compressedFloatReadValue( integerValue, maxIntegerValue, delta, min );
        return true;
    }

    @Override
    public boolean serializeBytes( byte[] data, int bytes )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        if ( bytes < 0 )
        {
            return fail();
        }
        if ( !serializeAlign() )
        {
            return fail();
        }
        // compare in bytes rather than bits, consistent with the reference's bookkeeping
        if ( bytes > reader.getBitsRemaining() / 8 )
        {
            return fail();
        }
        reader.readBytes( data, bytes );
        return true;
    }

    @Override
    public boolean serializeAlign()
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        int alignBits = reader.getAlignBits();
        if ( reader.wouldReadPastEnd( alignBits ) )
        {
            return fail();
        }
        if ( !reader.readAlign() )
        {
            return fail();                  // the padding was not zero
        }
        return true;
    }

    @Override
    public boolean serializeString( Ref<String> value, int bufferSize )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        // the length, over [0, bufferSize-1]
        IntRef length = new IntRef();
        if ( !serializeInt( length, 0, bufferSize - 1 ) )
        {
            return fail();
        }
        // the bytes, which align
        byte[] utf8 = new byte[length.value];
        if ( !serializeBytes( utf8, length.value ) )
        {
            return fail();
        }
        // STANDARD.md, "Readers must refuse malformed string payloads".
        // Interior NUL first: a zero byte among the transmitted bytes gives the
        // payload two lengths, and everything between them rides invisibly.
        // NUL is valid UTF-8, so the validator below cannot catch it.
        for ( int i = 0; i < length.value; i++ )
        {
            if ( utf8[i] == 0 )
            {
                return fail();
            }
        }
        if ( !SerializeUtil.isValidUtf8( utf8, length.value ) )
        {
            return fail();
        }
        value.value = new String( utf8, StandardCharsets.UTF_8 );
        return true;
    }

    @Override
    public boolean serializeWideString( Ref<String> value, int bufferSize )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        IntRef length = new IntRef();
        if ( !serializeInt( length, 0, bufferSize - 1 ) )
        {
            return fail();
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
                return fail();
            }
            int character = reader.readBits( 32 );
            if ( Integer.compareUnsigned( character, 0xFFFF ) > 0 )
            {
                return fail();                   // not a UTF-16 code unit: nothing conforming emits one
            }
            if ( character == 0 )
            {
                return fail();                   // interior NUL: the two-lengths smuggling primitive
            }
            if ( havePending )
            {
                if ( character < 0xDC00 || character > 0xDFFF )
                {
                    return fail();               // high surrogate without its low
                }
                output[outputIndex++] = pending;
                output[outputIndex++] = (char) character;
                havePending = false;
                continue;
            }
            if ( character >= 0xDC00 && character <= 0xDFFF )
            {
                return fail();                   // low surrogate with no high before it
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
            return fail();                       // the final group is a dangling high surrogate
        }
        value.value = new String( output, 0, outputIndex );
        return true;
    }

    @Override
    public boolean serializeIntRelative( int previous, IntRef current )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        assert previous >= 0;               // the domain: previous is caller state, never off the wire

        // the one-bit tier
        if ( reader.wouldReadPastEnd( 1 ) )
        {
            return fail();
        }
        if ( reader.readBits( 1 ) != 0 )
        {
            return acceptIntRelative( previous, (long) previous + 1, current );
        }

        // the bounded difference tiers
        for ( int tier = 0; tier < RELATIVE_TIER_MIN.length; tier++ )
        {
            if ( reader.wouldReadPastEnd( 1 ) )
            {
                return fail();
            }
            if ( reader.readBits( 1 ) != 0 )
            {
                int tierMin = RELATIVE_TIER_MIN[tier];
                int tierMax = RELATIVE_TIER_MAX[tier];
                int bits = SerializeUtil.bitsRequired( tierMin, tierMax );
                if ( reader.wouldReadPastEnd( bits ) )
                {
                    return fail();
                }
                int unsignedValue = reader.readBits( bits );
                if ( Integer.compareUnsigned( unsignedValue, tierMax - tierMin ) > 0 )
                {
                    return fail();
                }
                long difference = Integer.toUnsignedLong( unsignedValue ) + tierMin;
                return acceptIntRelative( previous, previous + difference, current );
            }
        }

        // the final tier transmits current, not the difference. Its 32 raw bits
        // are unsigned, so a value with the top bit set lies outside the domain
        // and the reconstruction check below refuses it.
        if ( reader.wouldReadPastEnd( 32 ) )
        {
            return fail();
        }
        long absolute = Integer.toUnsignedLong( reader.readBits( 32 ) );
        return acceptIntRelative( previous, absolute, current );
    }

    // STANDARD.md, "int_relative": every tier reconstructs current in a width
    // that cannot wrap — a long here — and the read is refused unless the
    // result lies in the domain, 0 to 2^31 - 1 inclusive, and is strictly
    // greater than previous. The destination is written only on acceptance.
    private boolean acceptIntRelative( int previous, long reconstructed, IntRef current )
    {
        if ( reconstructed < 0 || reconstructed > Integer.MAX_VALUE )
        {
            return fail();
        }
        if ( reconstructed <= previous )
        {
            return fail();
        }
        current.value = (int) reconstructed;
        return true;
    }

    static final int[] RELATIVE_TIER_MIN = { 2, 7, 24, 281, 4378 };
    static final int[] RELATIVE_TIER_MAX = { 6, 23, 280, 4377, 69914 };

    @Override
    public boolean serializeFixed( LongRef value, int integerBits, int fractionBits, long minUnits, long maxUnits )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
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
                return fail();
            }
            offset = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else
        {
            // per-group truncation checks: the reference composes this path from
            // two stream-level 32-bit operations
            if ( reader.wouldReadPastEnd( 32 ) )
            {
                return fail();
            }
            long lo = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 32 ) )
            {
                return fail();
            }
            long hi = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
            offset = ( hi << 32 ) | lo;
        }

        // reject raw values outside [rawMin,rawMax] smuggled into the bit headroom — reject, never clamp
        if ( Long.compareUnsigned( offset, rawRange ) > 0 )
        {
            return fail();
        }
        value.value = rawMin + offset;
        return true;
    }

    @Override
    public boolean serializeFixed128( Ref<Int128Value> value, int integerBits, int fractionBits, long minUnits, long maxUnits )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
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
            if ( reader.wouldReadPastEnd( bits ) ) return fail();
            group0 = Integer.toUnsignedLong( reader.readBits( bits ) );
        }
        else if ( bits <= 64 )
        {
            if ( reader.wouldReadPastEnd( 32 ) ) return fail();
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 32 ) ) return fail();
            group1 = Integer.toUnsignedLong( reader.readBits( bits - 32 ) );
        }
        else if ( bits <= 96 )
        {
            if ( reader.wouldReadPastEnd( 32 ) ) return fail();
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( 32 ) ) return fail();
            group1 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 64 ) ) return fail();
            group2 = Integer.toUnsignedLong( reader.readBits( bits - 64 ) );
        }
        else
        {
            if ( reader.wouldReadPastEnd( 32 ) ) return fail();
            group0 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( 32 ) ) return fail();
            group1 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( 32 ) ) return fail();
            group2 = Integer.toUnsignedLong( reader.readBits( 32 ) );
            if ( reader.wouldReadPastEnd( bits - 96 ) ) return fail();
            group3 = Integer.toUnsignedLong( reader.readBits( bits - 96 ) );
        }
        UInt128Value offset = new UInt128Value( ( group3 << 32 ) | group2, ( group1 << 32 ) | group0 );

        // reject raw values outside [rawMin,rawMax] smuggled into the bit headroom — reject, never clamp
        if ( offset.compareUnsigned( rawRange ) > 0 )
        {
            return fail();
        }
        value.value = Int128Value.fromUnsigned( rawMin.add( offset ) );
        return true;
    }

    @Override
    public boolean serializeObject( Serializer object )
    {
        if ( failed ) return false;         // the latch: a failed stream refuses everything after
        return object.serialize( this );
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
