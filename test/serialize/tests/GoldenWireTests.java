package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/**
 * The golden battery's spine: byte-exact write, exact decode, the trailing
 * bits and past-end doctrines, and the full sabotage sweep.
 */
final class GoldenWireTests
{
    private GoldenWireTests() {}

    static void run()
    {
        final byte[] golden = GoldenWire.GOLDEN_WIRE_BYTES;
        final long goldenBits = GoldenWire.GOLDEN_BITS;

        test( "golden wire, write side: the golden values produce exactly the golden bytes", () -> {
            byte[] buffer = new byte[256];
            WriteStream writer = new WriteStream( buffer, 256 );
            check( GoldenWire.serialize( writer, GoldenWire.goldenData() ) );
            writer.flush();
            checkEqual( writer.getBytesProcessed(), golden.length, "112 bytes" );
            checkEqual( writer.getBitsProcessed(), goldenBits, "891 consumed bits" );
            Harness.checkBytesEqual( buffer, golden, golden.length, "golden bytes" );
        } );

        test( "golden wire, read side: the golden bytes decode to the expected values, forever", () -> {
            byte[] buffer = new byte[256];
            System.arraycopy( golden, 0, buffer, 0, golden.length );
            ReadStream reader = new ReadStream( buffer, golden.length );
            GoldenWire.Data data = new GoldenWire.Data();
            check( GoldenWire.serialize( reader, data ) );
            checkEqual( reader.getBitsProcessed(), goldenBits, "891 consumed bits" );
            check( GoldenWire.matchesGolden( data ), "every field decodes exactly, compressed float included" );
        } );

        test( "trailing bits: the writer zeroes them and the reader is indifferent, small stream", () -> {
            byte[] buffer = new byte[64];
            java.util.Arrays.fill( buffer, (byte) 0xFF );       // the zeros must come from the writer

            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeBits( new IntRef( 0xDEADBEEF ), 32 ) );
            check( writer.serializeBits( new IntRef( 5 ), 3 ) );
            writer.flush();

            int bytesWritten = (int) writer.getBytesProcessed();
            int bitsInFinalByte = (int) ( writer.getBitsProcessed() % 8 );
            checkEqual( bitsInFinalByte, 3, "the stream really does end unaligned" );
            int trailingMask = ( 0xFF << bitsInFinalByte ) & 0xFF;
            checkEqual( buffer[bytesWritten - 1] & trailingMask, 0, "writers must write zero" );

            // set every trailing bit: the doctored stream must be accepted and decode the same
            buffer[bytesWritten - 1] |= (byte) trailingMask;
            ReadStream reader = new ReadStream( buffer, bytesWritten );
            IntRef head = new IntRef();
            check( reader.serializeBits( head, 32 ) );
            checkEqual( head.value, 0xDEADBEEF, "head unchanged" );
            IntRef tail = new IntRef();
            check( reader.serializeBits( tail, 3 ) );
            checkEqual( tail.value, 5, "tail unchanged" );
        } );

        test( "trailing bits: a doctored golden stream decodes identically", () -> {
            int bitsInFinalByte = (int) ( goldenBits % 8 );
            check( bitsInFinalByte != 0, "golden ends unaligned, so this test can discriminate" );
            int trailingMask = ( 0xFF << bitsInFinalByte ) & 0xFF;
            checkEqual( golden[golden.length - 1] & trailingMask, 0, "the pinned emission met the writer obligation" );

            byte[] cleanBuffer = new byte[256];
            System.arraycopy( golden, 0, cleanBuffer, 0, golden.length );
            ReadStream cleanReader = new ReadStream( cleanBuffer, golden.length );
            GoldenWire.Data cleanData = new GoldenWire.Data();
            check( GoldenWire.serialize( cleanReader, cleanData ) );

            byte[] doctoredBuffer = new byte[256];
            System.arraycopy( golden, 0, doctoredBuffer, 0, golden.length );
            doctoredBuffer[golden.length - 1] |= (byte) trailingMask;       // set every trailing bit

            ReadStream doctoredReader = new ReadStream( doctoredBuffer, golden.length );
            GoldenWire.Data doctoredData = new GoldenWire.Data();
            check( GoldenWire.serialize( doctoredReader, doctoredData ), "readers must not reject" );
            check( GoldenWire.matchesGolden( doctoredData ), "and must decode identically" );
            checkEqual( doctoredReader.getBitsProcessed(), cleanReader.getBitsProcessed(), "same bits consumed" );
        } );

        test( "past-end poison: bytes past the stream end are never interpreted, accept path", () -> {
            byte[] cleanBuffer = new byte[256];
            byte[] poisonBuffer = new byte[256];
            java.util.Arrays.fill( poisonBuffer, (byte) 0xFF );     // poison the loaded-but-never-interpreted window
            System.arraycopy( golden, 0, cleanBuffer, 0, golden.length );
            System.arraycopy( golden, 0, poisonBuffer, 0, golden.length );

            ReadStream cleanReader = new ReadStream( cleanBuffer, golden.length );
            GoldenWire.Data cleanData = new GoldenWire.Data();
            check( GoldenWire.serialize( cleanReader, cleanData ) );

            ReadStream poisonReader = new ReadStream( poisonBuffer, golden.length );
            GoldenWire.Data poisonData = new GoldenWire.Data();
            check( GoldenWire.serialize( poisonReader, poisonData ) );

            check( GoldenWire.matchesGolden( cleanData ), "clean decodes the golden values" );
            check( GoldenWire.matchesGolden( poisonData ), "poison decodes the golden values — byte-identical decode" );
            checkEqual( poisonReader.getBitsProcessed(), cleanReader.getBitsProcessed(), "same bits consumed" );
        } );

