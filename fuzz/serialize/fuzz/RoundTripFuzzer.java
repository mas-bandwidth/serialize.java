package serialize.fuzz;

import java.math.BigInteger;

import serialize.BitStream;
import serialize.BoolRef;
import serialize.DoubleRef;
import serialize.FloatRef;
import serialize.Int128Value;
import serialize.IntRef;
import serialize.LongRef;
import serialize.MeasureStream;
import serialize.ReadStream;
import serialize.Ref;
import serialize.Refusal;
import serialize.SerializeUtil;
import serialize.UInt128Value;
import serialize.WriteStream;

/**
 * Jazzer target two: the DIFFERENTIAL ROUND TRIP, the Java counterpart of the
 * reference's FuzzRoundTrip (fuzz.cpp, pass 3).
 *
 * Values generated from the input are written with a {@link WriteStream},
 * measured with a {@link MeasureStream}, and read back with a
 * {@link ReadStream}; one serialize body drives all three, as a message's
 * would. It traps on any write/read asymmetry (a value that does not come
 * back exactly, or a read of the writer's own bytes that refuses), on a
 * measure that counts fewer bits than were written, on a read that does not
 * end exactly where the writer stopped, and on a stream one byte longer than
 * the writer reported whose end is not refused as trailing_data.
 *
 * Input layout, as the reference: the first {@link HostileReadFuzzer#NUM_OPS}
 * bytes are the op program, the rest is the value pool. Every case consumes
 * the same pool bytes writing and reading, so each pass regenerates the exact
 * values the write pass produced.
 *
 * Run with assertions on (-ea): the write side contracts are asserts, and the
 * generated values must satisfy every one of them.
 */
public final class RoundTripFuzzer
{
    private RoundTripFuzzer() {}

    /** Worst case is ~260 bytes per op (a 241 byte bytes field plus alignment), so 32 ops fit comfortably. */
    private static final int WRITE_BUFFER_SIZE = 16 * 1024;

    /** Jazzer's entry point. */
    public static void fuzzerTestOneInput( byte[] data )
    {
        final int numOps = HostileReadFuzzer.NUM_OPS;
        if ( data.length <= numOps || data.length > numOps + HostileReadFuzzer.MAX_PAYLOAD )
        {
            return;
        }
        final int payloadBytes = data.length - numOps;

        byte[] writeBuffer = new byte[WRITE_BUFFER_SIZE];
        WriteStream writeStream = new WriteStream( writeBuffer, WRITE_BUFFER_SIZE );
        FuzzCheck.check( fuzzRoundTrip( writeStream, data, new ValuePool( data, numOps, payloadBytes ) ), "writing in-range values refused" );
        writeStream.flush();

        MeasureStream measureStream = new MeasureStream();
        FuzzCheck.check( fuzzRoundTrip( measureStream, data, new ValuePool( data, numOps, payloadBytes ) ), "measuring in-range values refused" );
        FuzzCheck.check( measureStream.getBitsProcessed() >= writeStream.getBitsProcessed(), "the measure under-measured the write" );

        final int written = (int) writeStream.getBytesProcessed();

        // the write buffer extends well past the written bytes, and the writer only ever writes zeros there
        ReadStream readStream = new ReadStream( writeBuffer, written );
        FuzzCheck.check( fuzzRoundTrip( readStream, data, new ValuePool( data, numOps, payloadBytes ) ), "reading back the writer's own bytes refused" );
        FuzzCheck.check( readStream.getBitsProcessed() == writeStream.getBitsProcessed(), "the reader and the writer disagree on the bits processed" );
        FuzzCheck.check( readStream.serializeEnd(), "the read did not end where the writer stopped" );
        FuzzCheck.check( readStream.getRefusal() == Refusal.NONE, "an accepted read reported a refusal" );

        // one byte more than the writer reported is trailing data, refused at the end
        ReadStream longerStream = new ReadStream( writeBuffer, written + 1 );
        FuzzCheck.check( fuzzRoundTrip( longerStream, data, new ValuePool( data, numOps, payloadBytes ) ), "reading the writer's bytes plus one refused before the end" );
        FuzzCheck.check( !longerStream.serializeEnd(), "a trailing byte was accepted at the end" );
        FuzzCheck.check( longerStream.getRefusal() == Refusal.TRAILING_DATA, "a trailing byte was refused for the wrong reason" );
    }

    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft( 64 );

