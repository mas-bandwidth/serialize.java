package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** Stream-level accept and refuse coverage for bits, bits64, int, int64 and bytes. */
final class StreamTests
{
    private StreamTests() {}

    static void run()
    {
        test( "serializeBits: round trips at 1, 4, 11, 24 and 32 bits", () -> {
            byte[] buffer = new byte[32];
            WriteStream writer = new WriteStream( buffer, 24 );
            check( writer.serializeBits( new IntRef( 1 ), 1 ) );
            check( writer.serializeBits( new IntRef( 13 ), 4 ) );
            check( writer.serializeBits( new IntRef( 1445 ), 11 ) );
            check( writer.serializeBits( new IntRef( 11259375 ), 24 ) );
            check( writer.serializeBits( new IntRef( 0xDEADBEEF ), 32 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            IntRef value = new IntRef();
            check( reader.serializeBits( value, 1 ) );
            checkEqual( value.value, 1, "1 bit" );
            check( reader.serializeBits( value, 4 ) );
            checkEqual( value.value, 13, "4 bits" );
            check( reader.serializeBits( value, 11 ) );
            checkEqual( value.value, 1445, "11 bits" );
            check( reader.serializeBits( value, 24 ) );
            checkEqual( value.value, 11259375, "24 bits" );
            check( reader.serializeBits( value, 32 ) );
            checkEqual( value.value, 0xDEADBEEF, "32 bits" );
        } );

        test( "serializeBits: a truncated stream refuses cleanly", () -> {
            byte[] buffer = new byte[16];
            ReadStream reader = new ReadStream( buffer, 1 );
            IntRef value = new IntRef();
            check( reader.serializeBits( value, 8 ) );
            check( !reader.serializeBits( value, 8 ), "9th bit refused" );
        } );

        test( "serializeBits64: low 32-bit group first, then the remainder", () -> {
            byte[] buffer = new byte[24];
            WriteStream writer = new WriteStream( buffer, 16 );
            check( writer.serializeBits64( new LongRef( 0x123456789ABCDEF0L ), 64 ) );
            check( writer.serializeBits64( new LongRef( 0x1FFFFFFFFFFL ), 41 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            LongRef value = new LongRef();
            check( reader.serializeBits64( value, 64 ) );
            checkEqual( value.value, 0x123456789ABCDEF0L, "64 bits" );
            check( reader.serializeBits64( value, 41 ) );
            checkEqual( value.value, 0x1FFFFFFFFFFL, "41 bits" );
        } );

        test( "serializeBits64: byte identity with two 32-bit groups", () -> {
            byte[] bufferA = new byte[16];
            byte[] bufferB = new byte[16];
            WriteStream a = new WriteStream( bufferA, 8 );
            check( a.serializeBits64( new LongRef( 0x123456789ABCDEF0L ), 64 ) );
            a.flush();
            WriteStream b = new WriteStream( bufferB, 8 );
            check( b.serializeBits( new IntRef( 0x9ABCDEF0 ), 32 ) );
            check( b.serializeBits( new IntRef( 0x12345678 ), 32 ) );
            b.flush();
            Harness.checkBytesEqual( bufferA, bufferB, 8, "identical wire" );
        } );

        test( "serializeBits64: truncation refuses per 32-bit group", () -> {
            // 40 bits available: the low group reads, the high group refuses
            byte[] buffer = new byte[16];
            ReadStream reader = new ReadStream( buffer, 5 );
            LongRef value = new LongRef();
            check( !reader.serializeBits64( value, 64 ), "64 bits from 40 refused" );
            checkEqual( reader.getBitsProcessed(), 32, "the low group was consumed before the refusal" );
        } );

        test( "serializeInt: round trips across the range shapes", () -> {
            byte[] buffer = new byte[32];
            WriteStream writer = new WriteStream( buffer, 32 );
            check( writer.serializeInt( new IntRef( -37 ), -100, 100 ) );
            check( writer.serializeInt( new IntRef( 0 ), 0, 7 ) );
            check( writer.serializeInt( new IntRef( 8 ), 0, 8 ) );
            check( writer.serializeInt( new IntRef( -123456789 ), Integer.MIN_VALUE, Integer.MAX_VALUE ) );
            check( writer.serializeInt( new IntRef( 42 ), 42, 42 ) );      // degenerate: zero bits
            long bitsBeforeFlush = writer.getBitsProcessed();
            checkEqual( bitsBeforeFlush, 8 + 3 + 4 + 32, "bit cost of the shapes" );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            IntRef value = new IntRef();
            check( reader.serializeInt( value, -100, 100 ) );
            checkEqual( value.value, -37, "ranged" );
            check( reader.serializeInt( value, 0, 7 ) );
            checkEqual( value.value, 0, "3 bits" );
            check( reader.serializeInt( value, 0, 8 ) );
            checkEqual( value.value, 8, "4 bits" );
            check( reader.serializeInt( value, Integer.MIN_VALUE, Integer.MAX_VALUE ) );
            checkEqual( value.value, -123456789, "full range" );
            check( reader.serializeInt( value, 42, 42 ) );
            checkEqual( value.value, 42, "degenerate recovers the value from the range alone" );
        } );

        test( "serializeInt: an out-of-range value smuggled into the bit headroom refuses", () -> {
            byte[] buffer = new byte[16];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 7 ), 3 ) );        // 7 > the range [0,5]'s span
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            IntRef value = new IntRef();
            check( !reader.serializeInt( value, 0, 5 ), "7 in [0,5] refused" );
        } );

        test( "serializeInt: truncated refuses", () -> {
            byte[] buffer = new byte[16];
            ReadStream reader = new ReadStream( buffer, 0 );
            IntRef value = new IntRef();
            check( !reader.serializeInt( value, 0, 100 ), "empty stream refused" );
        } );

        test( "serializeInt64: round trips including full range and degenerate", () -> {
            byte[] buffer = new byte[40];
            WriteStream writer = new WriteStream( buffer, 40 );
            check( writer.serializeInt64( new LongRef( -4999999999L ), -5000000000L, 5000000000L ) );
            check( writer.serializeInt64( new LongRef( Long.MIN_VALUE ), Long.MIN_VALUE, Long.MAX_VALUE ) );
            check( writer.serializeInt64( new LongRef( Long.MAX_VALUE ), Long.MIN_VALUE, Long.MAX_VALUE ) );
            check( writer.serializeInt64( new LongRef( 7L ), 7L, 7L ) );
            checkEqual( writer.getBitsProcessed(), 34 + 64 + 64, "bit cost" );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            LongRef value = new LongRef();
            check( reader.serializeInt64( value, -5000000000L, 5000000000L ) );
            checkEqual( value.value, -4999999999L, "34-bit range" );
            check( reader.serializeInt64( value, Long.MIN_VALUE, Long.MAX_VALUE ) );
            checkEqual( value.value, Long.MIN_VALUE, "full range min" );
            check( reader.serializeInt64( value, Long.MIN_VALUE, Long.MAX_VALUE ) );
            checkEqual( value.value, Long.MAX_VALUE, "full range max" );
            check( reader.serializeInt64( value, 7L, 7L ) );
            checkEqual( value.value, 7L, "degenerate" );
        } );

        test( "serializeInt64: out-of-range offset refuses; truncation refuses whole-value", () -> {
            byte[] buffer = new byte[16];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits64( new LongRef( 400 ), 9 ) );       // [0,300] costs 9 bits; 400 is out of range
            writer.flush();
            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            LongRef value = new LongRef();
            check( !reader.serializeInt64( value, 0, 300 ), "400 in [0,300] refused" );

            // whole-value truncation check: nothing is consumed on refusal
            ReadStream truncated = new ReadStream( new byte[16], 4 );
            check( !truncated.serializeInt64( value, Long.MIN_VALUE, Long.MAX_VALUE ), "64 bits from 32 refused" );
            checkEqual( truncated.getBitsProcessed(), 0, "refused before consuming anything" );
        } );

        test( "serializeBytes: aligns first, zero count included, and refuses truncation in bytes", () -> {
            byte[] buffer = new byte[32];
            WriteStream writer = new WriteStream( buffer, 24 );
            check( writer.serializeBits( new IntRef( 1 ), 1 ) );
            check( writer.serializeBytes( new byte[0], 0 ) );               // aligns, writes nothing else
            checkEqual( writer.getBitsProcessed(), 8, "zero-count bytes still aligned" );
            byte[] payload = GoldenWire.bytes( 1, 2, 3, 4, 5 );
            check( writer.serializeBytes( payload, 5 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            IntRef bit = new IntRef();
            check( reader.serializeBits( bit, 1 ) );
            check( reader.serializeBytes( new byte[0], 0 ), "zero-count align verifies" );
            byte[] readBack = new byte[5];
            check( reader.serializeBytes( readBack, 5 ) );
            Harness.checkBytesEqual( readBack, payload, 5, "payload" );

            // capacity is priced in bytes: 6 bytes from a 5-byte stream refuses
            ReadStream small = new ReadStream( buffer, 5 );
            check( !small.serializeBytes( new byte[6], 6 ), "6 bytes from 5 refused" );
        } );

        test( "serializeBool and unsigned helpers round trip", () -> {
            byte[] buffer = new byte[24];
            WriteStream writer = new WriteStream( buffer, 24 );
            check( writer.serializeBool( new BoolRef( true ) ) );
            check( writer.serializeBool( new BoolRef( false ) ) );
            check( writer.serializeUint8( new IntRef( 0xFF ) ) );
            check( writer.serializeUint16( new IntRef( 0x1234 ) ) );
            check( writer.serializeUint32( new IntRef( 0xDEADBEEF ) ) );
            check( writer.serializeUint64( new LongRef( -1L ) ) );          // 2^64-1, bit transparent in the long
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            BoolRef flag = new BoolRef();
            IntRef small = new IntRef();
            LongRef big = new LongRef();
            check( reader.serializeBool( flag ) );
            check( flag.value, "true" );
            check( reader.serializeBool( flag ) );
            check( !flag.value, "false" );
            check( reader.serializeUint8( small ) );
            checkEqual( small.value, 0xFF, "uint8" );
            check( reader.serializeUint16( small ) );
            checkEqual( small.value, 0x1234, "uint16" );
            check( reader.serializeUint32( small ) );
            checkEqual( small.value, 0xDEADBEEF, "uint32" );
            check( reader.serializeUint64( big ) );
            checkEqual( big.value, -1L, "uint64 max" );
        } );

        test( "align: reader refuses doctored padding through the stream interface", () -> {
            byte[] buffer = new byte[16];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 1 ), 1 ) );
            check( writer.serializeAlign() );
            check( writer.serializeBits( new IntRef( 0x55 ), 8 ) );
            writer.flush();

            buffer[0] |= (byte) 0x80;                                       // set a padding bit
            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            IntRef value = new IntRef();
            check( reader.serializeBits( value, 1 ) );
            check( !reader.serializeAlign(), "doctored padding refused" );
        } );
    }
}
