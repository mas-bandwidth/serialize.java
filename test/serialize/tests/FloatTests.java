package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/**
 * Bit-transparent float and double, and the compressed float with its pinned
 * conformance vectors — copied mechanically from serialize.h, never re-derived.
 */
final class FloatTests
{
    private FloatTests() {}

    static void run()
    {
        test( "float: every bit pattern is legal and round trips exactly", () -> {
            int[] patterns = {
                0x00000000,             // +0
                0x80000000,             // -0
                0x3F800000,             // 1.0
                0xBF800000,             // -1.0
                0x7F800000,             // +Inf
                0xFF800000,             // -Inf
                0x7FC00000,             // quiet NaN
                0x7FC00001,             // quiet NaN with payload
                0x7F800001,             // signaling NaN
                0xFFC12345,             // negative NaN with payload
                0x00000001,             // smallest denormal
                0x007FFFFF,             // largest denormal
                0x40490FDA,             // pi
            };
            for ( int pattern : patterns )
            {
                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeFloat( new FloatRef( Float.intBitsToFloat( pattern ) ) ) );
                writer.flush();

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                FloatRef readBack = new FloatRef();
                check( reader.serializeFloat( readBack ), "read accepts every pattern" );
                checkEqual( Float.floatToRawIntBits( readBack.value ) & 0xFFFFFFFFL,
                            pattern & 0xFFFFFFFFL,
                            String.format( "pattern 0x%08X", pattern ) );
            }
        } );

