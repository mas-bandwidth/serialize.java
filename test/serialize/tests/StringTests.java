package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** UTF-8 strings and wide strings: accept controls and every refusal rule. */
final class StringTests
{
    private StringTests() {}

    static final int BUFFER_SIZE = 16;

    static void run()
    {
        test( "string: round trips, including the multi-byte UTF-8 control", () -> {
            // h, e-acute, euro sign, U+1F600 — 2, 3 and 4 byte sequences
            // h, e-acute, euro sign, U+1F600 from explicit code points, so the
            // source file encoding can never change the test
            String multiByte = "h\u00E9\u20AC" + new String( new char[] { 0xD83D, 0xDE00 } );
            String[] values = { "", "a", "golden", multiByte, "fifteen chars.." };
            for ( String value : values )
            {
                byte[] buffer = new byte[40];
                WriteStream writer = new WriteStream( buffer, 32 );
                Ref<String> written = new Ref<>( value );
                check( writer.serializeString( written, BUFFER_SIZE ) );
                writer.flush();

                MeasureStream measure = new MeasureStream();
                check( measure.serializeString( new Ref<>( value ), BUFFER_SIZE ) );
                check( measure.getBitsProcessed() >= writer.getBitsProcessed(), "measure bounds the write" );

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                Ref<String> readBack = new Ref<>( "" );
                check( reader.serializeString( readBack, BUFFER_SIZE ) );
                check( value.equals( readBack.value ), "round trip \"" + value + "\"" );
            }
        } );

        test( "string: an unaligned start aligns inside the operation", () -> {
            byte[] buffer = new byte[40];
            WriteStream writer = new WriteStream( buffer, 32 );
            check( writer.serializeBits( new IntRef( 1 ), 1 ) );
            check( writer.serializeString( new Ref<>( "abc" ), BUFFER_SIZE ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            IntRef bit = new IntRef();
            check( reader.serializeBits( bit, 1 ) );
            Ref<String> readBack = new Ref<>( "" );
            check( reader.serializeString( readBack, BUFFER_SIZE ) );
            check( "abc".equals( readBack.value ), "aligned round trip" );
        } );

        test( "string: invalid UTF-8 fails the read", () -> {
            // 0xFF can never appear anywhere in well-formed UTF-8
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 3 ), 0, BUFFER_SIZE - 1 ) );
            check( writer.serializeBytes( GoldenWire.bytes( 0xFF, 0xFE, 0xFF ), 3 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "0xFF payload refused" );
        } );

        test( "string: truncated UTF-8 — a 3-byte lead as the final transmitted byte — fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 2 ), 0, BUFFER_SIZE - 1 ) );
            check( writer.serializeBytes( GoldenWire.bytes( 'a', 0xE2 ), 2 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "truncated sequence refused" );
        } );

        test( "string: an interior NUL — the two-lengths smuggling primitive — fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 3 ), 0, BUFFER_SIZE - 1 ) );
            check( writer.serializeBytes( GoldenWire.bytes( 'a', 0x00, 'b' ), 3 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "interior NUL refused" );
        } );

        test( "string: a truncated payload fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeString( new Ref<>( "hello world" ), BUFFER_SIZE ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() - 1 );
            check( !reader.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "one byte short refused" );
        } );

        test( "wstring: round trips — BMP text and astral surrogate pairs", () -> {
            String cyrillic = new String( new char[] { 0x043C, 0x0438, 0x0440 } );
            String astral = new String( new char[] { 'a', 0xD83D, 0xDE00, 'b' } );   // 4 code units, 3 code points
            String[] values = { "", "abc", cyrillic, astral };
            for ( String value : values )
            {
                byte[] buffer = new byte[40];
                WriteStream writer = new WriteStream( buffer, 40 );
                check( writer.serializeWideString( new Ref<>( value ), 8 ) );
                writer.flush();

                MeasureStream measure = new MeasureStream();
                check( measure.serializeWideString( new Ref<>( value ), 8 ) );
                checkEqual( measure.getBitsProcessed(), writer.getBitsProcessed(),
                            "wstring measures exactly: no alignment anywhere in the operation" );

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                Ref<String> readBack = new Ref<>( "" );
                check( reader.serializeWideString( readBack, 8 ) );
                check( value.equals( readBack.value ), "round trip" );
            }
        } );

        test( "wstring: no alignment — the worked example's 13-byte run decodes at bit 3", () -> {
            // STANDARD.md's worked example: three characters in a wchar_t[8] buffer
            byte[] expected = GoldenWire.bytes( 0xE3, 0x21, 0x00, 0x00, 0xC0, 0x21, 0x00, 0x00, 0x00, 0x22, 0x00, 0x00, 0x00 );
            String value = new String( new char[] { 0x043C, 0x0438, 0x0440 } );

            byte[] buffer = new byte[24];
            WriteStream writer = new WriteStream( buffer, 16 );
            check( writer.serializeWideString( new Ref<>( value ), 8 ) );
            checkEqual( writer.getBitsProcessed(), 3 + 3 * 32, "99 bits: 3-bit length, no align, three groups" );
            writer.flush();
            Harness.checkBytesEqual( buffer, expected, 13, "the worked example's bytes" );
        } );

        test( "wstring: a high surrogate followed by a non-surrogate fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 2 ), 0, 7 ) );
            check( writer.serializeBits( new IntRef( 0xD800 ), 32 ) );
            check( writer.serializeBits( new IntRef( 0x0041 ), 32 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeWideString( new Ref<>( "" ), 8 ), "unpaired high refused" );
        } );

        test( "wstring: a low surrogate with no high before it fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 1 ), 0, 7 ) );
            check( writer.serializeBits( new IntRef( 0xDC00 ), 32 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeWideString( new Ref<>( "" ), 8 ), "lone low refused" );
        } );

        test( "wstring: a dangling high surrogate as the final group fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 1 ), 0, 7 ) );
            check( writer.serializeBits( new IntRef( 0xD83D ), 32 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeWideString( new Ref<>( "" ), 8 ), "dangling high refused" );
        } );

        test( "wstring: an interior NUL group fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 3 ), 0, 7 ) );
            check( writer.serializeBits( new IntRef( 0x0041 ), 32 ) );
            check( writer.serializeBits( new IntRef( 0x0000 ), 32 ) );
            check( writer.serializeBits( new IntRef( 0x0042 ), 32 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeWideString( new Ref<>( "" ), 8 ), "interior NUL group refused" );
        } );

        test( "wstring: a group above 0xFFFF is not a UTF-16 code unit and fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 1 ), 0, 7 ) );
            check( writer.serializeBits( new IntRef( 0x10000 ), 32 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            check( !reader.serializeWideString( new Ref<>( "" ), 8 ), "32-bit group above 0xFFFF refused" );
        } );

        test( "wstring: a well-formed surrogate pair is accepted — astral text travels as pairs", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeInt( new IntRef( 2 ), 0, 7 ) );
            check( writer.serializeBits( new IntRef( 0xD83D ), 32 ) );
            check( writer.serializeBits( new IntRef( 0xDE00 ), 32 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            Ref<String> readBack = new Ref<>( "" );
            check( reader.serializeWideString( readBack, 8 ), "pair accepted" );
            check( new String( new char[] { 0xD83D, 0xDE00 } ).equals( readBack.value ), "recombined as the pair" );
        } );

        test( "wstring: a truncated group list fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeWideString( new Ref<>( "abcdefg" ), 8 ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() - 1 );
            check( !reader.serializeWideString( new Ref<>( "" ), 8 ), "one byte short refused" );
        } );
    }
}
