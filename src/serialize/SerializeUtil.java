package serialize;

/**
 * The stream-independent helpers of the serialize library: bit width
 * calculations, zig-zag conversion, the UTF-8 validator shared by the string
 * write contract and read refusal, and the compressed-float quantization
 * arithmetic pinned by STANDARD.md.
 */
public final class SerializeUtil
{
    private SerializeUtil() {}

    /** The library version, matching the release tag. */
    public static final String VERSION = "1.1.2";

    /**
     * Does a value fit in a field of this width? STANDARD.md bounds a
     * {@code bits} field by {@code value < 2^bits} at every width in [1,64],
     * not only at 32 or fewer.
     *
     * This is the write side bound for {@code serializeBits64}, and it runs on
     * the caller's 64-bit value before any narrowing to a 32-bit group. A
     * check placed after the narrowing is handed a value the narrowing already
     * made legal: 2^32 + 5 arrives as 5, which fits four bits.
     *
     * @param value the caller's value, read as unsigned.
     * @param bits the field width, in [1,64].
     * @return true if the value fits in that many bits.
     */
    public static boolean valueFitsInBits( long value, int bits )
    {
        return bits >= 64 || Long.compareUnsigned( value, ( 1L << bits ) - 1 ) <= 0;
    }

    /**
     * The number of bits required to serialize an integer in [min,max].
     * The subtraction wraps in the unsigned 32-bit domain, so the full
     * int32 range reports 32 bits.
     */
    public static int bitsRequired( int min, int max )
    {
        return ( min == max ) ? 0 : 32 - Integer.numberOfLeadingZeros( max - min );
    }

    /**
     * The number of bits required to serialize a 64-bit integer in [min,max].
     * The subtraction wraps in the unsigned 64-bit domain, so ranges wider
     * than 2^63 are exact.
     */
    public static int bitsRequired64( long min, long max )
    {
        return ( min == max ) ? 0 : 64 - Long.numberOfLeadingZeros( max - min );
    }

    /**
     * The number of bits required to serialize a 128-bit integer in [min,max].
     * The subtraction runs in the unsigned 128-bit domain, so ranges wider
     * than 2^127 are exact.
     */
    public static int bitsRequired128( UInt128Value min, UInt128Value max )
    {
        if ( min.equals( max ) )
        {
            return 0;
        }
        UInt128Value diff = max.subtract( min );
        return ( diff.hi != 0 ) ? ( 64 + bitsRequired64( 0, diff.hi ) ) : bitsRequired64( 0, diff.lo );
    }

    /**
     * Convert a signed integer to an unsigned integer with zig-zag encoding:
     * 0,-1,+1,-2,+2... becomes 0,1,2,3,4...
     */
    public static int signedToUnsigned( int n )
    {
        return ( n << 1 ) ^ ( n >> 31 );
    }

    /**
     * Convert an unsigned integer to a signed integer with zig-zag encoding:
     * 0,1,2,3,4... becomes 0,-1,+1,-2,+2...
     */
    public static int unsignedToSigned( int n )
    {
        return ( n >>> 1 ) ^ -( n & 1 );
    }

    /*
        UTF-8 well-formedness, one validator with two callers (STANDARD.md):
        the WRITE path's contract check — a debug-only assert per the
        writes-trusted doctrine — and the READ path's refusal rule, which binds
        in every build mode. Rejects overlong encodings, surrogate code points,
        values above U+10FFFF, truncated sequences and stray continuation
        bytes. NUL bytes are VALID UTF-8: the interior-NUL refusal is a
        separate rule with its own reason.
    */

    static boolean isValidUtf8( byte[] bytes, int length )
    {
        int i = 0;
        while ( i < length )
        {
            int lead = bytes[i] & 0xFF;
            if ( lead < 0x80 )
            {
                i += 1;
            }
            else if ( ( lead & 0xE0 ) == 0xC0 )
            {
                if ( lead < 0xC2 )                                                  // overlong
                    return false;
                if ( i + 1 >= length )
                    return false;
                if ( ( bytes[i+1] & 0xC0 ) != 0x80 )
                    return false;
                i += 2;
            }
            else if ( ( lead & 0xF0 ) == 0xE0 )
            {
                if ( i + 2 >= length )
                    return false;
                int byte1 = bytes[i+1] & 0xFF;
                int byte2 = bytes[i+2] & 0xFF;
                if ( ( byte1 & 0xC0 ) != 0x80 || ( byte2 & 0xC0 ) != 0x80 )
                    return false;
                if ( lead == 0xE0 && byte1 < 0xA0 )                                 // overlong
                    return false;
                if ( lead == 0xED && byte1 >= 0xA0 )                                // surrogate code point
                    return false;
                i += 3;
            }
            else if ( ( lead & 0xF8 ) == 0xF0 )
            {
                if ( lead > 0xF4 )                                                  // above U+10FFFF
                    return false;
                if ( i + 3 >= length )
                    return false;
                int byte1 = bytes[i+1] & 0xFF;
                int byte2 = bytes[i+2] & 0xFF;
                int byte3 = bytes[i+3] & 0xFF;
                if ( ( byte1 & 0xC0 ) != 0x80 || ( byte2 & 0xC0 ) != 0x80 || ( byte3 & 0xC0 ) != 0x80 )
                    return false;
                if ( lead == 0xF0 && byte1 < 0x90 )                                 // overlong
                    return false;
                if ( lead == 0xF4 && byte1 >= 0x90 )                                // above U+10FFFF
                    return false;
                i += 4;
            }
            else
            {
                return false;                                                       // continuation or invalid lead byte
            }
        }
        return true;
    }