        test( "double: every bit pattern is legal and round trips exactly", () -> {
            long[] patterns = {
                0x0000000000000000L,    // +0
                0x8000000000000000L,    // -0
                0x3FF0000000000000L,    // 1.0
                0x7FF0000000000000L,    // +Inf
                0xFFF0000000000000L,    // -Inf
                0x7FF8000000000000L,    // quiet NaN
                0x7FF0000000000001L,    // signaling NaN
                0xFFF8123456789ABCL,    // negative NaN with payload
                0x0000000000000001L,    // smallest denormal
                0x3FD5555555555555L,    // 1/3
            };
            for ( long pattern : patterns )
            {
                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeDouble( new DoubleRef( Double.longBitsToDouble( pattern ) ) ) );
                writer.flush();

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                DoubleRef readBack = new DoubleRef();
                check( reader.serializeDouble( readBack ), "read accepts every pattern" );
                checkEqual( Double.doubleToRawLongBits( readBack.value ), pattern,
                            String.format( "pattern 0x%016X", pattern ) );
            }
        } );

        test( "float/double: truncated refuses", () -> {
            ReadStream reader = new ReadStream( new byte[16], 3 );
            check( !reader.serializeFloat( new FloatRef() ), "float from 24 bits refused" );
            ReadStream reader2 = new ReadStream( new byte[16], 7 );
            check( !reader2.serializeDouble( new DoubleRef() ), "double from 56 bits refused" );
        } );

        test( "compressed float: between-quanta discriminators over [0,10] at 0.01", () -> {
            // STANDARD.md: the required arithmetic quantizes 0.005 to 1, 0.025 to 3,
            // 0.105 to 11 and 9.995 to 1000; widening to double yields 0, 2, 10, 999.
            float[] values = { 0.005f, 0.025f, 0.105f, 9.995f, 2.5f };
            int[] codes = { 1, 3, 11, 1000, 250 };
            for ( int i = 0; i < values.length; i++ )
            {
                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeCompressedFloat( new FloatRef( values[i] ), 0.0f, 10.0f, 0.01f ) );
                writer.flush();
                checkEqual( writer.getBitsProcessed(), 10, "10 bits for 1000 steps" );

                // decode the raw code straight off the wire to convict a widened writer
                ReadStream rawReader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                IntRef code = new IntRef();
                check( rawReader.serializeBits( code, 10 ) );
                checkEqual( code.value, codes[i], "quantized code for " + values[i] );
            }
        } );

        test( "compressed float: the non-zero-min conformance vector, bytes and bit-exact decodes", () -> {
            // serialize.h test_compressed_float_conformance_nonzero_min: pinned_bytes verbatim
            byte[] pinnedBytes = GoldenWire.bytes( 0x10, 0xA7, 0x06, 0x80, 0x82, 0x06 );

            byte[] buffer = new byte[64];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeCompressedFloat( new FloatRef( 0.0f ), -100.0f, 100.0f, 0.01f ) );
            check( writer.serializeCompressedFloat( new FloatRef( -99.875f ), -100.0f, 100.0f, 0.01f ) );
            check( writer.serializeCompressedFloat( new FloatRef( -33.34f ), -100.0f, 100.0f, 0.01f ) );
            check( writer.serializeAlign() );
            writer.flush();
            checkEqual( writer.getBytesProcessed(), pinnedBytes.length, "pinned length" );
            Harness.checkBytesEqual( buffer, pinnedBytes, pinnedBytes.length, "pinned bytes" );

            // read side: decoded floats pinned bit-exactly — the divergence this detects is one ulp
            byte[] readBuffer = new byte[64];
            System.arraycopy( pinnedBytes, 0, readBuffer, 0, pinnedBytes.length );
            ReadStream reader = new ReadStream( readBuffer, pinnedBytes.length );
            FloatRef a = new FloatRef( -1.0f );
            FloatRef b = new FloatRef( -1.0f );
            FloatRef c = new FloatRef( -1.0f );
            check( reader.serializeCompressedFloat( a, -100.0f, 100.0f, 0.01f ) );
            check( reader.serializeCompressedFloat( b, -100.0f, 100.0f, 0.01f ) );
            check( reader.serializeCompressedFloat( c, -100.0f, 100.0f, 0.01f ) );
            check( reader.serializeAlign() );
            checkEqual( Float.floatToRawIntBits( a.value ) & 0xFFFFFFFFL, 0x00000000L, "a bits" );
            checkEqual( Float.floatToRawIntBits( b.value ) & 0xFFFFFFFFL, 0xC2C7BD71L, "b bits" );
            checkEqual( Float.floatToRawIntBits( c.value ) & 0xFFFFFFFFL, 0xC2055C2AL, "c bits" );
        } );

        test( "compressed float: the writer-fusion conformance vector — the [2^23,2^24) band", () -> {
            // serialize.h test_compressed_float_conformance_writer_fusion: pinned_bytes verbatim
            byte[] pinnedBytes = GoldenWire.bytes( 0x00, 0x00, 0x80, 0xAC, 0xAA, 0xAA, 0xFF, 0xFF, 0xFF );

            byte[] buffer = new byte[64];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeCompressedFloat( new FloatRef( 8388608.0f ), 0.0f, 16777215.0f, 1.0f ) );
            check( writer.serializeCompressedFloat( new FloatRef( 11184811.0f ), 0.0f, 16777215.0f, 1.0f ) );
            check( writer.serializeCompressedFloat( new FloatRef( 16777215.0f ), 0.0f, 16777215.0f, 1.0f ) );
            check( writer.serializeAlign() );
            writer.flush();
            checkEqual( writer.getBytesProcessed(), pinnedBytes.length, "pinned length" );
            Harness.checkBytesEqual( buffer, pinnedBytes, pinnedBytes.length, "pinned bytes" );

            byte[] readBuffer = new byte[64];
            System.arraycopy( pinnedBytes, 0, readBuffer, 0, pinnedBytes.length );
            ReadStream reader = new ReadStream( readBuffer, pinnedBytes.length );
            FloatRef a = new FloatRef( -1.0f );
            FloatRef b = new FloatRef( -1.0f );
            FloatRef c = new FloatRef( -1.0f );
            check( reader.serializeCompressedFloat( a, 0.0f, 16777215.0f, 1.0f ) );
            check( reader.serializeCompressedFloat( b, 0.0f, 16777215.0f, 1.0f ) );
            check( reader.serializeCompressedFloat( c, 0.0f, 16777215.0f, 1.0f ) );
            check( reader.serializeAlign() );
            checkEqual( Float.floatToRawIntBits( a.value ) & 0xFFFFFFFFL, 0x4B000000L, "8388608.0" );
            checkEqual( Float.floatToRawIntBits( b.value ) & 0xFFFFFFFFL, 0x4B2AAAACL, "11184812.0" );
            checkEqual( Float.floatToRawIntBits( c.value ) & 0xFFFFFFFFL, 0x4B7FFFFFL, "16777215.0" );
        } );

        test( "compressed float: the normative integer clamp witnesses", () -> {
            // [0, 8388609] at resolution 1: without the clamp, writing max emits a code
            // the reader's own check rejects. With it, the write round trips.
            {
                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeCompressedFloat( new FloatRef( 8388609.0f ), 0.0f, 8388609.0f, 1.0f ) );
                writer.flush();
                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                FloatRef readBack = new FloatRef();
                check( reader.serializeCompressedFloat( readBack, 0.0f, 8388609.0f, 1.0f ), "the reader-rejects class is closed" );
            }
            // [0, 16777215] at resolution 1: without the clamp, writing max emits a code
            // one bit wider than the field. The pinned vector above already pins the bytes;
            // this pins the round trip.
            {
                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeCompressedFloat( new FloatRef( 16777215.0f ), 0.0f, 16777215.0f, 1.0f ) );
                writer.flush();
                checkEqual( writer.getBitsProcessed(), 24, "24 bits, not 25" );
                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                FloatRef readBack = new FloatRef();
                check( reader.serializeCompressedFloat( readBack, 0.0f, 16777215.0f, 1.0f ), "the wire-divergence class is closed" );
                checkEqual( Float.floatToRawIntBits( readBack.value ) & 0xFFFFFFFFL, 0x4B7FFFFFL, "decodes to max" );
            }
        } );

        test( "compressed float: an integer above maxIntegerValue is refused on read", () -> {
            byte[] buffer = new byte[16];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 1023 ), 10 ) );    // maxIntegerValue is 1000 for [0,10] at 0.01
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            FloatRef value = new FloatRef();
            check( !reader.serializeCompressedFloat( value, 0.0f, 10.0f, 0.01f ), "1023 over 1000 steps refused" );
        } );

        test( "compressed float: huge delta/res ratios clamp instead of overflowing", () -> {
            byte[] buffer = new byte[16];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeCompressedFloat( new FloatRef( 5000000000.0f ), 0.0f, 10000000000.0f, 1.0f ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            FloatRef value = new FloatRef();
            check( reader.serializeCompressedFloat( value, 0.0f, 10000000000.0f, 1.0f ) );
            check( Math.abs( value.value - 5000000000.0f ) <= 4096.0f, "within the clamped resolution" );
        } );

        test( "compressed float: truncated refuses", () -> {
            ReadStream reader = new ReadStream( new byte[16], 1 );      // 8 bits available, 10 required
            FloatRef value = new FloatRef();
            check( !reader.serializeCompressedFloat( value, 0.0f, 10.0f, 0.01f ), "refused" );
        } );
    }
}
