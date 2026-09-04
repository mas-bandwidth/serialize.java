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

        test( "terminality: a refusal latches, and every later read fails and writes nothing", () -> {
            // failure before any consumption
            checkTerminal( "past the end at bit zero", failed( reader -> {
                check( !reader.serializeBits( new IntRef(), 32 ), "32 bits from an empty stream refused" );
            }, new byte[8], 0 ) );

            // failure after partial consumption: four bits land, the rest does not
            checkTerminal( "past the end mid-stream", failed( reader -> {
                check( reader.serializeBits( new IntRef(), 4 ), "the first four bits land" );
                check( !reader.serializeBits( new IntRef(), 32 ), "32 more from one byte refused" );
            }, new byte[9], 1 ) );

            // failure on range headroom: 255 smuggled into the eight bits of [0,200]
            byte[] headroom = new byte[9];
            headroom[0] = (byte) 0xFF;
            checkTerminal( "an offset above the range", failed( reader -> {
                check( !reader.serializeInt( new IntRef(), 0, 200 ), "255 over [0,200] refused" );
            }, headroom, 1 ) );

            // failure on alignment: a nonzero padding bit
            byte[] alignment = new byte[16];
            WriteStream aligning = new WriteStream( alignment, 8 );
            check( aligning.serializeBits( new IntRef( 1 ), 1 ) );
            check( aligning.serializeAlign() );
            check( aligning.serializeBits( new IntRef( 0x55 ), 8 ) );
            aligning.flush();
            alignment[0] |= (byte) 0x80;
            checkTerminal( "nonzero alignment padding", failed( reader -> {
                check( reader.serializeBits( new IntRef(), 1 ), "the leading bit lands" );
                check( !reader.serializeAlign(), "doctored padding refused" );
            }, alignment, (int) aligning.getBytesProcessed() ) );

            // failure on a malformed string: 0xFF appears nowhere in well-formed UTF-8
            byte[] malformed = new byte[72];
            WriteStream writing = new WriteStream( malformed, 64 );
            check( writing.serializeInt( new IntRef( 3 ), 0, 255 ) );
            check( writing.serializeBytes( GoldenWire.bytes( 0xFF, 0xFE, 0xFF ), 3 ) );
            writing.flush();
            checkTerminal( "a malformed string payload", failed( reader -> {
                check( !reader.serializeString( new Ref<>( "" ), 256 ), "0xFF payload refused" );
            }, malformed, (int) writing.getBytesProcessed() ) );

            // failure on int_relative: the one-bit tier reconstructs past the domain
            byte[] relative = new byte[9];
            relative[0] = 0x01;
            checkTerminal( "an int_relative read past the domain", failed( reader -> {
                check( !reader.serializeIntRelative( Integer.MAX_VALUE, new IntRef() ), "past the domain refused" );
            }, relative, 1 ) );
        } );

        test( "terminality: reset clears the latch", () -> {
            byte[] buffer = new byte[9];
            buffer[0] = 0x0F;
            ReadStream reader = new ReadStream( buffer, 1 );
            check( !reader.serializeBits( new IntRef(), 32 ), "32 bits from one byte refused" );
            check( reader.isFailed(), "the latch is set" );

            reader.reset( buffer, 1 );
            check( !reader.isFailed(), "reset cleared the latch" );
            IntRef value = new IntRef();
            check( reader.serializeBits( value, 4 ), "the stream reads again" );
            checkEqual( value.value, 0x0F, "the value after reset" );
        } );

        // STANDARD.md bounds a bits field by value < 2^bits at every width in
        // [1,64]. The write path narrows a 64-bit value to a 32-bit group at
        // widths of 32 or fewer, so the bound must be checked on the caller's
        // value FIRST: a check after the narrowing is handed a value the
        // narrowing already made legal, and 2^32 + 5 arrives as 5, which fits
        // four bits. Every writing stream carries the same check.
        // The contract is an assert, so it exists only in the checked shape.
        if ( assertionsEnabled() )
        {
            test( "write contract: a bits64 value is bounded at the caller's width, before it narrows", () -> {
                WriteStream writer = new WriteStream( new byte[16], 16 );
                check( fires( () -> writer.serializeBits64( new LongRef( ( 1L << 32 ) + 5 ), 4 ) ),
                       "the write stream must reject a value that does not fit four bits" );

                MeasureStream measure = new MeasureStream();
                check( fires( () -> measure.serializeBits64( new LongRef( ( 1L << 32 ) + 5 ), 4 ) ),
                       "the measure stream must reject a value that does not fit four bits" );

                // the negative control: the value one step inside the bound, which
                // must not fire on either stream
                WriteStream inside = new WriteStream( new byte[16], 16 );
                check( !fires( () -> inside.serializeBits64( new LongRef( 5 ), 4 ) ),
                       "a value that fits four bits must pass" );
                check( !fires( () -> new MeasureStream().serializeBits64( new LongRef( 5 ), 4 ) ),
                       "a value that fits four bits must pass the measure" );

                // and the widest field, where every 64-bit value fits
                check( !fires( () -> new MeasureStream().serializeBits64( new LongRef( -1L ), 64 ) ),
                       "every value fits 64 bits" );
            } );
        }
    }

    private static boolean assertionsEnabled()
    {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    /** Did the body's debug contract fire? */
    private static boolean fires( Runnable body )
    {
        try
        {
            body.run();
            return false;
        }
        catch ( AssertionError error )
        {
            return true;
        }
    }

    /** A value no read below decodes to, so a written destination is visible after a refusal. */
    private static final int SENTINEL = 0x5E5E5E5E;

    /** Runs a body that must fail its stream, and hands the failed stream back. */
    private static ReadStream failed( java.util.function.Consumer<ReadStream> body, byte[] buffer, int bytes )
    {
        ReadStream reader = new ReadStream( buffer, bytes );
        body.accept( reader );
        return reader;
    }

    /**
     * STANDARD.md, "Failure is terminal": a later read on a failed stream must
     * fail, consume no bits and write no destination — including a zero-bit
     * read, which consumes nothing and so cannot be caught by the past-end
     * check alone.
     */
    private static void checkTerminal( String what, ReadStream reader )
    {
        check( reader.isFailed(), what + ": the latch is set" );
        long consumed = reader.getBitsProcessed();

        IntRef destination = new IntRef( SENTINEL );
        check( !reader.serializeBits( destination, 1 ), what + ": a one-bit read fails" );
        checkEqual( destination.value, SENTINEL, what + ": the one-bit read wrote nothing" );

        check( !reader.serializeInt( destination, 7, 7 ), what + ": a zero-bit read fails" );
        checkEqual( destination.value, SENTINEL, what + ": the zero-bit read wrote nothing" );

        BoolRef flag = new BoolRef( true );
        check( !reader.serializeBool( flag ), what + ": a bool read fails" );
        check( flag.value, what + ": the bool read wrote nothing" );

        checkEqual( reader.getBitsProcessed(), consumed, what + ": no bits were consumed after the failure" );
    }
}
