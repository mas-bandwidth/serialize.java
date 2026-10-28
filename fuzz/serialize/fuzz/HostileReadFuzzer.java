package serialize.fuzz;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import serialize.BoolRef;
import serialize.CompressedFloatParams;
import serialize.DoubleRef;
import serialize.FloatRef;
import serialize.Int128Value;
import serialize.IntRef;
import serialize.LongRef;
import serialize.ReadStream;
import serialize.Ref;
import serialize.Refusal;
import serialize.SerializeUtil;
import serialize.UInt128Value;

/**
 * Jazzer target one: the HOSTILE READ, the Java counterpart of the
 * reference's FuzzRead (fuzz.cpp, passes 1 and 2).
 *
 * ReadStream is the trust boundary of this library: it must survive
 * arbitrary hostile bytes, refusing a read by returning false and never by
 * throwing. Any throwable escaping a read is a finding, Jazzer reports it.
 * On top of that, every accepted value must be in contract (a ranged value in
 * its range, a float finite, a string free of NUL and within its buffer), and
 * every refusal must keep the refusal contract: the stream is latched, the
 * reason is one of the closed list STANDARD.md defines ({@link Refusal}), a
 * scalar destination is left unwritten, and the next read on the failed
 * stream refuses too without consuming a bit or changing the reason.
 *
 * The string operation's buffer size is chosen by the op parameter from a
 * table that includes caller misuse (sizes below 1), which must refuse as
 * value_out_of_range before any bits move rather than throw (U7).
 *
 * Input layout, as the reference: the first {@link #NUM_OPS} bytes are an op
 * program (the low nibble selects the operation, the high nibble is its
 * parameter), the rest is the hostile bitstream.
 *
 * The reference's second pass feeds the same bytes through InitializePadded
 * from an allocation with no slack. A Java reader takes any array size, so
 * the counterpart here is a differential of two readers over the same
 * payload: one over an array sized exactly to the payload, one over an array
 * whose 8 bytes of slack are all set bits. Bytes past the end must never be
 * interpreted, so the two must agree on every value, on where they stop, and
 * on why.
 */
public final class HostileReadFuzzer
{
    private HostileReadFuzzer() {}

    static final int NUM_OPS = 32;

    static final int MAX_PAYLOAD = 4096;

    private static final int SENTINEL = 0x5EED5EED;

    // the string buffer sizes the op parameter selects: the caller misuse
    // values a reader must refuse (0, -1, -7, MIN_VALUE), the smallest legal
    // ones, the boundaries of each length field width, and the widest
    static final int[] STRING_BUFFER_SIZES = {
        0, -1, -7, Integer.MIN_VALUE, 1, 2, 3, 16, 255, 256, 257, 65536, 1 << 20, 1 << 30, Integer.MAX_VALUE - 1, Integer.MAX_VALUE,
    };

    private static final long SENTINEL64 = 0x5EED5EED5EED5EEDL;

    private static final CompressedFloatParams CF_PARAMS = SerializeUtil.compressedFloatParams( -10.0f, +10.0f, 0.01f );

    /** Jazzer's entry point. */
    public static void fuzzerTestOneInput( byte[] data )
    {
        if ( data.length <= NUM_OPS || data.length > NUM_OPS + MAX_PAYLOAD )
        {
            return;
        }

        final int payloadBytes = data.length - NUM_OPS;

        byte[] exact = new byte[payloadBytes];
        System.arraycopy( data, NUM_OPS, exact, 0, payloadBytes );

        byte[] slack = new byte[payloadBytes + 8];
        System.arraycopy( data, NUM_OPS, slack, 0, payloadBytes );
        for ( int i = payloadBytes; i < slack.length; i++ )
        {
            slack[i] = (byte) 0xFF;
        }

        Trace exactTrace = new Trace();
        ReadStream exactStream = new ReadStream( exact, payloadBytes );
        boolean exactAccepted = fuzzRead( exactStream, data, exactTrace );

        Trace slackTrace = new Trace();
        ReadStream slackStream = new ReadStream( slack, payloadBytes );
        boolean slackAccepted = fuzzRead( slackStream, data, slackTrace );

        FuzzCheck.check( exactAccepted == slackAccepted, "slack past the end changed whether the reader refused" );
        FuzzCheck.check( exactStream.getRefusal() == slackStream.getRefusal(), "slack past the end changed the refusal reason" );
        FuzzCheck.check( exactStream.getBitsProcessed() == slackStream.getBitsProcessed(), "slack past the end changed where the reader stopped" );
        FuzzCheck.check( exactTrace.hash == slackTrace.hash && exactTrace.count == slackTrace.count, "slack past the end changed a decoded value" );
    }