    /*
        The wstring payload is well-formed UTF-16 BY CONTRACT (STANDARD.md):
        an unpaired surrogate is a writer contract violation, debug-asserted.
        Java strings are UTF-16 code units natively, so the check is the
        2-byte-wchar_t pairing discipline of the reference.
    */

    static boolean isValidUtf16( String string )
    {
        int i = 0;
        int length = string.length();
        while ( i < length )
        {
            char character = string.charAt( i );
            if ( character >= 0xD800 && character <= 0xDBFF )
            {
                if ( i + 1 >= length )
                    return false;                                                   // dangling high surrogate
                char next = string.charAt( i + 1 );
                if ( next < 0xDC00 || next > 0xDFFF )
                    return false;                                                   // high surrogate without its pair
                i += 2;
            }
            else if ( character >= 0xDC00 && character <= 0xDFFF )
            {
                return false;                                                       // low surrogate with no high before it
            }
            else
            {
                i += 1;
            }
        }
        return true;
    }

    /** True if the string contains no NUL character: the writer's interior-NUL contract. */
    static boolean hasNoInteriorNul( String string )
    {
        return string.indexOf( '\0' ) < 0;
    }

    /**
     * The quantization step count of a compressed float declaration:
     * ceil( delta / res ), clamped to [1, 4294967040] (the largest float
     * below 2^32). A declaration whose delta or delta/res is not finite in
     * float32 is non-conforming and asserts in debug, per the writer-trusted
     * model. Returned as a long because the count is an unsigned 32-bit
     * quantity that can exceed Integer.MAX_VALUE.
     */
    public static long compressedFloatMaxIntegerValue( float min, float max, float res )
    {
        assert min < max && res > 0.0f;

        float delta = max - min;
        float values = delta / res;

        // finiteness spelled x - x == 0: NaN and both infinities fail it
        assert delta - delta == 0.0f;
        assert values - values == 0.0f;

        // clamp so the integer conversion below is defined even for pathological
        // delta / res (the !>= form also catches NaN)
        if ( !( values >= 1.0f ) )
        {
            values = 1.0f;
        }
        else if ( values > 4294967040.0f )      // largest float below 2^32
        {
            values = 4294967040.0f;
        }

        return (long) Math.ceil( values );
    }

    /**
     * Quantizes a float for the wire: the exact two-rounding float32
     * arithmetic STANDARD.md pins. Java evaluates float expressions with
     * strict IEEE-754 semantics and never contracts a multiply and add into
     * a fused multiply-add on its own (Math.fma is opt-in and deliberately
     * absent here), so each statement below is one float32 rounding.
     * The integer clamp after the floor is normative (STANDARD.md, schema#109).
     * Returns the wire code as an unsigned 32-bit value in an int.
     */
    static int compressedFloatWriteCode( float value, float min, float delta, long maxIntegerValue )
    {
        // writing a non-finite value (NaN, +/-Inf) is non-conforming — assert in
        // debug. In release the clamp below keeps the integer conversion defined.
        assert value - value == 0.0f;

        // clamp with the !>= / !<= form so a NaN value is forced into range
        float normalizedValue = ( value - min ) / delta;
        if ( !( normalizedValue >= 0.0f ) )
        {
            normalizedValue = 0.0f;
        }
        else if ( !( normalizedValue <= 1.0f ) )
        {
            normalizedValue = 1.0f;
        }

        // two roundings, not one: the product rounds to float32 BEFORE 0.5 is
        // added, and the sum rounds to float32 before the floor
        float scaled = normalizedValue * (float) maxIntegerValue;
        float sum = scaled + 0.5f;
        long integerValue = (long) Math.floor( (double) sum );

        // the normative integer clamp: once maxIntegerValue >= 2^23 the float32
        // ulp at the top of the range reaches 1, so the rounded sum can exceed
        // maxIntegerValue itself
        if ( integerValue > maxIntegerValue )
        {
            integerValue = maxIntegerValue;
        }

        return (int) integerValue;
    }

    /**
     * Reconstructs a float from its wire code: float32 with every step
     * rounding — the quotient rounds, the product rounds BEFORE min is
     * added, and the sum rounds. No widening, no fused multiply-add.
     */
    static float compressedFloatReadValue( int integerValue, long maxIntegerValue, float delta, float min )
    {
        float normalizedValue = (float) Integer.toUnsignedLong( integerValue ) / (float) maxIntegerValue;
        float scaledValue = normalizedValue * delta;
        return scaledValue + min;
    }

    /*
        The whole-unit capacity checks of serialize_fixed: the reference reads
        signedness off the storage type, which Java cannot express — the format
        is read as signed exactly when min < 0, and the signedness never
        reaches the wire: for the same bounds, signed and unsigned storage
        produce identical bytes. Debug-assert material only.
    */

    static boolean fixedBoundsFit( int integerBits, long minUnits, long maxUnits )
    {
        if ( minUnits < 0 )
        {
            if ( integerBits < 65 && minUnits < -( 1L << ( integerBits - 1 ) ) )
            {
                return false;
            }
            if ( integerBits < 64 && maxUnits > ( 1L << ( integerBits - 1 ) ) - 1 )
            {
                return false;
            }
        }
        else if ( integerBits < 64 && Long.compareUnsigned( maxUnits, ( 1L << integerBits ) - 1 ) > 0 )
        {
            return false;
        }
        return true;
    }
}