        test( "past-end poison: a truncated stream refuses identically regardless of the tail", () -> {
            int truncatedBytes = golden.length - 1;

            byte[] cleanBuffer = new byte[256];
            byte[] poisonBuffer = new byte[256];
            java.util.Arrays.fill( poisonBuffer, (byte) 0xFF );
            System.arraycopy( golden, 0, cleanBuffer, 0, truncatedBytes );
            System.arraycopy( golden, 0, poisonBuffer, 0, truncatedBytes );

            ReadStream cleanReader = new ReadStream( cleanBuffer, truncatedBytes );
            GoldenWire.Data cleanData = new GoldenWire.Data();
            check( !GoldenWire.serialize( cleanReader, cleanData ), "clean refuses" );

            ReadStream poisonReader = new ReadStream( poisonBuffer, truncatedBytes );
            GoldenWire.Data poisonData = new GoldenWire.Data();
            check( !GoldenWire.serialize( poisonReader, poisonData ), "poison refuses" );

            checkEqual( poisonReader.getBitsProcessed(), cleanReader.getBitsProcessed(), "refused at the same point" );
            check( partialStateMatches( cleanData, poisonData ), "with identical partial state" );
        } );

        test( "sabotage sweep: every consumed bit is load bearing, every trailing bit is not, nothing throws", () -> {
            // 891 consumed bits: flipping any single one must make the decode refuse
            // or produce different values. 5 trailing bits: flipping any must change
            // nothing. Hostile data never throws anywhere in the 896 doctored streams.
            for ( int bit = 0; bit < golden.length * 8; bit++ )
            {
                byte[] doctoredBuffer = new byte[256];
                System.arraycopy( golden, 0, doctoredBuffer, 0, golden.length );
                doctoredBuffer[bit >> 3] ^= (byte) ( 1 << ( bit & 7 ) );

                ReadStream reader = new ReadStream( doctoredBuffer, golden.length );
                GoldenWire.Data data = new GoldenWire.Data();
                boolean ok = GoldenWire.serialize( reader, data );
                boolean matches = ok && GoldenWire.matchesGolden( data );

                if ( bit < goldenBits )
                {
                    check( !matches, "consumed bit " + bit + " flipped: the doctored stream still decoded the golden values" );
                }
                else
                {
                    check( matches && reader.getBitsProcessed() == goldenBits,
                           "trailing bit " + bit + " flipped: the doctored stream was not accepted identically" );
                }
            }
        } );
    }

    // field-for-field equality of two partially decoded messages, for the
    // refusal-path poison comparison (the reference memcmps the structs)
    private static boolean partialStateMatches( GoldenWire.Data a, GoldenWire.Data b )
    {
        if ( a.bits4.value != b.bits4.value ) return false;
        if ( a.bits11.value != b.bits11.value ) return false;
        if ( a.bits24.value != b.bits24.value ) return false;
        if ( a.bits32.value != b.bits32.value ) return false;
        if ( a.intSmall.value != b.intSmall.value ) return false;
        if ( a.intFull.value != b.intFull.value ) return false;
        if ( a.flag.value != b.flag.value ) return false;
        if ( Float.floatToRawIntBits( a.floatValue.value ) != Float.floatToRawIntBits( b.floatValue.value ) ) return false;
        if ( Float.floatToRawIntBits( a.compressedFloatValue.value ) != Float.floatToRawIntBits( b.compressedFloatValue.value ) ) return false;
        if ( Double.doubleToRawLongBits( a.doubleValue.value ) != Double.doubleToRawLongBits( b.doubleValue.value ) ) return false;
        if ( a.uint8Value.value != b.uint8Value.value ) return false;
        if ( a.uint16Value.value != b.uint16Value.value ) return false;
        if ( a.uint32Value.value != b.uint32Value.value ) return false;
        if ( a.uint64Value.value != b.uint64Value.value ) return false;
        if ( a.relativeNear.value != b.relativeNear.value ) return false;
        if ( a.relativeFar.value != b.relativeFar.value ) return false;
        for ( int i = 0; i < 7; i++ )
        {
            if ( a.bytes[i] != b.bytes[i] ) return false;
        }
        if ( !a.string.value.equals( b.string.value ) ) return false;
        if ( !a.wstring.value.equals( b.wstring.value ) ) return false;
        if ( a.fixedQ8_8.value != b.fixedQ8_8.value ) return false;
        if ( a.fixedQ16_16.value != b.fixedQ16_16.value ) return false;
        if ( a.fixedQ48_16.value != b.fixedQ48_16.value ) return false;
        if ( a.fixedQ16_16Unsigned.value != b.fixedQ16_16Unsigned.value ) return false;
        if ( !a.fixedQ112_16Wide.value.equals( b.fixedQ112_16Wide.value ) ) return false;
        return a.fixedQ64_64Wide.value.equals( b.fixedQ64_64Wide.value );
    }
}
