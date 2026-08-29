package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** Fixed point at every storage shape: narrow, two-group, unsigned, degenerate, and the wide 128-bit paths. */
final class FixedTests
{
    private FixedTests() {}

    static void run()
    {
        test( "fixed: exact round trips across the golden battery's narrow shapes", () -> {
            // { integerBits, fractionBits, minUnits, maxUnits, raw value }
            long[][] cases = {
                { 8, 8, -100, 100, -( 3 * 256 + 64 ) },                 // Q8.8, -3.25
                { 8, 8, -100, 100, -100L << 8 },                        // Q8.8 at raw min
                { 8, 8, -100, 100, 100L << 8 },                         // Q8.8 at raw max
                { 16, 16, -2000, 2000, 1234L * 65536 + 32768 },         // Q16.16, 1234.5
                { 48, 16, -100000, 100000, -( 54321L * 65536 + 12345 ) }, // Q48.16, two groups
                { 16, 16, 0, 30000, 29999L * 65536 + 65535 },           // unsigned Q16.16
                { 32, 0, -1000, 1000, -7 },                             // fraction_bits 0 IS a ranged integer
            };
            for ( long[] c : cases )
            {
                int integerBits = (int) c[0];
                int fractionBits = (int) c[1];
                byte[] buffer = new byte[24];
                WriteStream writer = new WriteStream( buffer, 16 );
                check( writer.serializeFixed( new LongRef( c[4] ), integerBits, fractionBits, c[2], c[3] ) );
                writer.flush();

                MeasureStream measure = new MeasureStream();
                check( measure.serializeFixed( new LongRef( c[4] ), integerBits, fractionBits, c[2], c[3] ) );
                checkEqual( measure.getBitsProcessed(), writer.getBitsProcessed(), "measure agrees" );

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                LongRef readBack = new LongRef();
                check( reader.serializeFixed( readBack, integerBits, fractionBits, c[2], c[3] ) );
                checkEqual( readBack.value, c[4], "exact round trip: fixed point never quantizes" );
            }
        } );

        test( "fixed: byte identical to serializeInt64 of the raw value over the raw bounds", () -> {
            long minUnits = -100000;
            long maxUnits = +100000;
            int fractionBits = 16;
            long rawMin = minUnits << fractionBits;
            long rawMax = maxUnits << fractionBits;
            long[] rawValues = { rawMin, rawMin + 1, -1, 0, 1, 12345678, rawMax - 1, rawMax };

            for ( long raw : rawValues )
            {
                byte[] bufferFixed = new byte[24];
                WriteStream fixedWriter = new WriteStream( bufferFixed, 16 );
                check( fixedWriter.serializeFixed( new LongRef( raw ), 48, 16, minUnits, maxUnits ) );
                fixedWriter.flush();

                byte[] bufferInt = new byte[24];
                WriteStream intWriter = new WriteStream( bufferInt, 16 );
                check( intWriter.serializeInt64( new LongRef( raw ), rawMin, rawMax ) );
                intWriter.flush();

                checkEqual( fixedWriter.getBitsProcessed(), intWriter.getBitsProcessed(), "same bit count for raw " + raw );
                Harness.checkBytesEqual( bufferFixed, bufferInt, (int) intWriter.getBytesProcessed(), "identical wire for raw " + raw );
            }
        } );

        test( "fixed: a raw value outside the bounds is refused on read — reject, never clamp", () -> {
            // [0,100] whole units in Q16.16: raw range 6553600, 23 bits. 8000000 fits
            // 23 bits but exceeds the range.
            byte[] buffer = new byte[16];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 8000000 ), 23 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            LongRef readBack = new LongRef();
            check( !reader.serializeFixed( readBack, 16, 16, 0, 100 ), "smuggled offset refused" );
        } );

        test( "fixed: truncated refuses", () -> {
            ReadStream reader = new ReadStream( new byte[16], 1 );
            LongRef readBack = new LongRef();
            check( !reader.serializeFixed( readBack, 16, 16, -100, 100 ), "refused" );
        } );