    private static BigInteger unsignedBig( long value )
    {
        return new BigInteger( Long.toUnsignedString( value ) );
    }

    private static Int128Value int128( BigInteger value )
    {
        return new Int128Value( value.shiftRight( 64 ).longValue(), value.longValue() );
    }

    // wide fixed point: bounds ±2^60 whole units in Q112.16 give a raw range of 2^77, exercising
    // the multi group path with values that cannot fit in 64 bits
    private static final BigInteger WIDE_RAW_MIN = BigInteger.ONE.shiftLeft( 76 ).negate();
    private static final BigInteger WIDE_SPAN = BigInteger.ONE.shiftLeft( 77 ).add( BigInteger.ONE );

    // builds the expected text of case 11: random Unicode scalar values, so every length of
    // UTF-8 sequence and every plane reaches the writer. NUL and the surrogate block are not
    // scalar text and are replaced.
    private static String scalarText( ValuePool pool )
    {
        StringBuilder text = new StringBuilder();
        final int characters = pool.nextByte() % 8;
        for ( int j = 0; j < characters; j++ )
        {
            int code = Integer.remainderUnsigned( pool.nextUint32(), 0x110000 );
            if ( code == 0 || ( code >= 0xD800 && code <= 0xDFFF ) )
            {
                code = 0x7881;                                  // 碁
            }
            text.appendCodePoint( code );
        }
        return text.toString();
    }

