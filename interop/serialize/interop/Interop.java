package serialize.interop;

import serialize.BitStream;
import serialize.BoolRef;
import serialize.DoubleRef;
import serialize.FloatRef;
import serialize.Int128Value;
import serialize.IntRef;
import serialize.LongRef;
import serialize.ReadStream;
import serialize.Ref;
import serialize.UInt128Value;
import serialize.WriteStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Java half of the cross language interop harness.
 *
 * Its twin is {@code interop/interop.cpp}, built in CI against the real C++
 * serialize library at the release {@code .github/workflows/ci.yml} pins. The two
 * halves run head to head on every push and pull request: each writes the boundary
 * message, the two files must be byte identical, and each must decode the other's
 * file to the exact values and re-encode it to the exact bytes.
 *
 * <pre>
 *   make interop MODE=write  FILE=/tmp/java.bin
 *   make interop MODE=read   FILE=/tmp/cpp.bin
 *   make interop MODE=refuse FILE=/tmp/cpp.bin
 * </pre>
 *
 * THE MESSAGE is the boundary set: every operation STANDARD.md defines, at the
 * values where implementations disagree. Zero bit ranges on all three ranged
 * widths and on fixed point; the domain edges of int, int64, int128 and
 * int_relative; the maximum widths of bits, uint128 and the four group fixed point
 * path; both sides of the alignment rule, including the align inside a zero length
 * bytes; empty and full strings; and the wide string cases the surrogate rule
 * governs, up to the largest code unit.
 *
 * WHAT IT DELIBERATELY DOES NOT CARRY: a NaN payload. STANDARD.md's bit
 * transparency claim covers it, but a NaN's payload bits do not survive every
 * language's float type on the way to the wire, so a difference here would say
 * nothing about the wire format. This port pins its own NaN patterns in its own
 * suite, where the claim can be tested honestly.
 *
 * Any change to the sequence below must be mirrored in {@code interop/interop.cpp},
 * and never changes the wire format.
 */
public final class Interop
{
    private Interop() {}

    // -----------------------------------------------------------------------
    // the sequence, mirrored field for field from interop/interop.cpp

    // raw bit groups, every width boundary
    private static final int[] BITS_WIDTHS = { 1, 1, 7, 31, 32, 32, 33, 64, 64 };
    private static final long[] BITS_VALUES =
    {
        1,                          // the minimum width, at its maximum value
        0,                          // and at its minimum
        0x7F,                       // a sub-byte width, all ones
        0x7FFFFFFF,                 // one below the single group maximum
        0xFFFFFFFFL,                // the widest single group, all ones
        0,                          // and all zeros
        0x1FFFFFFFFL,               // the first width past the 32 bit split
        0xFFFFFFFFFFFFFFFFL,        // the maximum width, all ones
        0,                          // and all zeros
    };

    private static final int[] UINT8_VALUES = { 0x00, 0xFF };
    private static final int[] UINT16_VALUES = { 0x0000, 0xFFFF };
    private static final int[] UINT32_VALUES = { 0x00000000, 0xFFFFFFFF };
    private static final long[] UINT64_VALUES = { 0L, 0xFFFFFFFFFFFFFFFFL };

    private static UInt128Value[] uint128Values()
    {
        return new UInt128Value[]
        {
            UInt128Value.ZERO,
            UInt128Value.ZERO.not(),                                            // all ones
            new UInt128Value( 0x0123456789ABCDEFL, 0x0FEDCBA987654321L ),
        };
    }

    // ranged 32 bit integers: min, max, value
    private static final int[] INT_MIN =
        { 42, -100, -100, Integer.MIN_VALUE, Integer.MIN_VALUE, -100 };
    private static final int[] INT_MAX =
        { 42, +100, +100, Integer.MAX_VALUE, Integer.MAX_VALUE, +100 };
    private static final int[] INT_VALUE =
    {
        42,                     // degenerate: zero bits, mid sequence
        -100,                   // the bottom of the range
        +100,                   // the top of the range
        Integer.MIN_VALUE,      // the full domain, 32 bits on the wire
        Integer.MAX_VALUE,
        -37,                    // a live field after the degenerate one
    };

