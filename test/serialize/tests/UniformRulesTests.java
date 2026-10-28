package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.assertionsEnabled;
import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.fires;
import static serialize.tests.Harness.test;

/**
 * IMPLEMENTING.md's uniform rules, U1 to U16, as this port keeps them, and the
 * 2026-10-27 audit's reproducers (audit/Repro.java R1 to R6), each a test.
 *
 * Every read-side test runs in both shapes and must hold in both: a reader
 * never traps (U7), so caller misuse on read refuses as value_out_of_range
 * with assertions on and off alike. Each refusal has a reversed witness: the
 * same bytes with a conforming declaration are accepted, so a reader that
 * refused everything would fail the witness.
 *
 * The write-misuse mechanism of this port (U9) is the Java assert, checked
 * under -ea and compiled into nothing the caller sees under -da. The write and
 * measure tests that need it run in the checked shape only.
 */
final class UniformRulesTests
{
    private UniformRulesTests() {}

    // a read refused for the caller's own misuse: false, latched, value_out_of_range
    private static void refusesOutOfRange( ReadStream stream, boolean result, String what )
    {
        check( !result, what + ": refused" );
        check( stream.isFailed(), what + ": the stream latched" );
        check( stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE,
               what + ": value_out_of_range, got " + stream.getRefusal() );
    }

    private static ReadStream reader( int... values )
    {
        byte[] buffer = GoldenWire.bytes( values );
        return new ReadStream( buffer, buffer.length );
    }