    /** A running digest of every accepted value, so two readers can be compared without storing them. */
    static final class Trace
    {
        long hash = 0xCBF29CE484222325L;
        int count;

        void add( long value )
        {
            hash = ( hash ^ value ) * 0x100000001B3L;
            count++;
        }
    }

    // a refusal: the stream is latched with a reason from the closed list, the
    // scalar destination was left unwritten, and a further read refuses
    // without consuming anything or changing the reason. Returns false, so a
    // caller reads `return refused( ... );` like the reference's early return.
    private static boolean refused( ReadStream stream, boolean unwritten )
    {
        FuzzCheck.check( stream.isFailed(), "a refused read did not latch the stream" );
        Refusal reason = stream.getRefusal();
        FuzzCheck.check( reason != null && reason != Refusal.NONE, "a refused read reported no reason" );
        FuzzCheck.check( Refusal.fromSpelling( reason.toString() ) == reason, "a refusal reason outside the closed list" );
        FuzzCheck.check( unwritten, "a refused read wrote its scalar destination" );

        long bits = stream.getBitsProcessed();
        IntRef echo = new IntRef( SENTINEL );
        FuzzCheck.check( !stream.serializeBits( echo, 1 ), "a read after a refusal was accepted" );
        FuzzCheck.check( echo.value == SENTINEL, "a read after a refusal wrote its destination" );
        FuzzCheck.check( stream.getBitsProcessed() == bits, "a read after a refusal consumed bits" );
        FuzzCheck.check( stream.getRefusal() == reason, "a read after a refusal changed the reason" );
        return false;
    }

    private static void accepted( ReadStream stream )
    {
        FuzzCheck.check( !stream.isFailed() && stream.getRefusal() == Refusal.NONE, "an accepted read left the stream failed" );
    }

    private static BigInteger big( Int128Value value )
    {
        return BigInteger.valueOf( value.hi ).shiftLeft( 64 ).add( new BigInteger( Long.toUnsignedString( value.lo ) ) );
    }

    private static final BigInteger WIDE_BOUND = BigInteger.ONE.shiftLeft( 60 + 16 );            // ±2^60 whole units in Q112.16

