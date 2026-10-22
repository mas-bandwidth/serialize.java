package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** UTF-8 strings: accept controls, real text, and every refusal rule with its reason. */
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
            check( reader.getRefusal() == Refusal.INVALID_UTF8, "as invalid_utf8" );
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
            check( reader.getRefusal() == Refusal.EMBEDDED_NUL, "as embedded_nul" );
        } );

        test( "string: a truncated payload fails the read", () -> {
            byte[] buffer = new byte[64 + 8];
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeString( new Ref<>( "hello world" ), BUFFER_SIZE ) );
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() - 1 );
            check( !reader.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "one byte short refused" );
        } );

        test( "string: the refusal reasons — past_end, value_out_of_range, nonzero_padding", () -> {
            // the length one past the buffer: value_out_of_range
            byte[] length = GoldenWire.bytes( 0x0F, 0, 0, 0, 0, 0, 0, 0, 0 );
            ReadStream tooLong = new ReadStream( length, 1 );
            check( !tooLong.serializeString( new Ref<>( "" ), 15 ), "length 15 over buffer 15 refused" );
            check( tooLong.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "as value_out_of_range" );

            // the payload runs past the end: past_end
            byte[] truncated = GoldenWire.bytes( 0x03, 'a', 'b', 0, 0, 0, 0, 0, 0, 0 );
            ReadStream shortStream = new ReadStream( truncated, 3 );
            check( !shortStream.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "truncated payload refused" );
            check( shortStream.getRefusal() == Refusal.PAST_END, "as past_end" );

            // set padding AND a truncated payload: the align comes first, so nonzero_padding
            byte[] padded = GoldenWire.bytes( 0x83, 'a', 0, 0, 0, 0, 0, 0, 0 );
            ReadStream both = new ReadStream( padded, 2 );
            check( !both.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "set padding before a truncated payload refused" );
            check( both.getRefusal() == Refusal.NONZERO_PADDING, "as nonzero_padding, not past_end" );
        } );

        test( "string: invalid_utf8 and embedded_nul, and NUL wins when a payload breaks both", () -> {
            byte[][] invalid = {
                GoldenWire.bytes( 0xC0, 0xAF ),             // overlong slash
                GoldenWire.bytes( 0xED, 0xA0, 0x80 ),       // an encoded surrogate
                GoldenWire.bytes( 0xF4, 0x90, 0x80, 0x80 ), // above U+10FFFF
                GoldenWire.bytes( 0x80 ),                   // a lone continuation byte
                GoldenWire.bytes( 0xFE ),                   // a byte that never appears in UTF-8
            };
            for ( byte[] payload : invalid )
            {
                ReadStream reader = new ReadStream( stringStream( payload ), 1 + payload.length );
                check( !reader.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "malformed payload refused" );
                check( reader.getRefusal() == Refusal.INVALID_UTF8, "as invalid_utf8" );
            }

            // 02 FF 00: the NUL scan runs over the whole payload before the encoding is checked
            byte[] both = GoldenWire.bytes( 0xFF, 0x00 );
            ReadStream reader = new ReadStream( stringStream( both ), 3 );
            check( !reader.serializeString( new Ref<>( "" ), BUFFER_SIZE ), "the payload is refused" );
            check( reader.getRefusal() == Refusal.EMBEDDED_NUL, "as embedded_nul, not invalid_utf8" );

            // the same three bytes without the NUL are text
            byte[] clean = GoldenWire.bytes( 'a', 'b' );
            ReadStream accepted = new ReadStream( stringStream( clean ), 3 );
            Ref<String> value = new Ref<>( "" );
            check( accepted.serializeString( value, BUFFER_SIZE ), "the twin without the NUL is accepted" );
            check( "ab".equals( value.value ), "and decodes" );
        } );

        test( "string: real text round trips byte for byte — no normalization, no BOM stripping, no replacement", () -> {
            // each payload is the transmitted bytes; the decoded String must
            // re-encode to exactly them
            byte[][] payloads = {
                GoldenWire.bytes( 0xE5, 0x9B, 0xB4, 0xE6, 0xA3, 0x8B ),             // weiqi
                GoldenWire.bytes( 0xEB, 0xB0, 0x94, 0xEB, 0x91, 0x91 ),             // baduk
                GoldenWire.bytes( 0xD8, 0xB4, 0xD8, 0xB7, 0xD8, 0xB1 ),             // Arabic, right to left
                GoldenWire.bytes( 'e', 0xCC, 0x81 ),                                // e and a combining acute, not normalized
                GoldenWire.bytes( 0xC3, 0xA9 ),                                     // the precomposed e acute, distinct
                GoldenWire.bytes( 0xEF, 0xBB, 0xBF, 'a' ),                          // a byte order mark, kept
                GoldenWire.bytes( 0xEF, 0xBF, 0xBF ),                               // U+FFFF, a noncharacter, kept
                GoldenWire.bytes( 0xF0, 0x9F, 0x98, 0x80 ),                         // an emoji, four bytes
                GoldenWire.bytes( 0xF0, 0xA0, 0x80, 0x80 ),                         // CJK Extension B, four bytes
                GoldenWire.bytes( 0xF4, 0x8F, 0xBF, 0xBF ),                         // U+10FFFF, the highest code point
            };
            for ( byte[] payload : payloads )
            {
                ReadStream reader = new ReadStream( stringStream( payload ), 1 + payload.length );
                Ref<String> value = new Ref<>( "" );
                check( reader.serializeString( value, BUFFER_SIZE ), "well-formed text is accepted" );
                byte[] again = value.value.getBytes( java.nio.charset.StandardCharsets.UTF_8 );
                check( java.util.Arrays.equals( again, payload ), "the decoded text is exactly the bytes read" );

                // and the writer emits those bytes back
                byte[] buffer = new byte[40];
                WriteStream writer = new WriteStream( buffer, 32 );
                check( writer.serializeString( value, BUFFER_SIZE ) );
                writer.flush();
                checkEqual( writer.getBytesProcessed(), 1 + payload.length, "the writer emits the same length" );
                Harness.checkBytesEqual( buffer, stringStream( payload ), 1 + payload.length, "the writer emits the same bytes" );
            }
        } );

        // the writer's contract is an assert, so it exists only in the checked shape
        if ( Harness.assertionsEnabled() )
        {
            test( "write contract: a string writer is handed well-formed text with no NUL", () -> {
                String loneHigh = new String( new char[] { 'a', 0xD83D } );
                String loneLow = new String( new char[] { 0xDE00, 'a' } );
                String nul = "a\0b";
                for ( String value : new String[] { loneHigh, loneLow, nul } )
                {
                    check( Harness.fires( () -> new WriteStream( new byte[40], 32 ).serializeString( new Ref<>( value ), BUFFER_SIZE ) ),
                           "the write stream asserts" );
                    check( Harness.fires( () -> new MeasureStream().serializeString( new Ref<>( value ), BUFFER_SIZE ) ),
                           "the measure stream asserts" );
                }
                // the negative control: a well-formed surrogate pair passes
                String pair = new String( new char[] { 0xD83D, 0xDE00 } );
                check( !Harness.fires( () -> new WriteStream( new byte[40], 32 ).serializeString( new Ref<>( pair ), BUFFER_SIZE ) ),
                       "a well-formed pair passes" );
            } );
        }
    }

    // a string field over BUFFER_SIZE 16 at bit zero: the 4-bit length, its
    // align, then the payload, with the slack the reader's fastest path wants
    private static byte[] stringStream( byte[] payload )
    {
        byte[] stream = new byte[1 + payload.length + 8];
        stream[0] = (byte) payload.length;
        System.arraycopy( payload, 0, stream, 1, payload.length );
        return stream;
    }
}