    static boolean fuzzRoundTrip( BitStream stream, byte[] ops, ValuePool pool )
    {
        final boolean writing = stream.isWriting();
        final boolean reading = stream.isReading();

        for ( int i = 0; i < HostileReadFuzzer.NUM_OPS; i++ )
        {
            final int select = ops[i] & 15;
            final int param = ( ops[i] >> 4 ) & 15;                 // [0,15]

            switch ( select )
            {
                case 0:
                case 1:
                {
                    final int bits = ( select == 0 ) ? param + 1 : param + 17;              // [1,32]
                    final int mask = ( bits == 32 ) ? 0xFFFFFFFF : ( ( 1 << bits ) - 1 );
                    final int expected = pool.nextUint32() & mask;
                    IntRef value = new IntRef( writing ? expected : 0 );
                    if ( !stream.serializeBits( value, bits ) ) return false;
                    if ( reading ) FuzzCheck.check( value.value == expected, "bits: asymmetric round trip" );
                }
                break;

                case 2:
                {
                    final int expected = -100 + Integer.remainderUnsigned( pool.nextUint32(), 201 );
                    IntRef value = new IntRef( writing ? expected : 0 );
                    if ( !stream.serializeInt( value, -100, +100 ) ) return false;
                    if ( reading ) FuzzCheck.check( value.value == expected, "int [-100,+100]: asymmetric round trip" );
                }
                break;

                case 3:
                {
                    final int expected = pool.nextUint32();
                    IntRef value = new IntRef( writing ? expected : 0 );
                    if ( !stream.serializeInt( value, Integer.MIN_VALUE, Integer.MAX_VALUE ) ) return false;
                    if ( reading ) FuzzCheck.check( value.value == expected, "int full range: asymmetric round trip" );

                    final long expected64 = pool.nextUint64();
                    LongRef value64 = new LongRef( writing ? expected64 : 0 );
                    if ( !stream.serializeInt64( value64, Long.MIN_VALUE, Long.MAX_VALUE ) ) return false;
                    if ( reading ) FuzzCheck.check( value64.value == expected64, "int64 full range: asymmetric round trip" );

                    final long bound = (long) ( param + 1 ) << 35;                    // spans of varying width exercise bitsRequired64
                    final long span = bound * 2 + 1;
                    final long expectedRanged = -bound + Long.remainderUnsigned( pool.nextUint64(), span );
                    LongRef ranged64 = new LongRef( writing ? expectedRanged : 0 );
                    if ( !stream.serializeInt64( ranged64, -bound, +bound ) ) return false;
                    if ( reading ) FuzzCheck.check( ranged64.value == expectedRanged, "int64 ranged: asymmetric round trip" );

                    // fixed point: raw values generated uniformly inside the raw bounds must round trip exactly
                    final long fixed32Range = 2000L * 65536;                          // bounds [-1000,+1000] whole units in Q16.16
                    final long expectedFixed32 = -1000L * 65536 + Long.remainderUnsigned( Integer.toUnsignedLong( pool.nextUint32() ), fixed32Range + 1 );
                    LongRef fixed32 = new LongRef( writing ? expectedFixed32 : 0 );
                    if ( !stream.serializeFixed( fixed32, 16, 16, -1000, +1000 ) ) return false;
                    if ( reading ) FuzzCheck.check( fixed32.value == expectedFixed32, "fixed Q16.16: asymmetric round trip" );

                    final long fixed64Range = 200000000000L * 65536;                  // bounds [-1e11,+1e11] whole units in Q48.16
                    final long expectedFixed64 = -100000000000L * 65536 + Long.remainderUnsigned( pool.nextUint64(), fixed64Range + 1 );
                    LongRef fixed64 = new LongRef( writing ? expectedFixed64 : 0 );
                    if ( !stream.serializeFixed( fixed64, 48, 16, -100000000000L, +100000000000L ) ) return false;
                    if ( reading ) FuzzCheck.check( fixed64.value == expectedFixed64, "fixed Q48.16: asymmetric round trip" );

                    final long widePoolHigh = pool.nextUint64();
                    final long widePoolLow = pool.nextUint64();
                    final BigInteger widePool = unsignedBig( widePoolHigh ).multiply( TWO_64 ).add( unsignedBig( widePoolLow ) );
                    final Int128Value expectedWide = int128( WIDE_RAW_MIN.add( widePool.mod( WIDE_SPAN ) ) );
                    Ref<Int128Value> wide = new Ref<>( writing ? expectedWide : Int128Value.ZERO );
                    if ( !stream.serializeFixed128( wide, 112, 16, -1152921504606846976L, +1152921504606846976L ) ) return false;
                    if ( reading ) FuzzCheck.check( expectedWide.equals( wide.value ), "fixed Q112.16: asymmetric round trip" );

                    // ranged and full 128 bit integers, which the reference's fuzz pass reaches only through
                    // fixed point: the bound width varying by param walks the group structures
                    final BigInteger bound128 = BigInteger.ONE.shiftLeft( 20 + param * 6 );                 // 2^20 .. 2^110
                    final long rangedHigh = pool.nextUint64();
                    final long rangedLow = pool.nextUint64();
                    final BigInteger rangedPool = unsignedBig( rangedHigh ).multiply( TWO_64 ).add( unsignedBig( rangedLow ) );
                    final Int128Value expectedRanged128 = int128( bound128.negate().add( rangedPool.mod( bound128.shiftLeft( 1 ).add( BigInteger.ONE ) ) ) );
                    Ref<Int128Value> ranged128 = new Ref<>( writing ? expectedRanged128 : Int128Value.ZERO );
                    if ( !stream.serializeInt128( ranged128, int128( bound128.negate() ), int128( bound128 ) ) ) return false;
                    if ( reading ) FuzzCheck.check( expectedRanged128.equals( ranged128.value ), "int128 ranged: asymmetric round trip" );

                    final Int128Value expectedFull128 = new Int128Value( pool.nextUint64(), pool.nextUint64() );
                    Ref<Int128Value> full128 = new Ref<>( writing ? expectedFull128 : Int128Value.ZERO );
                    if ( !stream.serializeInt128( full128, Int128Value.MIN_VALUE, Int128Value.MAX_VALUE ) ) return false;
                    if ( reading ) FuzzCheck.check( expectedFull128.equals( full128.value ), "int128 full range: asymmetric round trip" );
                }
                break;

                case 4:
                {
                    final boolean expected = ( pool.nextByte() & 1 ) != 0;
                    BoolRef value = new BoolRef( writing && expected );
                    if ( !stream.serializeBool( value ) ) return false;
                    if ( reading ) FuzzCheck.check( value.value == expected, "bool: asymmetric round trip" );
                }
                break;

                case 5:
                {
                    // arbitrary FINITE bit patterns, -0 and subnormals included: floats round trip bit
                    // exact. A non-finite pattern has its exponent cleared, keeping the sign and the
                    // mantissa entropy as a subnormal: writing NaN or infinity is a contract violation,
                    // and hostile non-finite bytes reach the read path in the hostile read target
                    final int rawBits = pool.nextUint32();
                    final int expectedBits = SerializeUtil.floatBitsAreFinite( rawBits ) ? rawBits : ( rawBits & 0x807FFFFF );
                    FloatRef value = new FloatRef( writing ? Float.intBitsToFloat( expectedBits ) : 0.0f );
                    if ( !stream.serializeFloat( value ) ) return false;
                    if ( reading ) FuzzCheck.check( Float.floatToRawIntBits( value.value ) == expectedBits, "float: asymmetric round trip" );
                }
                break;

                case 6:
                {
                    final long rawBits = pool.nextUint64();
                    final long expectedBits = SerializeUtil.doubleBitsAreFinite( rawBits ) ? rawBits : ( rawBits & 0x800FFFFFFFFFFFFFL );
                    DoubleRef value = new DoubleRef( writing ? Double.longBitsToDouble( expectedBits ) : 0.0 );
                    if ( !stream.serializeDouble( value ) ) return false;
                    if ( reading ) FuzzCheck.check( Double.doubleToRawLongBits( value.value ) == expectedBits, "double: asymmetric round trip" );
                }
                break;

                case 7:
                {
                    // arbitrary FINITE bit patterns: out of range values clamp into [min,max] on write,
                    // and in range values round trip within the resolution
                    final int rawBits = pool.nextUint32();
                    final int expectedBits = SerializeUtil.floatBitsAreFinite( rawBits ) ? rawBits : ( rawBits & 0x807FFFFF );
                    final float expected = Float.intBitsToFloat( expectedBits );
                    FloatRef value = new FloatRef( writing ? expected : 0.0f );
                    if ( !stream.serializeCompressedFloat( value, -10.0f, +10.0f, 0.01f ) ) return false;
                    if ( reading )
                    {
                        FuzzCheck.check( value.value >= -10.001f && value.value <= +10.001f, "compressed_float: decoded outside [min,max]" );
                        if ( expected >= -10.0f && expected <= +10.0f )
                        {
                            FuzzCheck.check( Math.abs( value.value - expected ) <= 0.011f, "compressed_float: decoded off by more than the resolution" );
                        }
                    }
                }
                break;

                case 8:
                {
                    final long expected = pool.nextUint64();
                    LongRef value = new LongRef( writing ? expected : 0 );
                    if ( !stream.serializeUint64( value ) ) return false;
                    if ( reading ) FuzzCheck.check( value.value == expected, "uint64: asymmetric round trip" );

                    final long expected128High = pool.nextUint64();
                    final long expected128Low = pool.nextUint64();
                    final UInt128Value expected128 = new UInt128Value( expected128High, expected128Low );
                    Ref<UInt128Value> value128 = new Ref<>( writing ? expected128 : UInt128Value.ZERO );
                    if ( !stream.serializeUint128( value128 ) ) return false;
                    if ( reading ) FuzzCheck.check( expected128.equals( value128.value ), "uint128: asymmetric round trip" );

                    // the raw 64 bit width, the Java surface's own primitive: [33,63]
                    final int bits = 33 + param * 2;
                    final long expectedRaw = pool.nextUint64() & ( ( 1L << bits ) - 1 );
                    LongRef raw = new LongRef( writing ? expectedRaw : 0 );
                    if ( !stream.serializeBits64( raw, bits ) ) return false;
                    if ( reading ) FuzzCheck.check( raw.value == expectedRaw, "bits64: asymmetric round trip" );
                }
                break;

                case 9:
                {
                    final int numBytes = param * 16 + 1;                    // [1,241]
                    byte[] expected = new byte[numBytes];
                    for ( int j = 0; j < numBytes; j++ )
                    {
                        expected[j] = (byte) pool.nextByte();
                    }
                    byte[] value = writing ? expected.clone() : new byte[numBytes];
                    if ( !stream.serializeBytes( value, numBytes ) ) return false;
                    if ( reading ) FuzzCheck.check( java.util.Arrays.equals( value, expected ), "bytes: asymmetric round trip" );
                }
                break;

                case 10:
                {
                    // masked to ASCII with NUL replaced: the writer's contract is well-formed text with
                    // no interior NUL (asserted), and arbitrary bytes reach the read path in the hostile target
                    final int length = pool.nextByte() % 31;
                    StringBuilder text = new StringBuilder();
                    for ( int j = 0; j < length; j++ )
                    {
                        final int c = pool.nextByte() & 0x7F;
                        text.append( c != 0 ? (char) c : ' ' );
                    }
                    final String expected = text.toString();
                    Ref<String> value = new Ref<>( writing ? expected : null );
                    if ( !stream.serializeString( value, 32 ) ) return false;
                    if ( reading ) FuzzCheck.check( expected.equals( value.value ), "string (ascii): asymmetric round trip" );
                }
                break;

                case 11:
                {
                    // seven four byte characters at most: 28 bytes, inside a 40 byte buffer
                    final String expected = scalarText( pool );
                    Ref<String> value = new Ref<>( writing ? expected : null );
                    if ( !stream.serializeString( value, 40 ) ) return false;
                    if ( reading ) FuzzCheck.check( expected.equals( value.value ), "string (unicode): asymmetric round trip" );
                }
                break;

                case 12:
                {
                    if ( !stream.serializeAlign() ) return false;
                }
                break;

                case 13:
                {
                    final int previous = param * 1000;                      // int_relative's domain starts at 0: a negative previous is caller error
                    final int expected = previous + 1 + Integer.remainderUnsigned( pool.nextUint32(), 1000000 );     // strictly greater than previous
                    IntRef value = new IntRef( writing ? expected : 0 );
                    if ( !stream.serializeIntRelative( previous, value ) ) return false;
                    if ( reading ) FuzzCheck.check( value.value == expected, "int_relative: asymmetric round trip" );
                }
                break;

                case 14:
                {
                    final int max = ( param + 1 ) * 1000;                   // ranges of varying width exercise bitsRequired
                    final int expected = Integer.remainderUnsigned( pool.nextUint32(), max + 1 );
                    IntRef value = new IntRef( writing ? expected : 0 );
                    if ( !stream.serializeInt( value, 0, max ) ) return false;
                    if ( reading ) FuzzCheck.check( value.value == expected, "int [0,max]: asymmetric round trip" );
                }
                break;

                case 15:
                default:
                {
                    final int expected8 = pool.nextByte();
                    final int expected16 = pool.nextUint32() & 0xFFFF;
                    final int expected32 = pool.nextUint32();
                    IntRef value8 = new IntRef( writing ? expected8 : 0 );
                    IntRef value16 = new IntRef( writing ? expected16 : 0 );
                    IntRef value32 = new IntRef( writing ? expected32 : 0 );
                    if ( !stream.serializeUint8( value8 ) ) return false;
                    if ( !stream.serializeUint16( value16 ) ) return false;
                    if ( !stream.serializeUint32( value32 ) ) return false;
                    if ( reading )
                    {
                        FuzzCheck.check( value8.value == expected8, "uint8: asymmetric round trip" );
                        FuzzCheck.check( value16.value == expected16, "uint16: asymmetric round trip" );
                        FuzzCheck.check( value32.value == expected32, "uint32: asymmetric round trip" );
                    }
                }
                break;
            }
        }

        return true;
    }
}