    static boolean fuzzRead( ReadStream stream, byte[] ops, Trace trace )
    {
        byte[] bytes = new byte[256];

        for ( int i = 0; i < NUM_OPS; i++ )
        {
            final int select = ops[i] & 15;
            final int param = ( ops[i] >> 4 ) & 15;                 // [0,15]

            switch ( select )
            {
                case 0:
                case 1:
                {
                    final int bits = ( select == 0 ) ? param + 1 : param + 17;          // [1,32]
                    IntRef value = new IntRef( SENTINEL );
                    if ( !stream.serializeBits( value, bits ) ) return refused( stream, value.value == SENTINEL );
                    accepted( stream );
                    FuzzCheck.check( bits == 32 || ( value.value >>> bits ) == 0, "bits: value wider than its width" );
                    trace.add( value.value );
                }
                break;

                case 2:
                {
                    IntRef value = new IntRef( SENTINEL );
                    if ( !stream.serializeInt( value, -100, +100 ) ) return refused( stream, value.value == SENTINEL );
                    accepted( stream );
                    FuzzCheck.check( value.value >= -100 && value.value <= +100, "int: value outside [-100,+100]" );
                    trace.add( value.value );
                }
                break;

                case 3:
                {
                    IntRef value = new IntRef( SENTINEL );
                    if ( !stream.serializeInt( value, Integer.MIN_VALUE, Integer.MAX_VALUE ) ) return refused( stream, value.value == SENTINEL );
                    trace.add( value.value );

                    LongRef value64 = new LongRef( SENTINEL64 );
                    if ( !stream.serializeInt64( value64, Long.MIN_VALUE, Long.MAX_VALUE ) ) return refused( stream, value64.value == SENTINEL64 );
                    trace.add( value64.value );

                    final long bound = (long) ( param + 1 ) << 35;                    // spans of varying width exercise bitsRequired64
                    LongRef ranged64 = new LongRef( SENTINEL64 );
                    if ( !stream.serializeInt64( ranged64, -bound, +bound ) ) return refused( stream, ranged64.value == SENTINEL64 );
                    FuzzCheck.check( ranged64.value >= -bound && ranged64.value <= +bound, "int64: value outside its range" );
                    trace.add( ranged64.value );

                    // fixed point: values read off hostile bytes must decode within the raw bounds or fail the read
                    LongRef fixed32 = new LongRef( SENTINEL64 );
                    if ( !stream.serializeFixed( fixed32, 16, 16, -1000, +1000 ) ) return refused( stream, fixed32.value == SENTINEL64 );
                    FuzzCheck.check( fixed32.value >= -1000L * 65536 && fixed32.value <= 1000L * 65536, "fixed Q16.16: raw value outside its bounds" );
                    trace.add( fixed32.value );

                    LongRef fixed64 = new LongRef( SENTINEL64 );
                    if ( !stream.serializeFixed( fixed64, 48, 16, -100000000000L, +100000000000L ) ) return refused( stream, fixed64.value == SENTINEL64 );
                    FuzzCheck.check( fixed64.value >= -100000000000L * 65536 && fixed64.value <= 100000000000L * 65536, "fixed Q48.16: raw value outside its bounds" );
                    trace.add( fixed64.value );

                    // ±2^60 units: a raw range past 64 bits
                    Ref<Int128Value> wide = new Ref<>( null );
                    if ( !stream.serializeFixed128( wide, 112, 16, -1152921504606846976L, +1152921504606846976L ) ) return refused( stream, wide.value == null );
                    BigInteger wideValue = big( wide.value );
                    FuzzCheck.check( wideValue.compareTo( WIDE_BOUND.negate() ) >= 0 && wideValue.compareTo( WIDE_BOUND ) <= 0, "fixed Q112.16: raw value outside its bounds" );
                    trace.add( wide.value.lo );
                    trace.add( wide.value.hi );

                    // ranged 128 bit integers, the bound width varying by param so the run walks one
                    // group through four (2^20 .. 2^110): hostile bytes decode inside the bounds or fail,
                    // never clamp or wrap
                    final BigInteger boundBig = BigInteger.ONE.shiftLeft( 20 + param * 6 );
                    final Int128Value bound128 = new Int128Value( boundBig.shiftRight( 64 ).longValue(), boundBig.longValue() );
                    Ref<Int128Value> ranged128 = new Ref<>( null );
                    if ( !stream.serializeInt128( ranged128, bound128.negate(), bound128 ) ) return refused( stream, ranged128.value == null );
                    FuzzCheck.check( ranged128.value.compareTo( bound128.negate() ) >= 0 && ranged128.value.compareTo( bound128 ) <= 0, "int128: value outside its range" );
                    trace.add( ranged128.value.lo );
                    trace.add( ranged128.value.hi );

                    Ref<Int128Value> full128 = new Ref<>( null );
                    if ( !stream.serializeInt128( full128, Int128Value.MIN_VALUE, Int128Value.MAX_VALUE ) ) return refused( stream, full128.value == null );
                    trace.add( full128.value.lo );
                    trace.add( full128.value.hi );
                    accepted( stream );
                }
                break;

                case 4:
                {
                    BoolRef value = new BoolRef();
                    if ( !stream.serializeBool( value ) ) return refused( stream, true );
                    accepted( stream );
                    trace.add( value.value ? 1 : 0 );
                }
                break;

                case 5:
                {
                    // finite values only: whatever the bytes, an accepted float is never NaN or infinite
                    FloatRef value = new FloatRef( 1.5f );
                    if ( !stream.serializeFloat( value ) ) return refused( stream, Float.floatToRawIntBits( value.value ) == Float.floatToRawIntBits( 1.5f ) );
                    accepted( stream );
                    FuzzCheck.check( SerializeUtil.floatBitsAreFinite( Float.floatToRawIntBits( value.value ) ), "float: accepted a non-finite value" );
                    trace.add( Float.floatToRawIntBits( value.value ) );
                }
                break;

                case 6:
                {
                    DoubleRef value = new DoubleRef( 1.5 );
                    if ( !stream.serializeDouble( value ) ) return refused( stream, Double.doubleToRawLongBits( value.value ) == Double.doubleToRawLongBits( 1.5 ) );
                    accepted( stream );
                    FuzzCheck.check( SerializeUtil.doubleBitsAreFinite( Double.doubleToRawLongBits( value.value ) ), "double: accepted a non-finite value" );
                    trace.add( Double.doubleToRawLongBits( value.value ) );
                }
                break;

                case 7:
                {
                    // odd parameters drive the precomputed form with the same constants:
                    // the two are wire identical, so they refuse and accept alike
                    FloatRef value = new FloatRef( 1.5f );
                    final boolean ok = ( param & 1 ) == 0
                        ? stream.serializeCompressedFloat( value, -10.0f, +10.0f, 0.01f )
                        : stream.serializeCompressedFloatPrecomputed( value, CF_PARAMS.maxIntegerValue, CF_PARAMS.bits, CF_PARAMS.delta, -10.0f );
                    if ( !ok ) return refused( stream, Float.floatToRawIntBits( value.value ) == Float.floatToRawIntBits( 1.5f ) );
                    accepted( stream );
                    FuzzCheck.check( SerializeUtil.floatBitsAreFinite( Float.floatToRawIntBits( value.value ) ), "compressed_float: accepted a non-finite value" );
                    trace.add( Float.floatToRawIntBits( value.value ) );
                }
                break;

                case 8:
                {
                    LongRef value = new LongRef( SENTINEL64 );
                    if ( !stream.serializeUint64( value ) ) return refused( stream, value.value == SENTINEL64 );
                    trace.add( value.value );

                    Ref<UInt128Value> value128 = new Ref<>( null );
                    if ( !stream.serializeUint128( value128 ) ) return refused( stream, value128.value == null );
                    trace.add( value128.value.lo );
                    trace.add( value128.value.hi );

                    // the raw 64 bit width, the Java surface's own primitive: [33,63]
                    final int bits = 33 + param * 2;
                    LongRef raw = new LongRef( SENTINEL64 );
                    if ( !stream.serializeBits64( raw, bits ) ) return refused( stream, raw.value == SENTINEL64 );
                    FuzzCheck.check( ( raw.value >>> bits ) == 0, "bits64: value wider than its width" );
                    trace.add( raw.value );
                    accepted( stream );
                }
                break;

                case 9:
                {
                    final int numBytes = param * 16 + 1;                    // [1,241]
                    if ( !stream.serializeBytes( bytes, numBytes ) ) return refused( stream, true );       // a caller-owned buffer is unspecified after a refusal
                    accepted( stream );
                    FuzzCheck.check( stream.getBitsProcessed() % 8 == 0, "bytes: ended off a byte boundary" );
                    for ( int j = 0; j < numBytes; j++ )
                    {
                        trace.add( bytes[j] );
                    }
                }
                break;

                case 10:
                {
                    // the buffer size is fuzzed too, caller misuse included: a size
                    // below 1 must refuse as value_out_of_range before any bits move,
                    // whatever the wire holds (IMPLEMENTING.md U7)
                    final int bufferSize = STRING_BUFFER_SIZES[param];
                    final long before = stream.getBitsProcessed();
                    Ref<String> value = new Ref<>( null );
                    if ( !stream.serializeString( value, bufferSize ) )
                    {
                        if ( bufferSize < 1 )
                        {
                            FuzzCheck.check( stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "string: a buffer below 1 was not value_out_of_range" );
                            FuzzCheck.check( stream.getBitsProcessed() == before, "string: a buffer below 1 moved bits" );
                        }
                        return refused( stream, value.value == null );
                    }
                    FuzzCheck.check( bufferSize >= 1, "string: accepted with a buffer below 1" );
                    accepted( stream );
                    byte[] utf8 = value.value.getBytes( StandardCharsets.UTF_8 );
                    FuzzCheck.check( utf8.length < bufferSize, "string: longer than its buffer" );
                    FuzzCheck.check( value.value.indexOf( '\0' ) < 0, "string: accepted an embedded NUL" );
                    FuzzCheck.check( StandardCharsets.UTF_8.newEncoder().canEncode( value.value ), "string: decoded text holds an unpaired surrogate" );
                    trace.add( value.value.hashCode() );
                    trace.add( utf8.length );
                }
                break;

                case 11:
                {
                    // the end of the object: an accepted end leaves nothing in the stream
                    if ( !stream.serializeEnd() ) return refused( stream, true );
                    accepted( stream );
                    FuzzCheck.check( stream.getBitsProcessed() % 8 == 0, "end: accepted off a byte boundary" );
                    IntRef after = new IntRef( SENTINEL );
                    FuzzCheck.check( !stream.serializeBits( after, 1 ), "end: a bit remained after an accepted end" );
                    FuzzCheck.check( stream.getRefusal() == Refusal.PAST_END, "end: the read past an accepted end was not past_end" );
                    return false;
                }

                case 12:
                {
                    if ( !stream.serializeAlign() ) return refused( stream, true );
                    accepted( stream );
                    FuzzCheck.check( stream.getBitsProcessed() % 8 == 0, "align: ended off a byte boundary" );
                }
                break;

                case 13:
                {
                    final int previous = param * 1000;                      // int_relative's domain starts at 0: a negative previous is caller error
                    IntRef current = new IntRef( SENTINEL );
                    if ( !stream.serializeIntRelative( previous, current ) ) return refused( stream, current.value == SENTINEL );
                    accepted( stream );
                    FuzzCheck.check( current.value > previous, "int_relative: accepted a value that does not increase" );
                    trace.add( current.value );
                }
                break;

                case 14:
                {
                    final int max = ( param + 1 ) * 1000;                   // ranges of varying width exercise bitsRequired
                    IntRef value = new IntRef( SENTINEL );
                    if ( !stream.serializeInt( value, 0, max ) ) return refused( stream, value.value == SENTINEL );
                    accepted( stream );
                    FuzzCheck.check( value.value >= 0 && value.value <= max, "int: value outside [0,max]" );
                    trace.add( value.value );
                }
                break;

                case 15:
                default:
                {
                    IntRef value8 = new IntRef( SENTINEL );
                    if ( !stream.serializeUint8( value8 ) ) return refused( stream, value8.value == SENTINEL );
                    FuzzCheck.check( ( value8.value >>> 8 ) == 0, "uint8: value wider than 8 bits" );
                    IntRef value16 = new IntRef( SENTINEL );
                    if ( !stream.serializeUint16( value16 ) ) return refused( stream, value16.value == SENTINEL );
                    FuzzCheck.check( ( value16.value >>> 16 ) == 0, "uint16: value wider than 16 bits" );
                    IntRef value32 = new IntRef( SENTINEL );
                    if ( !stream.serializeUint32( value32 ) ) return refused( stream, value32.value == SENTINEL );
                    accepted( stream );
                    trace.add( value8.value );
                    trace.add( value16.value );
                    trace.add( value32.value );
                }
                break;
            }
        }

        return true;
    }
}