    static void run()
    {
        // ---- U7: a reader never traps; caller misuse on read is value_out_of_range ----

        test( "U7 R1: string bufferSize 0, wire length 0x80000000, refuses value_out_of_range", () -> {
            ReadStream stream = reader( 0x00, 0x00, 0x00, 0x80, 0, 0, 0, 0 );
            Ref<String> value = new Ref<>( "untouched" );
            refusesOutOfRange( stream, stream.serializeString( value, 0 ), "bufferSize 0" );
            check( value.value.equals( "untouched" ), "the destination is unwritten" );
            checkEqual( stream.getBitsProcessed(), 0, "no bits moved" );
        } );

        test( "U7 R1b: string bufferSize 0, wire length 0, refuses rather than accepting an empty string", () -> {
            ReadStream stream = reader( 0, 0, 0, 0, 0, 0, 0, 0 );
            Ref<String> value = new Ref<>( "untouched" );
            refusesOutOfRange( stream, stream.serializeString( value, 0 ), "bufferSize 0" );
            check( value.value.equals( "untouched" ), "the destination is unwritten" );
        } );

        test( "U7 R1c: string bufferSize -7, wire length 0xFFFFFFF0, refuses value_out_of_range", () -> {
            ReadStream stream = reader( 0xF0, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0 );
            refusesOutOfRange( stream, stream.serializeString( new Ref<>( "x" ), -7 ), "bufferSize -7" );
        } );

        test( "U7 R1 witness: string bufferSize 1 over the same empty wire is accepted", () -> {
            ReadStream stream = reader( 0, 0, 0, 0, 0, 0, 0, 0 );
            Ref<String> value = new Ref<>( "untouched" );
            check( stream.serializeString( value, 1 ), "bufferSize 1 is a conforming declaration" );
            check( value.value.isEmpty(), "the empty string" );
        } );

        test( "U7 R2: bytes count larger than the destination refuses value_out_of_range", () -> {
            ReadStream stream = new ReadStream( new byte[16], 16 );
            refusesOutOfRange( stream, stream.serializeBytes( new byte[4], 8 ), "8 bytes into 4" );
            checkEqual( stream.getBitsProcessed(), 0, "no bits moved" );

            ReadStream nullStream = new ReadStream( new byte[16], 16 );
            refusesOutOfRange( nullStream, nullStream.serializeBytes( null, 1 ), "a null destination" );

            ReadStream witness = new ReadStream( new byte[16], 16 );
            check( witness.serializeBytes( new byte[8], 8 ), "witness: 8 bytes into 8 is accepted" );
        } );

        test( "U7 R3: a bit count outside [1,32] or [1,64] refuses value_out_of_range, and the latch holds", () -> {
            for ( int bits : new int[] { -1, 0, 33, Integer.MIN_VALUE, Integer.MAX_VALUE } )
            {
                ReadStream stream = new ReadStream( new byte[16], 16 );
                IntRef value = new IntRef( 7 );
                refusesOutOfRange( stream, stream.serializeBits( value, bits ), "bits " + bits );
                checkEqual( value.value, 7, "the destination is unwritten" );
                check( !stream.serializeBits( value, 8 ), "the next read refuses too" );
                checkEqual( stream.getBitsProcessed(), 0, "no bits moved" );
            }
            for ( int bits : new int[] { -1, 0, 65 } )
            {
                ReadStream stream = new ReadStream( new byte[16], 16 );
                refusesOutOfRange( stream, stream.serializeBits64( new LongRef(), bits ), "bits64 " + bits );
            }
            ReadStream witness = new ReadStream( new byte[16], 16 );
            check( witness.serializeBits( new IntRef(), 32 ) && witness.serializeBits64( new LongRef(), 64 ),
                   "witness: 32 and 64 bits are accepted" );
        } );

        test( "U7 R4: a ReadStream over more bytes than its array refuses at construction", () -> {
            ReadStream stream = new ReadStream( new byte[4], 12 );
            check( stream.isFailed() && stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "latched value_out_of_range" );
            check( !stream.serializeBits( new IntRef(), 1 ), "every read refuses" );
            check( stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "and the first reason stands" );

            ReadStream nullStream = new ReadStream( null, 0 );
            check( nullStream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "a null array refuses too" );

            ReadStream witness = new ReadStream( new byte[4], 4 );
            check( !witness.isFailed() && witness.serializeBits( new IntRef(), 32 ), "witness: 4 bytes of 4" );
        } );

        test( "U7 R5: a ReadStream over a negative byte count refuses, and its end is refused", () -> {
            ReadStream stream = new ReadStream( new byte[100], -1 );
            check( stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "latched value_out_of_range at initialization" );
            check( !stream.serializeEnd(), "the end refuses" );
        } );

        test( "U7 R4/R5: reset applies the same rule, and a good reset clears it", () -> {
            ReadStream stream = new ReadStream( new byte[8], 8 );
            stream.reset( new byte[8], 9 );
            check( stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "reset over too many bytes" );
            stream.reset( new byte[8], -8 );
            check( stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "reset over a negative count" );
            stream.reset( new byte[8], 8 );
            check( !stream.isFailed() && stream.serializeBits64( new LongRef(), 64 ), "witness: a good reset reads" );
        } );

        test( "U7 U16 R6: ranged reads with min > max refuse value_out_of_range", () -> {
            ReadStream stream = reader( 0xF0, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0 );
            IntRef value = new IntRef( 12345 );
            refusesOutOfRange( stream, stream.serializeInt( value, 10, 0 ), "int [10,0]" );
            checkEqual( value.value, 12345, "the destination is unwritten" );

            ReadStream stream64 = reader( 0, 0, 0, 0, 0, 0, 0, 0 );
            refusesOutOfRange( stream64, stream64.serializeInt64( new LongRef(), 1, 0 ), "int64 [1,0]" );

            ReadStream stream128 = reader( 0, 0, 0, 0, 0, 0, 0, 0 );
            refusesOutOfRange( stream128, stream128.serializeInt128( new Ref<>( null ), Int128Value.fromLong( 1 ), Int128Value.fromLong( 0 ) ), "int128 [1,0]" );

            ReadStream witness = reader( 0x05, 0, 0, 0, 0, 0, 0, 0 );
            IntRef got = new IntRef();
            check( witness.serializeInt( got, 0, 10 ), "witness: [0,10] accepts" );
            checkEqual( got.value, 5, "five" );
        } );

        test( "U7: the failure state is checked first, so misuse on a failed stream keeps the first reason", () -> {
            ReadStream stream = new ReadStream( new byte[1], 1 );
            check( !stream.serializeBits( new IntRef(), 16 ), "past the end" );
            check( stream.getRefusal() == Refusal.PAST_END, "past_end" );
            check( !stream.serializeString( new Ref<>( null ), 0 ), "string bufferSize 0 on the failed stream" );
            check( !stream.serializeBits( new IntRef(), 0 ), "bits 0 on the failed stream" );
            check( !stream.serializeInt( new IntRef(), 1, 0 ), "int [1,0] on the failed stream" );
            check( !stream.serializeBytes( new byte[1], 2 ), "bytes into a short destination on the failed stream" );
            check( stream.getRefusal() == Refusal.PAST_END, "the first reason stands" );
        } );

        // ---- U16: caller declarations validated before any bits move ----

        test( "U16: a fixed point declaration that does not fit refuses on read", () -> {
            int[][] shapes = { { 0, 8 }, { 8, -1 }, { 4, 8 }, { 40, 40 } };
            for ( int[] shape : shapes )
            {
                ReadStream stream = new ReadStream( new byte[16], 16 );
                refusesOutOfRange( stream, stream.serializeFixed( new LongRef(), shape[0], shape[1], 0, 1 ),
                                   "Q" + shape[0] + "." + shape[1] );
                checkEqual( stream.getBitsProcessed(), 0, "no bits moved" );
            }
            ReadStream minMax = new ReadStream( new byte[16], 16 );
            refusesOutOfRange( minMax, minMax.serializeFixed( new LongRef(), 16, 16, 1, 0 ), "min > max" );
            ReadStream wide = new ReadStream( new byte[16], 16 );
            refusesOutOfRange( wide, wide.serializeFixed( new LongRef(), 8, 8, 0, 256 ), "Q8.8 max 256 does not fit" );
            ReadStream neg = new ReadStream( new byte[16], 16 );
            refusesOutOfRange( neg, neg.serializeFixed( new LongRef(), 8, 8, -129, 0 ), "Q8.8 min -129 does not fit" );

            ReadStream f128 = new ReadStream( new byte[32], 32 );
            refusesOutOfRange( f128, f128.serializeFixed128( new Ref<>( null ), 64, 16, 0, 1 ), "Q64.16 is not 128" );
            ReadStream f128b = new ReadStream( new byte[32], 32 );
            refusesOutOfRange( f128b, f128b.serializeFixed128( new Ref<>( null ), 112, 16, 1, 0 ), "128: min > max" );

            ReadStream witness = new ReadStream( new byte[32], 32 );
            check( witness.serializeFixed( new LongRef(), 8, 8, 0, 255 ), "witness: Q8.8 over [0,255]" );
            check( witness.serializeFixed( new LongRef(), 8, 8, -128, 127 ), "witness: Q8.8 over [-128,127]" );
            check( witness.serializeFixed128( new Ref<>( null ), 112, 16, -5, 5 ), "witness: Q112.16" );
        } );

        test( "U16: a compressed_float declaration the params would assert on refuses on read", () -> {
            float[][] declarations = {
                { 1.0f, 0.0f, 0.01f },                                  // min > max
                { 1.0f, 1.0f, 0.01f },                                  // min == max
                { 0.0f, 1.0f, 0.0f },                                   // res 0
                { 0.0f, 1.0f, -0.5f },                                  // res negative
                { Float.NaN, 1.0f, 0.01f },                             // NaN min
                { 0.0f, 1.0f, Float.NaN },                              // NaN res
                { -Float.MAX_VALUE, Float.MAX_VALUE, 1.0f },            // delta not finite
                { 0.0f, Float.MAX_VALUE, Float.MIN_VALUE },             // delta / res not finite
            };
            for ( float[] d : declarations )
            {
                ReadStream stream = new ReadStream( new byte[16], 16 );
                FloatRef value = new FloatRef( 1.5f );
                refusesOutOfRange( stream, stream.serializeCompressedFloat( value, d[0], d[1], d[2] ),
                                   "[" + d[0] + ", " + d[1] + "] at " + d[2] );
                check( value.value == 1.5f, "the destination is unwritten" );
                checkEqual( stream.getBitsProcessed(), 0, "no bits moved" );
            }
            ReadStream witness = new ReadStream( new byte[16], 16 );
            check( witness.serializeCompressedFloat( new FloatRef(), 0.0f, 1.0f, 0.01f ), "witness: [0,1] at 0.01" );
        } );

        test( "U16: int_relative with previous outside the domain refuses on read", () -> {
            ReadStream stream = reader( 0x01, 0, 0, 0, 0, 0, 0, 0 );
            IntRef current = new IntRef( 99 );
            refusesOutOfRange( stream, stream.serializeIntRelative( -1, current ), "previous -1" );
            checkEqual( current.value, 99, "the destination is unwritten" );
            checkEqual( stream.getBitsProcessed(), 0, "no bits moved" );

            ReadStream witness = reader( 0x01, 0, 0, 0, 0, 0, 0, 0 );
            IntRef got = new IntRef();
            check( witness.serializeIntRelative( 0, got ), "witness: previous 0" );
            checkEqual( got.value, 1, "one" );
        } );

        // ---- U14: the refusal-name function and a public fail( reason ) ----

        test( "U14: refusalName spells every reason as the family does, and none for no refusal", () -> {
            check( SerializeUtil.refusalName( null ).equals( "none" ), "null is none" );
            check( SerializeUtil.refusalName( Refusal.NONE ).equals( "none" ), "NONE is none" );
            String[] spellings = {
                "past_end", "value_out_of_range", "nonzero_padding", "trailing_data",
                "non_finite_float", "invalid_utf8", "embedded_nul", "non_canonical", "rejected",
            };
            for ( String spelling : spellings )
            {
                check( SerializeUtil.refusalName( Refusal.fromSpelling( spelling ) ).equals( spelling ), spelling );
            }
        } );

        test( "U14: fail( reason ) on read latches the reason, first wins, and none or null record rejected", () -> {
            for ( Refusal reason : Refusal.values() )
            {
                ReadStream stream = new ReadStream( new byte[8], 8 );
                check( !stream.fail( reason ), "fail returns false" );
                Refusal expected = reason == Refusal.NONE ? Refusal.REJECTED : reason;
                check( stream.isFailed() && stream.getRefusal() == expected, reason + " records " + expected );
                check( !stream.fail( Refusal.PAST_END ) && stream.getRefusal() == expected, "the first reason wins" );
                check( !stream.serializeBits( new IntRef(), 1 ), "a read after fail refuses" );
                check( stream.getRefusal() == expected, "and keeps the reason, never past_end" );
            }
            ReadStream nullReason = new ReadStream( new byte[8], 8 );
            check( !nullReason.fail( null ) && nullReason.getRefusal() == Refusal.REJECTED, "null records rejected" );
            ReadStream witness = new ReadStream( new byte[8], 8 );
            check( witness.serializeBits( new IntRef(), 8 ) && !witness.isFailed(), "witness: a stream nobody failed reads" );
        } );

        test( "U6 U9 U14: fail on a write or measure returns false, records nothing and never throws", () -> {
            WriteStream writer = new WriteStream( new byte[8], 8 );
            MeasureStream measure = new MeasureStream();
            for ( Refusal reason : new Refusal[] { null, Refusal.NONE, Refusal.PAST_END, Refusal.REJECTED } )
            {
                check( !writer.fail( reason ), "write fail is false" );
                check( !measure.fail( reason ), "measure fail is false" );
            }
            check( writer.serializeBits( new IntRef( 1 ), 1 ), "the writer still writes" );
            check( measure.serializeBits( new IntRef( 1 ), 1 ), "the measure still measures: a measure refuses nothing" );
            checkEqual( writer.getBitsProcessed(), 1, "one bit written" );
            checkEqual( measure.getBitsProcessed(), 1, "one bit measured" );
        } );

        test( "U12: end on a write or measure stream is a no-op returning true", () -> {
            WriteStream writer = new WriteStream( new byte[8], 8 );
            check( writer.serializeBits( new IntRef( 5 ), 3 ) && writer.serializeEnd() && writer.serializeEnd(), "write end, twice" );
            checkEqual( writer.getBitsProcessed(), 3, "the end wrote nothing" );
            MeasureStream measure = new MeasureStream();
            check( measure.serializeEnd() && measure.serializeEnd(), "measure end, twice" );
            checkEqual( measure.getBitsProcessed(), 0, "the end cost nothing" );
        } );

        test( "U13: every stream re-initialises: reset rewinds and clears a read latch", () -> {
            byte[] buffer = new byte[8];
            ReadStream reader = new ReadStream( buffer, 1 );
            check( !reader.serializeBits( new IntRef(), 16 ), "past the end" );
            reader.reset( buffer, 2 );
            check( !reader.isFailed() && reader.getRefusal() == Refusal.NONE && reader.serializeBits( new IntRef(), 16 ), "reset clears the latch" );

            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 1 ), 7 ) );
            writer.reset( buffer, 8 );
            checkEqual( writer.getBitsProcessed(), 0, "write reset rewinds" );