    private static final long[] INT64_MIN =
        { 10000000000L, -5000000000L, -5000000000L, Long.MIN_VALUE, Long.MIN_VALUE };
    private static final long[] INT64_MAX =
        { 10000000000L, +5000000000L, +5000000000L, Long.MAX_VALUE, Long.MAX_VALUE };
    private static final long[] INT64_VALUE =
    {
        10000000000L,           // degenerate, with bounds past 2^32
        -5000000000L,           // a range wider than 32 bits, bottom
        +5000000000L,           // and top
        Long.MIN_VALUE,         // the full domain, 64 bits on the wire
        Long.MAX_VALUE,
    };

    // 2^100 + 7: a degenerate bound no 64 bit path can carry
    private static Int128Value int128Degenerate()
    {
        return Int128Value.fromLong( 1 ).shiftLeft( 100 ).add( Int128Value.fromLong( 7 ) );
    }

    private static Int128Value[] int128Bounds( boolean minimum )
    {
        Int128Value degenerate = int128Degenerate();
        return new Int128Value[]
        {
            degenerate,
            // bounds inside the 64 bit domain: the bytes are identical to serializeInt64 here
            Int128Value.fromLong( minimum ? -5000000000L : +5000000000L ),
            minimum ? Int128Value.MIN_VALUE : Int128Value.MAX_VALUE,
            minimum ? Int128Value.MIN_VALUE : Int128Value.MAX_VALUE,
        };
    }

    private static Int128Value[] int128Values()
    {
        return new Int128Value[]
        {
            int128Degenerate(),
            Int128Value.fromLong( 5000000000L ),
            Int128Value.MIN_VALUE,
            Int128Value.MAX_VALUE,
        };
    }