        test( "fixed: a degenerate range costs zero bits on every storage width", () -> {
            // narrow storage
            {
                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeFixed( new LongRef( 42L << 16 ), 16, 16, 42, 42 ) );
                checkEqual( writer.getBitsProcessed(), 0, "zero bits, narrow" );
                writer.flush();

                ReadStream reader = new ReadStream( buffer, 0 );
                LongRef readBack = new LongRef();
                check( reader.serializeFixed( readBack, 16, 16, 42, 42 ) );
                checkEqual( readBack.value, 42L << 16, "the value IS the range" );
            }
            // wide storage: zero bits, NOT fractionBits zeros (STANDARD.md, adopted 2026-08-15)
            {
                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                Ref<Int128Value> value = new Ref<>( Int128Value.fromLong( -7L << 16 ) );
                check( writer.serializeFixed128( value, 112, 16, -7, -7 ) );
                checkEqual( writer.getBitsProcessed(), 0, "zero bits, wide" );
                writer.flush();

                ReadStream reader = new ReadStream( buffer, 0 );
                Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
                check( reader.serializeFixed128( readBack, 112, 16, -7, -7 ) );
                check( readBack.value.equals( Int128Value.fromLong( -7L << 16 ) ), "the value IS the range" );

                MeasureStream measure = new MeasureStream();
                check( measure.serializeFixed128( value, 112, 16, -7, -7 ) );
                checkEqual( measure.getBitsProcessed(), 0, "measure charges zero too" );
            }
        } );

        test( "fixed128: the three-group and four-group structures round trip", () -> {
            // Q112.16 over ±2^57 whole units: 75 bits, three groups
            {
                long bound = 144115188075855872L;                       // 2^57
                Int128Value[] values = {
                    Int128Value.fromLong( -( 98765432109L * 65536 + 4321 ) ),
                    Int128Value.fromLong( -bound ).shiftLeft( 16 ),     // raw min
                    Int128Value.fromLong( bound ).shiftLeft( 16 ),      // raw max
                    Int128Value.ZERO,
                    Int128Value.fromLong( -1 ),
                };
                for ( Int128Value value : values )
                {
                    byte[] buffer = new byte[24];
                    WriteStream writer = new WriteStream( buffer, 16 );
                    check( writer.serializeFixed128( new Ref<>( value ), 112, 16, -bound, bound ) );
                    writer.flush();
                    checkEqual( writer.getBitsProcessed(), 75, "75 bits: 32 + 32 + 11" );

                    MeasureStream measure = new MeasureStream();
                    check( measure.serializeFixed128( new Ref<>( value ), 112, 16, -bound, bound ) );
                    checkEqual( measure.getBitsProcessed(), 75, "measure agrees" );

                    ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                    Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
                    check( reader.serializeFixed128( readBack, 112, 16, -bound, bound ) );
                    check( readBack.value.equals( value ), "round trip " + value );
                }
            }
            // Q64.64 over the full int64 unit range: 128 bits, four groups
            {
                Int128Value[] values = {
                    new Int128Value( 0x0123456789ABCDEFL, 0x0FEDCBA987654321L ),
                    Int128Value.fromLong( Long.MIN_VALUE ).shiftLeft( 64 ),     // the raw minimum
                    Int128Value.fromLong( Long.MAX_VALUE ).shiftLeft( 64 ),     // the raw maximum: INT64_MAX whole units exactly
                    Int128Value.ZERO,
                };
                for ( Int128Value value : values )
                {
                    byte[] buffer = new byte[24];
                    WriteStream writer = new WriteStream( buffer, 16 );
                    check( writer.serializeFixed128( new Ref<>( value ), 64, 64, Long.MIN_VALUE, Long.MAX_VALUE ) );
                    writer.flush();
                    checkEqual( writer.getBitsProcessed(), 128, "128 bits: four groups" );

                    ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                    Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
                    check( reader.serializeFixed128( readBack, 64, 64, Long.MIN_VALUE, Long.MAX_VALUE ) );
                    check( readBack.value.equals( value ), "round trip " + value );
                }
            }
        } );

        test( "fixed128: a smuggled out-of-range offset is refused", () -> {
            // ±1 whole unit in Q112.16: raw range 131072, 18 bits. 200000 fits 18
            // bits but exceeds the range.
            byte[] buffer = new byte[16];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 200000 ), 18 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
            check( !reader.serializeFixed128( readBack, 112, 16, -1, 1 ), "smuggled offset refused" );
        } );

        test( "fixed128: truncated refuses", () -> {
            ReadStream reader = new ReadStream( new byte[16], 4 );      // 32 bits available, 128 required
            Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
            check( !reader.serializeFixed128( readBack, 64, 64, Long.MIN_VALUE, Long.MAX_VALUE ), "refused" );
        } );
    }
}