            MeasureStream measure = new MeasureStream();
            check( measure.serializeBits( new IntRef( 1 ), 7 ) );
            measure.reset();
            checkEqual( measure.getBitsProcessed(), 0, "measure reset rewinds" );
        } );

        test( "U11: the writer's buffer holds the last word only after flush", () -> {
            byte[] buffer = new byte[8];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 0xAB ), 8 ) );
            checkEqual( buffer[0], 0, "before flush the scratch word has not reached the buffer" );
            writer.flush();
            checkEqual( buffer[0] & 0xFF, 0xAB, "flush stores it" );
        } );

        // ---- U15: compressed_float_precomputed, wire identical to the unified form ----

        test( "U15: compressedFloatParams + the precomputed form match the unified form at every start offset", () -> {
            float[][] declarations = {
                { 0.0f, 10.0f, 0.01f }, { -100.0f, 100.0f, 0.01f }, { -1.0f, 16777218.0f, 1.0f },
                { 0.0f, 1.0f, 2.0f }, { 1.0f, 2.0f, 1.0e-6f },
                { -1.0e9f, 1.0e9f, 1.0e-3f },
            };
            float[] values = { 0.0f, 0.005f, -100.0f, 99.99f, 1.0e9f, -1.0e9f, 5.0f };
            for ( float[] d : declarations )
            {
                CompressedFloatParams params = SerializeUtil.compressedFloatParams( d[0], d[1], d[2] );
                checkEqual( params.bits, SerializeUtil.bitsRequired( 0, (int) params.maxIntegerValue ), "bits" );
                check( params.delta == d[1] - d[0], "delta" );
                for ( int offset = 0; offset < 8; offset++ )
                {
                    for ( float v : values )
                    {
                        float value = Math.max( d[0], Math.min( v, d[1] ) );
                        byte[] unified = new byte[16];
                        byte[] precomputed = new byte[16];
                        WriteStream a = new WriteStream( unified, 16 );
                        WriteStream b = new WriteStream( precomputed, 16 );
                        if ( offset > 0 )
                        {
                            check( a.serializeBits( new IntRef( 0 ), offset ) && b.serializeBits( new IntRef( 0 ), offset ) );
                        }
                        check( a.serializeCompressedFloat( new FloatRef( value ), d[0], d[1], d[2] ) );
                        check( b.serializeCompressedFloatPrecomputed( new FloatRef( value ), params.maxIntegerValue, params.bits, params.delta, d[0] ) );
                        a.flush();
                        b.flush();
                        checkEqual( a.getBitsProcessed(), b.getBitsProcessed(), "the same width" );
                        Harness.checkBytesEqual( precomputed, unified, 16, "the same bytes" );

                        MeasureStream m = new MeasureStream();
                        check( m.serializeCompressedFloatPrecomputed( new FloatRef( value ), params.maxIntegerValue, params.bits, params.delta, d[0] ) );
                        checkEqual( m.getBitsProcessed(), params.bits, "the measure" );

                        int bytes = (int) a.getBytesProcessed();
                        ReadStream ra = new ReadStream( unified, bytes );
                        ReadStream rb = new ReadStream( precomputed, bytes );
                        FloatRef fa = new FloatRef();
                        FloatRef fb = new FloatRef();
                        if ( offset > 0 )
                        {
                            check( ra.serializeBits( new IntRef(), offset ) && rb.serializeBits( new IntRef(), offset ) );
                        }
                        check( ra.serializeCompressedFloat( fa, d[0], d[1], d[2] ), "unified read" );
                        check( rb.serializeCompressedFloatPrecomputed( fb, params.maxIntegerValue, params.bits, params.delta, d[0] ), "precomputed read" );
                        checkEqual( Float.floatToRawIntBits( fb.value ), Float.floatToRawIntBits( fa.value ), "bit identical decode" );
                    }
                }
            }
        } );

        test( "U15 U16: precomputed constants that cannot be right refuse on read", () -> {
            Object[][] cases = {
                { 1000L, 11, 10.0f },                   // bits not bitsRequired( 0, 1000 )
                { 1000L, 10, -10.0f },                  // delta negative
                { 1000L, 10, 0.0f },                    // delta zero
                { 1000L, 10, Float.NaN },               // delta NaN
                { 1000L, 10, Float.POSITIVE_INFINITY }, // delta infinite
                { 0L, 1, 10.0f },                       // maxIntegerValue below 1
                { 4294967295L, 32, 10.0f },             // maxIntegerValue above 4294967040
                { -1L, 32, 10.0f },                     // negative
            };
            for ( Object[] c : cases )
            {
                ReadStream stream = new ReadStream( new byte[16], 16 );
                FloatRef value = new FloatRef( 1.5f );
                refusesOutOfRange( stream, stream.serializeCompressedFloatPrecomputed( value, (Long) c[0], (Integer) c[1], (Float) c[2], 0.0f ),
                                   "constants " + c[0] + "/" + c[1] + "/" + c[2] );
                check( value.value == 1.5f, "the destination is unwritten" );
                checkEqual( stream.getBitsProcessed(), 0, "no bits moved" );
            }
            ReadStream witness = new ReadStream( new byte[16], 16 );
            check( witness.serializeCompressedFloatPrecomputed( new FloatRef(), 1000L, 10, 10.0f, 0.0f ), "witness: consistent constants" );
            ReadStream top = new ReadStream( new byte[16], 16 );
            check( top.serializeCompressedFloatPrecomputed( new FloatRef(), 4294967040L, 32, 10.0f, 0.0f ), "witness: the top of the range" );
        } );

        test( "U15: the precomputed read refuses an integer above maxIntegerValue, as the unified form does", () -> {
            // 1001 in 10 bits over maxIntegerValue 1000
            ReadStream stream = reader( 0xE9, 0x03, 0, 0, 0, 0, 0, 0 );
            check( !stream.serializeCompressedFloatPrecomputed( new FloatRef(), 1000L, 10, 10.0f, 0.0f ), "1001 is refused" );
            check( stream.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "value_out_of_range" );
            ReadStream witness = reader( 0xE8, 0x03, 0, 0, 0, 0, 0, 0 );
            FloatRef value = new FloatRef();
            check( witness.serializeCompressedFloatPrecomputed( value, 1000L, 10, 10.0f, 0.0f ), "witness: 1000 is the top" );
            check( value.value == 10.0f, "and decodes to max" );
        } );

        test( "U9: reject on a write or measure returns false and never throws in the release shape", () -> {
            if ( assertionsEnabled() )
            {
                return;                         // the checked shape asserts it: see the U9 checked test
            }
            check( !new WriteStream( new byte[8], 8 ).reject(), "write reject is false" );
            check( !new MeasureStream().reject(), "measure reject is false" );
        } );

        // ---- the write-misuse mechanism: asserts, the checked shape only ----

        if ( !assertionsEnabled() )
        {
            return;
        }

        test( "U8: a value wider than its width is caught on every writing stream, measure included", () -> {
            check( fires( () -> new WriteStream( new byte[8], 8 ).serializeBits( new IntRef( 256 ), 8 ) ), "write bits(256, 8)" );
            check( fires( () -> new MeasureStream().serializeBits( new IntRef( 256 ), 8 ) ), "measure bits(256, 8)" );
            check( fires( () -> new WriteStream( new byte[8], 8 ).serializeUint8( new IntRef( 256 ) ) ), "write uint8(256)" );
            check( fires( () -> new MeasureStream().serializeUint8( new IntRef( 256 ) ) ), "measure uint8(256)" );
            check( fires( () -> new WriteStream( new byte[8], 8 ).serializeUint16( new IntRef( 65536 ) ) ), "write uint16(65536)" );
            check( fires( () -> new MeasureStream().serializeUint16( new IntRef( 65536 ) ) ), "measure uint16(65536)" );
            check( fires( () -> new MeasureStream().serializeBits64( new LongRef( 1L << 40 ), 40 ) ), "measure bits64(2^40, 40)" );
            // the reversed witness: the widest value that fits does not fire
            check( !fires( () -> new WriteStream( new byte[8], 8 ).serializeBits( new IntRef( 255 ), 8 ) ), "write bits(255, 8)" );
            check( !fires( () -> new MeasureStream().serializeBits( new IntRef( 255 ), 8 ) ), "measure bits(255, 8)" );
            check( !fires( () -> new MeasureStream().serializeUint16( new IntRef( 65535 ) ) ), "measure uint16(65535)" );
        } );

        test( "U10: bytes check the room for the whole operation before writing any of it", () -> {
            WriteStream writer = new WriteStream( new byte[8], 8 );
            check( writer.serializeBits( new IntRef( 5 ), 3 ) );
            check( fires( () -> writer.serializeBytes( new byte[8], 8 ) ), "8 bytes after 3 bits do not fit in 8" );
            checkEqual( writer.getBitsProcessed(), 3, "no align bits were written ahead of the payload" );

            WriteStream witness = new WriteStream( new byte[8], 8 );
            check( witness.serializeBits( new IntRef( 5 ), 3 ) );
            check( !fires( () -> witness.serializeBytes( new byte[7], 7 ) ), "witness: 7 bytes after 3 bits fit" );
        } );

        test( "U10: string checks the room for length, align and payload before writing any of it", () -> {
            WriteStream writer = new WriteStream( new byte[8], 8 );
            check( fires( () -> writer.serializeString( new Ref<>( "abcdefgh" ), 256 ) ), "8 bytes of length and payload past 8" );
            checkEqual( writer.getBitsProcessed(), 0, "no length bits were written ahead of the payload" );

            WriteStream witness = new WriteStream( new byte[8], 8 );
            check( !fires( () -> witness.serializeString( new Ref<>( "abcdefg" ), 256 ) ), "witness: 1 + 7 bytes fit in 8" );
        } );

        test( "U16: bytes with a destination shorter than the count are caught on write and measure", () -> {
            check( fires( () -> new WriteStream( new byte[16], 16 ).serializeBytes( new byte[4], 8 ) ), "write 8 bytes from 4" );
            check( fires( () -> new MeasureStream().serializeBytes( new byte[4], 8 ) ), "measure 8 bytes from 4" );
            check( fires( () -> new WriteStream( new byte[16], 16 ).serializeString( new Ref<>( "" ), 0 ) ), "write string bufferSize 0" );
            check( fires( () -> new MeasureStream().serializeString( new Ref<>( "" ), 0 ) ), "measure string bufferSize 0" );
            check( !fires( () -> new MeasureStream().serializeBytes( new byte[8], 8 ) ), "witness: measure 8 bytes from 8" );
        } );

        test( "U9: one mechanism, the assert: reject on write and measure fires, fail never does", () -> {
            check( fires( () -> new WriteStream( new byte[8], 8 ).reject() ), "write reject asserts" );
            check( fires( () -> new MeasureStream().reject() ), "measure reject asserts" );
            check( !fires( () -> new WriteStream( new byte[8], 8 ).fail( Refusal.REJECTED ) ), "write fail does not" );
            check( !fires( () -> new MeasureStream().fail( null ) ), "measure fail does not" );
        } );

        test( "U1: write buffers are a multiple of 8 bytes", () -> {
            for ( int bytes : new int[] { 1, 4, 12, 20 } )
            {
                check( fires( () -> new WriteStream( new byte[32], bytes ) ), bytes + " bytes asserts" );
            }
            check( !fires( () -> new WriteStream( new byte[32], 8 ) ), "witness: 8 bytes" );
            check( !fires( () -> new WriteStream( new byte[32], 32 ) ), "witness: 32 bytes" );
        } );

        test( "U3 U4: a string that is not well-formed UTF-16, or holds NUL, is caught on write and measure", () -> {
            String[] bad = { "a\uD800", "\uDC00b", "x\uD800y", "nul\u0000inside" };
            for ( String string : bad )
            {
                check( fires( () -> new WriteStream( new byte[64], 64 ).serializeString( new Ref<>( string ), 64 ) ), "write" );
                check( fires( () -> new MeasureStream().serializeString( new Ref<>( string ), 64 ) ), "measure" );
            }
            check( !fires( () -> new WriteStream( new byte[64], 64 ).serializeString( new Ref<>( "ok \uD83D\uDE00" ), 64 ) ), "witness: a surrogate pair" );
        } );

        test( "U15 U16: precomputed constants that cannot be right are caught on write and measure", () -> {
            check( fires( () -> new WriteStream( new byte[16], 16 ).serializeCompressedFloatPrecomputed( new FloatRef( 1.0f ), 1000L, 11, 10.0f, 0.0f ) ), "write: bits" );
            check( fires( () -> new MeasureStream().serializeCompressedFloatPrecomputed( new FloatRef( 1.0f ), 1000L, 10, -10.0f, 0.0f ) ), "measure: delta" );
            check( fires( () -> new WriteStream( new byte[16], 16 ).serializeCompressedFloatPrecomputed( new FloatRef( Float.NaN ), 1000L, 10, 10.0f, 0.0f ) ), "write: NaN value" );
            check( !fires( () -> new WriteStream( new byte[16], 16 ).serializeCompressedFloatPrecomputed( new FloatRef( 1.0f ), 1000L, 10, 10.0f, 0.0f ) ), "witness" );
        } );
    }
}