    // int_relative: every tier at both ends, and the domain edges
    private static final int[] RELATIVE_PREVIOUS =
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2147483646, 0 };
    private static final int[] RELATIVE_CURRENT =
    {
        1,              // one-bit
        2, 6,           // bounded-3, both ends
        7, 23,          // bounded-5
        24, 280,        // bounded-9
        281, 4377,      // bounded-13
        4378, 69914,    // bounded-17
        69915,          // absolute, at its smallest difference
        2147483647,     // one-bit, at the top of the domain
        2147483647,     // absolute, at the top of the domain
    };

    // floats are given as bit patterns, so no decimal literal is parsed twice
    private static final int[] FLOAT_BITS =
    {
        0x00000000,     // +0
        0x80000000,     // -0
        0x7F800000,     // +infinity
        0xFF800000,     // -infinity
        0x7F7FFFFF,     // the largest finite float32
        0x00800000,     // the smallest normal
        0x00000001,     // the smallest subnormal
        0x3F800000,     // 1.0f
        0xBF800000,     // -1.0f
    };

    private static final long[] DOUBLE_BITS =
    {
        0x0000000000000000L,    // +0
        0x8000000000000000L,    // -0
        0x7FF0000000000000L,    // +infinity
        0xFFF0000000000000L,    // -infinity
        0x7FEFFFFFFFFFFFFFL,    // the largest finite float64
        0x0010000000000000L,    // the smallest normal
        0x0000000000000001L,    // the smallest subnormal
        0x3FF0000000000000L,    // 1.0
        0xBFF0000000000000L,    // -1.0
    };

    private static final float[] CF_VALUE =
    {
        0.0f,           // the bottom of the range: integer 0
        10.0f,          // the top: the maximum integer
        0.005f,         // between quanta: 1 under float32, 0 widened
        0.025f,         // between quanta: 3 vs 2
        0.105f,         // between quanta: 11 vs 10
        9.995f,         // between quanta: 1000 vs 999
        -100.0f,        // the bottom of a range with a non-zero min
        -42.573f,       // off quantum over a non-zero min
        8388609.0f,     // clamp witness A (schema#109)
        16777215.0f,    // clamp witness B
        0.0f,           // a one bit field, both codes
        1.0f,
    };
    private static final float[] CF_MIN =
        { 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -100.0f, -100.0f, 0.0f, 0.0f, 0.0f, 0.0f };
    private static final float[] CF_MAX =
        { 10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 100.0f, 100.0f, 8388609.0f, 16777215.0f, 1.0f, 1.0f };
    private static final float[] CF_RES =
        { 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 1.0f, 1.0f, 1.0f, 1.0f };

    // a zero length block first: the align happens anyway
    private static final int[] BYTES_LENGTH = { 0, 8, 8, 1 };
    private static final int[] BYTES_FILL = { 0x00, 0x00, 0xFF, 0x5A };

    private static final int STRING_BUFFER_SIZE = 16;
    private static final String[] STRINGS =
    {
        "",                                 // empty
        "0123456789abcde",                  // fifteen bytes: the most buffer size 16 carries
        "\u043C\u0438\u0440",               // six UTF-8 bytes, as explicit code points so no
                                            // source file encoding can reach the wire
    };

    private static final int WSTRING_BUFFER_SIZE = 8;
    private static final String[] WIDE_STRINGS =
    {
        "",                                 // empty
        "\u043C\u0438\u0440",               // basic plane
        "\uE000",                           // the first code unit above the surrogate block
        "\uFFFF",                           // the largest code unit there is
        "A\uD83D\uDE00B",                    // U+1F600 as its surrogate pair: four code units
        "abcdefg",                          // seven code units, the most buffer size 8 carries
    };

    // -----------------------------------------------------------------------
    // the message

    /**
     * Holders for every field. The raw bit groups keep both a narrow and a wide
     * holder per entry, because the port splits raw bits at 32 where the wire does
     * not; only the one the width selects is ever used.
     */
    private static final class Data
    {
        final IntRef[] bitsNarrow = new IntRef[BITS_WIDTHS.length];
        final LongRef[] bitsWide = new LongRef[BITS_WIDTHS.length];
        final BoolRef[] bools = { new BoolRef(), new BoolRef() };
        final IntRef[] uint8 = new IntRef[UINT8_VALUES.length];
        final IntRef[] uint16 = new IntRef[UINT16_VALUES.length];
        final IntRef[] uint32 = new IntRef[UINT32_VALUES.length];
        final LongRef[] uint64 = new LongRef[UINT64_VALUES.length];
        final List<Ref<UInt128Value>> uint128 = new ArrayList<>();
        final IntRef[] ints = new IntRef[INT_VALUE.length];
        final LongRef[] int64s = new LongRef[INT64_VALUE.length];
        final List<Ref<Int128Value>> int128s = new ArrayList<>();
        final LongRef fixedQ8_8Min = new LongRef();
        final LongRef fixedQ8_8Max = new LongRef();
        final LongRef fixedQ16_16Degenerate = new LongRef();
        final LongRef fixedQ48_16Min = new LongRef();
        final LongRef fixedQ48_16Max = new LongRef();
        final Ref<Int128Value> fixedQ112_16Max = new Ref<>( Int128Value.ZERO );
        final Ref<Int128Value> fixedQ64_64Degenerate = new Ref<>( Int128Value.ZERO );
        final Ref<Int128Value> fixedQ64_64Max = new Ref<>( Int128Value.ZERO );
        final IntRef[] relative = new IntRef[RELATIVE_CURRENT.length];
        final FloatRef[] floats = new FloatRef[FLOAT_BITS.length];
        final DoubleRef[] doubles = new DoubleRef[DOUBLE_BITS.length];
        final FloatRef[] compressedFloats = new FloatRef[CF_VALUE.length];
        final IntRef filler = new IntRef();
        final byte[][] bytes = new byte[BYTES_LENGTH.length][];
        final List<Ref<String>> strings = new ArrayList<>();
        final List<Ref<String>> wideStrings = new ArrayList<>();

        Data()
        {
            for ( int i = 0; i < BITS_WIDTHS.length; i++ )
            {
                bitsNarrow[i] = new IntRef();
                bitsWide[i] = new LongRef();
            }
            for ( int i = 0; i < UINT8_VALUES.length; i++ )  { uint8[i] = new IntRef(); }
            for ( int i = 0; i < UINT16_VALUES.length; i++ ) { uint16[i] = new IntRef(); }
            for ( int i = 0; i < UINT32_VALUES.length; i++ ) { uint32[i] = new IntRef(); }
            for ( int i = 0; i < UINT64_VALUES.length; i++ ) { uint64[i] = new LongRef(); }
            for ( int i = 0; i < 3; i++ ) { uint128.add( new Ref<>( UInt128Value.ZERO ) ); }
            for ( int i = 0; i < INT_VALUE.length; i++ ) { ints[i] = new IntRef(); }
            for ( int i = 0; i < INT64_VALUE.length; i++ ) { int64s[i] = new LongRef(); }
            for ( int i = 0; i < 4; i++ ) { int128s.add( new Ref<>( Int128Value.ZERO ) ); }
            for ( int i = 0; i < RELATIVE_CURRENT.length; i++ ) { relative[i] = new IntRef(); }
            for ( int i = 0; i < FLOAT_BITS.length; i++ ) { floats[i] = new FloatRef(); }
            for ( int i = 0; i < DOUBLE_BITS.length; i++ ) { doubles[i] = new DoubleRef(); }
            for ( int i = 0; i < CF_VALUE.length; i++ ) { compressedFloats[i] = new FloatRef(); }
            for ( int i = 0; i < BYTES_LENGTH.length; i++ ) { bytes[i] = new byte[BYTES_LENGTH[i]]; }
            for ( int i = 0; i < STRINGS.length; i++ ) { strings.add( new Ref<>( "" ) ); }
            for ( int i = 0; i < WIDE_STRINGS.length; i++ ) { wideStrings.add( new Ref<>( "" ) ); }
        }
    }

    /** The write side: every holder carries the value the sequence pins. */
    private static Data boundaryData()
    {
        Data data = new Data();
        for ( int i = 0; i < BITS_WIDTHS.length; i++ )
        {
            data.bitsNarrow[i].value = (int) BITS_VALUES[i];
            data.bitsWide[i].value = BITS_VALUES[i];
        }
        data.bools[0].value = true;
        data.bools[1].value = false;
        for ( int i = 0; i < UINT8_VALUES.length; i++ )  { data.uint8[i].value = UINT8_VALUES[i]; }
        for ( int i = 0; i < UINT16_VALUES.length; i++ ) { data.uint16[i].value = UINT16_VALUES[i]; }
        for ( int i = 0; i < UINT32_VALUES.length; i++ ) { data.uint32[i].value = UINT32_VALUES[i]; }
        for ( int i = 0; i < UINT64_VALUES.length; i++ ) { data.uint64[i].value = UINT64_VALUES[i]; }
        UInt128Value[] unsigned = uint128Values();
        for ( int i = 0; i < unsigned.length; i++ ) { data.uint128.get( i ).value = unsigned[i]; }
        for ( int i = 0; i < INT_VALUE.length; i++ ) { data.ints[i].value = INT_VALUE[i]; }
        for ( int i = 0; i < INT64_VALUE.length; i++ ) { data.int64s[i].value = INT64_VALUE[i]; }
        Int128Value[] signed = int128Values();
        for ( int i = 0; i < signed.length; i++ ) { data.int128s.get( i ).value = signed[i]; }
        data.fixedQ8_8Min.value = -100L * 256;                              // the bottom of the range
        data.fixedQ8_8Max.value = 100L * 256;                               // the top
        data.fixedQ16_16Degenerate.value = 7L * 65536;                      // min == max: zero bits
        data.fixedQ48_16Min.value = -100000L * 65536;                       // 34 bits on the wire
        data.fixedQ48_16Max.value = 100000L * 65536;
        data.fixedQ112_16Max.value = Int128Value.fromLong( 144115188075855872L ).shiftLeft( 16 );
        data.fixedQ64_64Degenerate.value = Int128Value.fromLong( 5 ).shiftLeft( 64 );
        data.fixedQ64_64Max.value = Int128Value.fromLong( Long.MAX_VALUE ).shiftLeft( 64 );
        for ( int i = 0; i < RELATIVE_CURRENT.length; i++ ) { data.relative[i].value = RELATIVE_CURRENT[i]; }
        for ( int i = 0; i < FLOAT_BITS.length; i++ ) { data.floats[i].value = Float.intBitsToFloat( FLOAT_BITS[i] ); }
        for ( int i = 0; i < DOUBLE_BITS.length; i++ ) { data.doubles[i].value = Double.longBitsToDouble( DOUBLE_BITS[i] ); }
        for ( int i = 0; i < CF_VALUE.length; i++ ) { data.compressedFloats[i].value = CF_VALUE[i]; }
        data.filler.value = 5;
        for ( int i = 0; i < BYTES_LENGTH.length; i++ ) { Arrays.fill( data.bytes[i], (byte) BYTES_FILL[i] ); }
        for ( int i = 0; i < STRINGS.length; i++ ) { data.strings.get( i ).value = STRINGS[i]; }
        for ( int i = 0; i < WIDE_STRINGS.length; i++ ) { data.wideStrings.get( i ).value = WIDE_STRINGS[i]; }
        return data;
    }

    /** The message, operation for operation. The chain stops at the first refusal. */
    private static boolean interopSerialize( BitStream stream, Data d )
    {
        // ----- raw bit groups
        for ( int i = 0; i < BITS_WIDTHS.length; i++ )
        {
            int width = BITS_WIDTHS[i];
            boolean ok = width <= 32 ? stream.serializeBits( d.bitsNarrow[i], width )
                                     : stream.serializeBits64( d.bitsWide[i], width );
            if ( !ok ) { return false; }
        }

        // ----- bool, both codes
        for ( BoolRef ref : d.bools )
        {
            if ( !stream.serializeBool( ref ) ) { return false; }
        }

        // both sides of the alignment rule: the stream is unaligned here, so the
        // first align pads and the second must write nothing at all
        if ( !stream.serializeAlign() ) { return false; }
        if ( !stream.serializeAlign() ) { return false; }

        // ----- the fixed width unsigned helpers, at their domain edges
        for ( IntRef ref : d.uint8 )  { if ( !stream.serializeUint8( ref ) )  { return false; } }
        for ( IntRef ref : d.uint16 ) { if ( !stream.serializeUint16( ref ) ) { return false; } }
        for ( IntRef ref : d.uint32 ) { if ( !stream.serializeUint32( ref ) ) { return false; } }
        for ( LongRef ref : d.uint64 ) { if ( !stream.serializeUint64( ref ) ) { return false; } }
        for ( Ref<UInt128Value> ref : d.uint128 ) { if ( !stream.serializeUint128( ref ) ) { return false; } }

        // ----- ranged integers
        for ( int i = 0; i < INT_VALUE.length; i++ )
        {
            if ( !stream.serializeInt( d.ints[i], INT_MIN[i], INT_MAX[i] ) ) { return false; }
        }
        for ( int i = 0; i < INT64_VALUE.length; i++ )
        {
            if ( !stream.serializeInt64( d.int64s[i], INT64_MIN[i], INT64_MAX[i] ) ) { return false; }
        }
        Int128Value[] lower = int128Bounds( true );
        Int128Value[] upper = int128Bounds( false );
        for ( int i = 0; i < lower.length; i++ )
        {
            if ( !stream.serializeInt128( d.int128s.get( i ), lower[i], upper[i] ) ) { return false; }
        }

        // ----- fixed point, at the ends of its ranges and degenerate on two storage widths
        if ( !stream.serializeAlign() ) { return false; }
        if ( !stream.serializeFixed( d.fixedQ8_8Min, 8, 8, -100, +100 ) ) { return false; }
        if ( !stream.serializeFixed( d.fixedQ8_8Max, 8, 8, -100, +100 ) ) { return false; }
        if ( !stream.serializeFixed( d.fixedQ16_16Degenerate, 16, 16, 7, 7 ) ) { return false; }
        if ( !stream.serializeFixed( d.fixedQ48_16Min, 48, 16, -100000, +100000 ) ) { return false; }
        if ( !stream.serializeFixed( d.fixedQ48_16Max, 48, 16, -100000, +100000 ) ) { return false; }
        if ( !stream.serializeFixed128( d.fixedQ112_16Max, 112, 16, -144115188075855872L, +144115188075855872L ) ) { return false; }
        if ( !stream.serializeFixed128( d.fixedQ64_64Degenerate, 64, 64, 5, 5 ) ) { return false; }
        if ( !stream.serializeFixed128( d.fixedQ64_64Max, 64, 64, Long.MIN_VALUE, Long.MAX_VALUE ) ) { return false; }

        // ----- int_relative
        for ( int i = 0; i < RELATIVE_CURRENT.length; i++ )
        {
            if ( !stream.serializeIntRelative( RELATIVE_PREVIOUS[i], d.relative[i] ) ) { return false; }
        }

        // ----- float and double, bit transparent at the domain edges
        for ( FloatRef ref : d.floats ) { if ( !stream.serializeFloat( ref ) ) { return false; } }
        for ( DoubleRef ref : d.doubles ) { if ( !stream.serializeDouble( ref ) ) { return false; } }

        // ----- compressed_float
        for ( int i = 0; i < CF_VALUE.length; i++ )
        {
            if ( !stream.serializeCompressedFloat( d.compressedFloats[i], CF_MIN[i], CF_MAX[i], CF_RES[i] ) ) { return false; }
        }

        // ----- bytes. The three bit filler leaves the stream unaligned, so the
        // align that begins the first block -- a ZERO LENGTH one -- is load bearing.
        if ( !stream.serializeBits( d.filler, 3 ) ) { return false; }
        for ( int i = 0; i < BYTES_LENGTH.length; i++ )
        {
            if ( !stream.serializeBytes( d.bytes[i], BYTES_LENGTH[i] ) ) { return false; }
        }

        // ----- string: empty, full, and multi-byte UTF-8
        for ( Ref<String> ref : d.strings )
        {
            if ( !stream.serializeString( ref, STRING_BUFFER_SIZE ) ) { return false; }
        }

        // ----- wstring: empty, basic plane, the code unit boundaries, a pair, full
        for ( Ref<String> ref : d.wideStrings )
        {
            if ( !stream.serializeWideString( ref, WSTRING_BUFFER_SIZE ) ) { return false; }
        }

        return true;
    }

    /**
     * What a conforming reader recovers. Everything is exact except the compressed
     * floats, which are lossy by construction: the reader returns the nearest
     * quantum, so they are compared within one resolution step. Floats compare by
     * RAW BIT PATTERN -- a value comparison cannot see -0.0.
     */
    private static List<String> interopCheck( Data d )
    {
        List<String> problems = new ArrayList<>();
        Data expected = boundaryData();

        for ( int i = 0; i < BITS_WIDTHS.length; i++ )
        {
            long actual = BITS_WIDTHS[i] <= 32 ? ( d.bitsNarrow[i].value & 0xFFFFFFFFL ) : d.bitsWide[i].value;
            long want = BITS_WIDTHS[i] <= 32 ? ( (int) BITS_VALUES[i] & 0xFFFFFFFFL ) : BITS_VALUES[i];
            if ( actual != want ) { problems.add( "bits[" + i + "]: got " + actual + ", expected " + want ); }
        }
        if ( !d.bools[0].value ) { problems.add( "bool[0]" ); }
        if ( d.bools[1].value ) { problems.add( "bool[1]" ); }
        for ( int i = 0; i < UINT8_VALUES.length; i++ )
        {
            if ( d.uint8[i].value != UINT8_VALUES[i] ) { problems.add( "uint8[" + i + "]" ); }
        }
        for ( int i = 0; i < UINT16_VALUES.length; i++ )
        {
            if ( d.uint16[i].value != UINT16_VALUES[i] ) { problems.add( "uint16[" + i + "]" ); }
        }
        for ( int i = 0; i < UINT32_VALUES.length; i++ )
        {
            if ( d.uint32[i].value != UINT32_VALUES[i] ) { problems.add( "uint32[" + i + "]" ); }
        }
        for ( int i = 0; i < UINT64_VALUES.length; i++ )
        {
            if ( d.uint64[i].value != UINT64_VALUES[i] ) { problems.add( "uint64[" + i + "]" ); }
        }
        for ( int i = 0; i < d.uint128.size(); i++ )
        {
            if ( !d.uint128.get( i ).value.equals( expected.uint128.get( i ).value ) ) { problems.add( "uint128[" + i + "]" ); }
        }
        for ( int i = 0; i < INT_VALUE.length; i++ )
        {
            if ( d.ints[i].value != INT_VALUE[i] ) { problems.add( "int[" + i + "]" ); }
        }
        for ( int i = 0; i < INT64_VALUE.length; i++ )
        {
            if ( d.int64s[i].value != INT64_VALUE[i] ) { problems.add( "int64[" + i + "]" ); }
        }
        for ( int i = 0; i < d.int128s.size(); i++ )
        {
            if ( !d.int128s.get( i ).value.equals( expected.int128s.get( i ).value ) ) { problems.add( "int128[" + i + "]" ); }
        }
        if ( d.fixedQ8_8Min.value != expected.fixedQ8_8Min.value ) { problems.add( "fixed q8.8 min" ); }
        if ( d.fixedQ8_8Max.value != expected.fixedQ8_8Max.value ) { problems.add( "fixed q8.8 max" ); }
        if ( d.fixedQ16_16Degenerate.value != expected.fixedQ16_16Degenerate.value ) { problems.add( "fixed q16.16 degenerate" ); }
        if ( d.fixedQ48_16Min.value != expected.fixedQ48_16Min.value ) { problems.add( "fixed q48.16 min" ); }
        if ( d.fixedQ48_16Max.value != expected.fixedQ48_16Max.value ) { problems.add( "fixed q48.16 max" ); }
        if ( !d.fixedQ112_16Max.value.equals( expected.fixedQ112_16Max.value ) ) { problems.add( "fixed q112.16 max" ); }
        if ( !d.fixedQ64_64Degenerate.value.equals( expected.fixedQ64_64Degenerate.value ) ) { problems.add( "fixed q64.64 degenerate" ); }
        if ( !d.fixedQ64_64Max.value.equals( expected.fixedQ64_64Max.value ) ) { problems.add( "fixed q64.64 max" ); }
        if ( d.filler.value != expected.filler.value ) { problems.add( "filler" ); }
        for ( int i = 0; i < RELATIVE_CURRENT.length; i++ )
        {
            if ( d.relative[i].value != RELATIVE_CURRENT[i] ) { problems.add( "int_relative[" + i + "]" ); }
        }
        for ( int i = 0; i < FLOAT_BITS.length; i++ )
        {
            if ( Float.floatToRawIntBits( d.floats[i].value ) != FLOAT_BITS[i] ) { problems.add( "float[" + i + "]" ); }
        }
        for ( int i = 0; i < DOUBLE_BITS.length; i++ )
        {
            if ( Double.doubleToRawLongBits( d.doubles[i].value ) != DOUBLE_BITS[i] ) { problems.add( "double[" + i + "]" ); }
        }
        for ( int i = 0; i < CF_VALUE.length; i++ )
        {
            if ( !( Math.abs( d.compressedFloats[i].value - CF_VALUE[i] ) <= CF_RES[i] ) )
            {
                problems.add( "compressed_float[" + i + "]: got " + d.compressedFloats[i].value );
            }
        }
        for ( int i = 0; i < BYTES_LENGTH.length; i++ )
        {
            if ( !Arrays.equals( d.bytes[i], expected.bytes[i] ) ) { problems.add( "bytes[" + i + "]" ); }
        }
        for ( int i = 0; i < STRINGS.length; i++ )
        {
            if ( !STRINGS[i].equals( d.strings.get( i ).value ) ) { problems.add( "string[" + i + "]: got " + d.strings.get( i ).value ); }
        }
        for ( int i = 0; i < WIDE_STRINGS.length; i++ )
        {
            if ( !WIDE_STRINGS[i].equals( d.wideStrings.get( i ).value ) ) { problems.add( "wstring[" + i + "]" ); }
        }
        return problems;
    }

    // -----------------------------------------------------------------------
    // the three modes

    private static final int BUFFER_BYTES = 1024;

    /**
     * The read allocation contract: this port's reader requires the array to extend
     * eight bytes past the data (BitReader). The slack is zero filled, so nothing in
     * it can reach a decoded value -- STANDARD.md ruled that bytes past the stream
     * end are never interpreted, and a truncated prefix must be refused for the bits
     * it does not have, not accepted from what follows it.
     */
    private static byte[] withSlack( byte[] input, int length )
    {
        byte[] buffer = new byte[length + 8];
        System.arraycopy( input, 0, buffer, 0, length );
        return buffer;
    }

    private static byte[] encode( Data data )
    {
        WriteStream stream = new WriteStream( new byte[BUFFER_BYTES], BUFFER_BYTES );
        if ( !interopSerialize( stream, data ) )
        {
            throw new IllegalStateException( "interop java: serialize failed" );
        }
        stream.flush();
        return Arrays.copyOf( stream.getData(), (int) stream.getBytesProcessed() );
    }

    private static void write( String path ) throws IOException
    {
        byte[] bytes = encode( boundaryData() );
        Files.write( Path.of( path ), bytes );
        System.out.println( "interop java: wrote " + bytes.length + " bytes to " + path );
    }

    private static void read( String path ) throws IOException
    {
        byte[] input = Files.readAllBytes( Path.of( path ) );
        Data data = new Data();
        if ( !interopSerialize( new ReadStream( withSlack( input, input.length ), input.length ), data ) )
        {
            throw new IllegalStateException( "interop java: could not decode " + path );
        }
        List<String> problems = interopCheck( data );
        if ( !problems.isEmpty() )
        {
            throw new IllegalStateException( "interop java: " + path + " decoded to unexpected values: " + problems );
        }
        // re-encode what was decoded: the bytes must be identical to the input
        if ( !Arrays.equals( encode( data ), input ) )
        {
            throw new IllegalStateException( "interop java: re-encoded bytes differ from " + path );
        }
        System.out.println( "interop java: decoded and re-encoded " + input.length + " bytes from " + path + ", byte identical" );
    }

    /**
     * The hostile half: every proper prefix of a valid stream is a truncated
     * stream, and a conforming reader refuses every one of them without throwing.
     */
    private static void refuse( String path ) throws IOException
    {
        byte[] input = Files.readAllBytes( Path.of( path ) );
        for ( int length = 0; length < input.length; length++ )
        {
            byte[] truncated = withSlack( input, length );
            boolean accepted;
            try
            {
                accepted = interopSerialize( new ReadStream( truncated, length ), new Data() );
            }
            catch ( RuntimeException error )
            {
                throw new IllegalStateException( "interop java refuse: the " + length + " byte prefix of " + path + " THREW", error );
            }
            if ( accepted )
            {
                throw new IllegalStateException( "interop java refuse: the " + length + " byte prefix of " + path + " was ACCEPTED" );
            }
        }
        System.out.println( "interop java: refused all " + input.length + " truncated prefixes of " + path );
    }

    public static void main( String[] args ) throws IOException
    {
        if ( args.length != 2 )
        {
            System.err.println( "usage: Interop write|read|refuse <file>" );
            System.exit( 2 );
        }
        switch ( args[0] )
        {
            case "write":
                write( args[1] );
                break;
            case "read":
                read( args[1] );
                break;
            case "refuse":
                refuse( args[1] );
                break;
            default:
                System.err.println( "usage: Interop write|read|refuse <file>" );
                System.exit( 2 );
                break;
        }
    }
}
