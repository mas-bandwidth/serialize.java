package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** uint128 and ranged int128, with the golden pins copied mechanically from serialize.h. */
final class Int128Tests
{
    private Int128Tests() {}

    // serialize.h golden_uint128_bytes, verbatim
    static final byte[] GOLDEN_UINT128_BYTES = GoldenWire.bytes(
        0x10, 0x32, 0x54, 0x76, 0x98, 0xBA, 0xDC, 0xFE,
        0xEF, 0xCD, 0xAB, 0x89, 0x67, 0x45, 0x23, 0x01 );

    // serialize.h golden_int128_bytes, verbatim
    static final byte[] GOLDEN_INT128_BYTES = GoldenWire.bytes(
        0x11, 0x32, 0x54, 0x76, 0x98, 0xBA, 0xDC, 0xFE,
        0x3F, 0x00, 0x00, 0x00 );

    static void run()
    {
        test( "uint128: round trips across the value patterns", () -> {
            UInt128Value[] values = {
                UInt128Value.ZERO,
                UInt128Value.ZERO.not(),
                new UInt128Value( -1L, 0 ),                                 // high half only
                new UInt128Value( 0, -1L ),                                 // low half only
                new UInt128Value( 0xAAAAAAAAAAAAAAAAL, 0x5555555555555555L ),
                new UInt128Value( 0x0123456789ABCDEFL, 0xFEDCBA9876543210L ),
            };
            for ( UInt128Value value : values )
            {
                byte[] buffer = new byte[16 + 8];
                WriteStream writer = new WriteStream( buffer, 16 );
                Ref<UInt128Value> written = new Ref<>( value );
                check( writer.serializeUint128( written ) );
                writer.flush();

                MeasureStream measure = new MeasureStream();
                check( measure.serializeUint128( new Ref<>( value ) ) );
                checkEqual( measure.getBitsProcessed(), writer.getBitsProcessed(), "measure agrees" );
                checkEqual( writer.getBitsProcessed(), 128, "always 128 bits" );

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                Ref<UInt128Value> readBack = new Ref<>( UInt128Value.ZERO );
                check( reader.serializeUint128( readBack ) );
                check( readBack.value.equals( value ), "round trip " + value );
            }
        } );

        test( "uint128: byte identical to two uint64 halves, low half first", () -> {
            long lowHalf = 0xFEDCBA9876543210L;
            long highHalf = 0x0123456789ABCDEFL;

            byte[] wholeBuffer = new byte[16 + 8];
            WriteStream whole = new WriteStream( wholeBuffer, 16 );
            check( whole.serializeUint128( new Ref<>( new UInt128Value( highHalf, lowHalf ) ) ) );
            whole.flush();

            byte[] halvesBuffer = new byte[16 + 8];
            WriteStream halves = new WriteStream( halvesBuffer, 16 );
            check( halves.serializeUint64( new LongRef( lowHalf ) ) );
            check( halves.serializeUint64( new LongRef( highHalf ) ) );
            halves.flush();

            checkEqual( whole.getBitsProcessed(), halves.getBitsProcessed(), "same bit count" );
            Harness.checkBytesEqual( wholeBuffer, halvesBuffer, 16, "identical wire" );
        } );

        test( "uint128: the golden pin — 16 little-endian bytes, low half first", () -> {
            UInt128Value goldenValue = new UInt128Value( 0x0123456789ABCDEFL, 0xFEDCBA9876543210L );

            byte[] buffer = new byte[16 + 8];
            WriteStream writer = new WriteStream( buffer, 16 );
            check( writer.serializeUint128( new Ref<>( goldenValue ) ) );
            writer.flush();
            checkEqual( writer.getBytesProcessed(), 16, "16 bytes" );
            Harness.checkBytesEqual( buffer, GOLDEN_UINT128_BYTES, 16, "golden bytes" );

            byte[] readBuffer = new byte[16 + 8];
            System.arraycopy( GOLDEN_UINT128_BYTES, 0, readBuffer, 0, 16 );
            ReadStream reader = new ReadStream( readBuffer, 16 );
            Ref<UInt128Value> readBack = new Ref<>( UInt128Value.ZERO );
            check( reader.serializeUint128( readBack ) );
            check( readBack.value.equals( goldenValue ), "golden decode" );
        } );

        test( "uint128: truncated refuses", () -> {
            ReadStream reader = new ReadStream( new byte[24], 8 );          // 64 bits available, 128 required
            Ref<UInt128Value> value = new Ref<>( UInt128Value.ZERO );
            check( !reader.serializeUint128( value ), "128 bits from 64 refused" );
        } );

        test( "int128: wire identity with serializeInt64 wherever the range fits 64 bits", () -> {
            long min64 = -5000000000L;
            long max64 = +5000000000L;
            long[] values = { min64, min64 + 1, -1, 0, +1, 4123456789L, max64 - 1, max64 };

            for ( long value : values )
            {
                byte[] buffer128 = new byte[32 + 8];
                byte[] buffer64 = new byte[32 + 8];

                WriteStream w128 = new WriteStream( buffer128, 32 );
                check( w128.serializeInt128( new Ref<>( Int128Value.fromLong( value ) ),
                                             Int128Value.fromLong( min64 ), Int128Value.fromLong( max64 ) ) );
                w128.flush();

                WriteStream w64 = new WriteStream( buffer64, 32 );
                check( w64.serializeInt64( new LongRef( value ), min64, max64 ) );
                w64.flush();

                checkEqual( w128.getBitsProcessed(), w64.getBitsProcessed(), "same bit count for " + value );
                Harness.checkBytesEqual( buffer128, buffer64, (int) w64.getBytesProcessed(), "identical wire for " + value );

                ReadStream reader = new ReadStream( buffer128, 32 );
                Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
                check( reader.serializeInt128( readBack, Int128Value.fromLong( min64 ), Int128Value.fromLong( max64 ) ) );
                check( readBack.value.equals( Int128Value.fromLong( value ) ), "round trip " + value );
            }
        } );

        test( "int128: the three-group band the 64-bit path cannot express", () -> {
            Int128Value wideMin = Int128Value.fromLong( 1 ).shiftLeft( 100 ).negate();
            Int128Value wideMax = Int128Value.fromLong( 1 ).shiftLeft( 100 );
            Int128Value[] values = {
                wideMin, wideMin.add( Int128Value.fromLong( 1 ) ),
                Int128Value.fromLong( -1 ), Int128Value.ZERO, Int128Value.fromLong( 1 ),
                Int128Value.fromLong( 1 ).shiftLeft( 99 ),
                wideMax.subtract( Int128Value.fromLong( 1 ) ), wideMax,
            };
            for ( Int128Value value : values )
            {
                byte[] buffer = new byte[32 + 8];
                WriteStream writer = new WriteStream( buffer, 32 );
                check( writer.serializeInt128( new Ref<>( value ), wideMin, wideMax ) );
                writer.flush();
                checkEqual( writer.getBitsProcessed(), 102, "bits_required128(-2^100, 2^100)" );

                ReadStream reader = new ReadStream( buffer, 32 );
                Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
                check( reader.serializeInt128( readBack, wideMin, wideMax ) );
                check( readBack.value.equals( value ), "round trip " + value );
            }
        } );

        test( "int128: the full range — every group full, range wider than 2^127", () -> {
            Int128Value fullMin = Int128Value.MIN_VALUE;
            Int128Value fullMax = Int128Value.MAX_VALUE;
            Int128Value[] values = {
                fullMin, fullMin.add( Int128Value.fromLong( 1 ) ),
                Int128Value.fromLong( -1 ), Int128Value.ZERO, Int128Value.fromLong( 1 ),
                fullMax.subtract( Int128Value.fromLong( 1 ) ), fullMax,
            };
            for ( Int128Value value : values )
            {
                byte[] buffer = new byte[32 + 8];
                WriteStream writer = new WriteStream( buffer, 32 );
                check( writer.serializeInt128( new Ref<>( value ), fullMin, fullMax ) );
                writer.flush();
                checkEqual( writer.getBitsProcessed(), 128, "128 bits" );

                ReadStream reader = new ReadStream( buffer, 32 );
                Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
                check( reader.serializeInt128( readBack, fullMin, fullMax ) );
                check( readBack.value.equals( value ), "round trip " + value );
            }
        } );

        test( "int128: the measure stream agrees with the write stream at every group width", () -> {
            Int128Value[][] cases = {
                { Int128Value.ZERO, Int128Value.ZERO, Int128Value.fromLong( 255 ) },
                { Int128Value.fromLong( 7 ), Int128Value.fromLong( -5000000000L ), Int128Value.fromLong( +5000000000L ) },
                { Int128Value.fromLong( 1 ), Int128Value.fromLong( 1 ).shiftLeft( 100 ).negate(), Int128Value.fromLong( 1 ).shiftLeft( 100 ) },
                { Int128Value.ZERO, Int128Value.MIN_VALUE, Int128Value.MAX_VALUE },
            };
            for ( Int128Value[] c : cases )
            {
                byte[] buffer = new byte[32 + 8];
                WriteStream writer = new WriteStream( buffer, 32 );
                check( writer.serializeInt128( new Ref<>( c[0] ), c[1], c[2] ) );
                writer.flush();

                MeasureStream measure = new MeasureStream();
                check( measure.serializeInt128( new Ref<>( c[0] ), c[1], c[2] ) );
                checkEqual( measure.getBitsProcessed(), writer.getBitsProcessed(), "measure agrees" );
            }
        } );

        test( "int128: a value outside the bounds is refused on read", () -> {
            byte[] buffer = new byte[32 + 8];
            WriteStream writer = new WriteStream( buffer, 32 );
            check( writer.serializeInt128( new Ref<>( Int128Value.fromLong( 255 ) ),
                                           Int128Value.ZERO, Int128Value.fromLong( 255 ) ) );
            writer.flush();

            // the bit count is identical for both bound pairs, so the range check is what convicts
            checkEqual( SerializeUtil.bitsRequired128( UInt128Value.ZERO, UInt128Value.fromUnsignedLong( 200 ) ), 8, "same width" );

            ReadStream reader = new ReadStream( buffer, 32 );
            Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
            check( !reader.serializeInt128( readBack, Int128Value.ZERO, Int128Value.fromLong( 200 ) ), "255 in [0,200] refused" );
        } );

        test( "int128: a degenerate range is legal and costs zero bits", () -> {
            // the corpus vector's bounds: 2^100 + 7, min == max
            Int128Value bound = new Int128Value( 0x0000000000000010L, 0x0000000000000007L );

            byte[] buffer = new byte[16 + 8];
            WriteStream writer = new WriteStream( buffer, 16 );
            check( writer.serializeInt128( new Ref<>( bound ), bound, bound ) );
            writer.flush();
            checkEqual( writer.getBitsProcessed(), 0, "the writer emits nothing" );

            MeasureStream measure = new MeasureStream();
            check( measure.serializeInt128( new Ref<>( bound ), bound, bound ) );
            checkEqual( measure.getBitsProcessed(), 0, "the measure adds zero" );

            ReadStream reader = new ReadStream( new byte[8], 0 );           // an empty stream carries it
            Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
            check( reader.serializeInt128( readBack, bound, bound ) );
            check( readBack.value.equals( bound ), "the value comes from min" );
            checkEqual( reader.getBitsProcessed(), 0, "the reader consumes nothing" );
        } );

        test( "int128: a truncated buffer refuses rather than reading past the end", () -> {
            ReadStream reader = new ReadStream( new byte[32 + 8], 4 );      // 32 bits available, 128 required
            Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
            check( !reader.serializeInt128( readBack, Int128Value.MIN_VALUE, Int128Value.MAX_VALUE ), "refused" );
            checkEqual( reader.getBitsProcessed(), 0, "whole-value check: nothing consumed" );
        } );

        test( "int128: the golden pin — ±2^70 bounds, the three-group structure 32,32,8", () -> {
            Int128Value goldenMin = Int128Value.fromLong( 1 ).shiftLeft( 70 ).negate();
            Int128Value goldenMax = Int128Value.fromLong( 1 ).shiftLeft( 70 );
            Int128Value goldenValue = Int128Value.fromLong( 0x0123456789ABCDEFL ).negate();

            byte[] buffer = new byte[16 + 8];
            WriteStream writer = new WriteStream( buffer, 16 );
            check( writer.serializeInt128( new Ref<>( goldenValue ), goldenMin, goldenMax ) );
            writer.flush();
            checkEqual( writer.getBitsProcessed(), 72, "72 bits" );
            Harness.checkBytesEqual( buffer, GOLDEN_INT128_BYTES, GOLDEN_INT128_BYTES.length, "golden bytes" );

            byte[] readBuffer = new byte[16 + 8];
            System.arraycopy( GOLDEN_INT128_BYTES, 0, readBuffer, 0, GOLDEN_INT128_BYTES.length );
            ReadStream reader = new ReadStream( readBuffer, 16 );
            Ref<Int128Value> readBack = new Ref<>( Int128Value.ZERO );
            check( reader.serializeInt128( readBack, goldenMin, goldenMax ) );
            check( readBack.value.equals( goldenValue ), "golden decode" );
        } );
    }
}
