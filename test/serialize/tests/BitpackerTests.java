package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** The bitpacker, bit width helpers and zig-zag, ported from serialize.h. */
final class BitpackerTests
{
    private BitpackerTests() {}

    static void run()
    {
        test( "bitpacker: write then read the reference value sequence", () -> {
            final int bufferSize = 256;
            byte[] buffer = new byte[bufferSize];

            BitWriter writer = new BitWriter( buffer, bufferSize );
            checkEqual( writer.getBitsWritten(), 0, "bits written before writing" );
            checkEqual( writer.getBytesWritten(), 0, "bytes written before writing" );
            checkEqual( writer.getBitsAvailable(), bufferSize * 8, "bits available before writing" );

            writer.writeBits( 0, 1 );
            writer.writeBits( 1, 1 );
            writer.writeBits( 10, 8 );
            writer.writeBits( 255, 8 );
            writer.writeBits( 1000, 10 );
            writer.writeBits( 50000, 16 );
            writer.writeBits( 9999999, 32 );
            writer.flushBits();

            final int bitsWritten = 1 + 1 + 8 + 8 + 10 + 16 + 32;
            checkEqual( writer.getBytesWritten(), 10, "bytes written" );
            checkEqual( writer.getBitsWritten(), bitsWritten, "bits written" );
            checkEqual( writer.getBitsAvailable(), bufferSize * 8 - bitsWritten, "bits available" );

            BitReader reader = new BitReader( buffer, (int) writer.getBytesWritten() );
            checkEqual( reader.getBitsRead(), 0, "bits read before reading" );
            checkEqual( reader.getBitsRemaining(), writer.getBytesWritten() * 8, "bits remaining before reading" );

            checkEqual( reader.readBits( 1 ), 0, "a" );
            checkEqual( reader.readBits( 1 ), 1, "b" );
            checkEqual( reader.readBits( 8 ), 10, "c" );
            checkEqual( reader.readBits( 8 ), 255, "d" );
            checkEqual( reader.readBits( 10 ), 1000, "e" );
            checkEqual( reader.readBits( 16 ), 50000, "f" );
            checkEqual( reader.readBits( 32 ), 9999999, "g" );

            checkEqual( reader.getBitsRead(), bitsWritten, "bits read" );
            checkEqual( reader.getBitsRemaining(), writer.getBytesWritten() * 8 - bitsWritten, "bits remaining" );
        } );

        test( "bitpacker: values split across the 64-bit word boundary survive", () -> {
            byte[] buffer = new byte[64];
            BitWriter writer = new BitWriter( buffer, 64 );
            // 40 bits of prefix, then a 32-bit value straddling the first word boundary
            writer.writeBits( 0x12345, 20 );
            writer.writeBits( 0xABCDE, 20 );
            writer.writeBits( 0xDEADBEEF, 32 );
            writer.writeBits( 0x7F, 7 );
            writer.flushBits();

            BitReader reader = new BitReader( buffer, (int) writer.getBytesWritten() );
            checkEqual( reader.readBits( 20 ), 0x12345, "first" );
            checkEqual( reader.readBits( 20 ), 0xABCDE, "second" );
            checkEqual( reader.readBits( 32 ) & 0xFFFFFFFFL, 0xDEADBEEFL, "straddling value" );
            checkEqual( reader.readBits( 7 ), 0x7F, "tail" );
        } );

        test( "bitpacker: little-endian word layout on the wire", () -> {
            byte[] buffer = new byte[8];
            BitWriter writer = new BitWriter( buffer, 8 );
            writer.writeBits( 0x04030201, 32 );
            writer.flushBits();
            Harness.checkBytesEqual( buffer, GoldenWire.bytes( 0x01, 0x02, 0x03, 0x04 ), 4, "wire bytes" );
        } );

        test( "bitpacker: writeBytes fuses through an aligned mid-stream position", () -> {
            byte[] buffer = new byte[64];
            BitWriter writer = new BitWriter( buffer, 64 );
            writer.writeBits( 0xAB, 8 );
            byte[] payload = GoldenWire.bytes( 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99 );
            writer.writeBytes( payload, 9 );        // crosses the first word boundary
            writer.writeBits( 0x3F, 6 );            // packs into the reloaded tail word
            writer.flushBits();

            BitReader reader = new BitReader( buffer, (int) writer.getBytesWritten() );
            checkEqual( reader.readBits( 8 ), 0xAB, "head" );
            byte[] readBack = new byte[9];
            reader.readBytes( readBack, 9 );
            Harness.checkBytesEqual( readBack, payload, 9, "payload" );
            checkEqual( reader.readBits( 6 ), 0x3F, "tail after payload" );
        } );

        test( "bitpacker: align pads zeros to the byte boundary and is a no-op when aligned", () -> {
            byte[] buffer = new byte[16];
            BitWriter writer = new BitWriter( buffer, 16 );
            writer.writeBits( 1, 1 );
            checkEqual( writer.getAlignBits(), 7, "align bits at bit 1" );
            writer.writeAlign();
            checkEqual( writer.getBitsWritten(), 8, "aligned to 8" );
            writer.writeAlign();                    // already aligned: nothing written
            checkEqual( writer.getBitsWritten(), 8, "align at boundary writes nothing" );
            writer.flushBits();

            BitReader reader = new BitReader( buffer, (int) writer.getBytesWritten() );
            checkEqual( reader.readBits( 1 ), 1, "bit" );
            check( reader.readAlign(), "align reads back" );
            checkEqual( reader.getBitsRead(), 8, "reader aligned" );
        } );

        test( "bitpacker: readAlign refuses non-zero padding", () -> {
            byte[] buffer = new byte[16];
            buffer[0] = (byte) 0xFF;                // bit 0 set, and non-zero padding above it
            BitReader reader = new BitReader( buffer, 8 );
            checkEqual( reader.readBits( 1 ), 1, "bit" );
            check( !reader.readAlign(), "non-zero padding refused" );
        } );

        test( "bitsRequired: the reference table", () -> {
            checkEqual( SerializeUtil.bitsRequired( 0, 0 ), 0, "0,0" );
            checkEqual( SerializeUtil.bitsRequired( 0, 1 ), 1, "0,1" );
            checkEqual( SerializeUtil.bitsRequired( 0, 2 ), 2, "0,2" );
            checkEqual( SerializeUtil.bitsRequired( 0, 3 ), 2, "0,3" );
            checkEqual( SerializeUtil.bitsRequired( 0, 4 ), 3, "0,4" );
            checkEqual( SerializeUtil.bitsRequired( 0, 5 ), 3, "0,5" );
            checkEqual( SerializeUtil.bitsRequired( 0, 6 ), 3, "0,6" );
            checkEqual( SerializeUtil.bitsRequired( 0, 7 ), 3, "0,7" );
            checkEqual( SerializeUtil.bitsRequired( 0, 8 ), 4, "0,8" );
            checkEqual( SerializeUtil.bitsRequired( 0, 255 ), 8, "0,255" );
            checkEqual( SerializeUtil.bitsRequired( 0, 65535 ), 16, "0,65535" );
            checkEqual( SerializeUtil.bitsRequired( 0, (int) 4294967295L ), 32, "0,2^32-1" );
        } );

        test( "bitsRequired64: the reference table", () -> {
            checkEqual( SerializeUtil.bitsRequired64( 0, 0 ), 0, "0,0" );
            checkEqual( SerializeUtil.bitsRequired64( 0, 1 ), 1, "0,1" );
            checkEqual( SerializeUtil.bitsRequired64( 0, 255 ), 8, "0,255" );
            checkEqual( SerializeUtil.bitsRequired64( 0, 4294967295L ), 32, "0,2^32-1" );
            checkEqual( SerializeUtil.bitsRequired64( 0, 4294967296L ), 33, "0,2^32" );
            checkEqual( SerializeUtil.bitsRequired64( 0, 1L << 40 ), 41, "0,2^40" );
            checkEqual( SerializeUtil.bitsRequired64( 0, -1L ), 64, "0,2^64-1" );
            checkEqual( SerializeUtil.bitsRequired64( Long.MIN_VALUE, Long.MAX_VALUE ), 64, "full int64" );
            checkEqual( SerializeUtil.bitsRequired64( -5000000000L, +5000000000L ), 34, "±5e9" );
        } );

        test( "bitsRequired128: the reference table, including the sign-extension trap", () -> {
            UInt128Value zero = UInt128Value.ZERO;
            checkEqual( SerializeUtil.bitsRequired128( zero, zero ), 0, "0,0" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( 1 ) ), 1, "0,1" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( 255 ) ), 8, "0,255" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( 4294967295L ) ), 32, "0,2^32-1" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( 4294967296L ) ), 33, "0,2^32" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( -1L ) ), 64, "0,2^64-1" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( 1 ).shiftLeft( 64 ) ), 65, "0,2^64" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( 1 ).shiftLeft( 127 ) ), 128, "0,2^127" );
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.ZERO.not() ), 128, "0,2^128-1" );

            // the helpers must agree wherever the range fits 64 bits
            checkEqual( SerializeUtil.bitsRequired128( zero, UInt128Value.fromUnsignedLong( 4294967296L ) ),
                        SerializeUtil.bitsRequired64( 0, 4294967296L ), "agreement at 2^32" );

            // negative bounds arrive sign extended: the same 34 bits the 64-bit helper reports
            checkEqual( SerializeUtil.bitsRequired128( Int128Value.fromLong( -5000000000L ).toUnsigned(),
                                                       Int128Value.fromLong( +5000000000L ).toUnsigned() ), 34, "±5e9 sign extended" );

            // the trap: an already-wrapped uint64 bound zero extends instead, and the range costs 128
            checkEqual( SerializeUtil.bitsRequired128( UInt128Value.fromUnsignedLong( -5000000000L ),
                                                       UInt128Value.fromUnsignedLong( +5000000000L ) ), 128, "zero-extension trap" );

            // a range wider than 2^127: the subtraction must run in the unsigned domain
            checkEqual( SerializeUtil.bitsRequired128( UInt128Value.fromUnsignedLong( 1 ), UInt128Value.ZERO.not() ), 128, "wider than 2^127" );
        } );

        test( "zigzag: the reference table and round trips", () -> {
            checkEqual( SerializeUtil.signedToUnsigned( 0 ), 0, "0" );
            checkEqual( SerializeUtil.signedToUnsigned( -1 ), 1, "-1" );
            checkEqual( SerializeUtil.signedToUnsigned( +1 ), 2, "+1" );
            checkEqual( SerializeUtil.signedToUnsigned( -2 ), 3, "-2" );
            checkEqual( SerializeUtil.signedToUnsigned( +2 ), 4, "+2" );
            checkEqual( Integer.toUnsignedLong( SerializeUtil.signedToUnsigned( Integer.MAX_VALUE ) ), 0xFFFFFFFEL, "INT32_MAX" );
            checkEqual( Integer.toUnsignedLong( SerializeUtil.signedToUnsigned( Integer.MIN_VALUE ) ), 0xFFFFFFFFL, "INT32_MIN" );

            checkEqual( SerializeUtil.unsignedToSigned( 0 ), 0, "0" );
            checkEqual( SerializeUtil.unsignedToSigned( 1 ), -1, "1" );
            checkEqual( SerializeUtil.unsignedToSigned( 2 ), +1, "2" );
            checkEqual( SerializeUtil.unsignedToSigned( 3 ), -2, "3" );
            checkEqual( SerializeUtil.unsignedToSigned( 4 ), +2, "4" );
            checkEqual( SerializeUtil.unsignedToSigned( (int) 0xFFFFFFFEL ), Integer.MAX_VALUE, "0xFFFFFFFE" );
            checkEqual( SerializeUtil.unsignedToSigned( (int) 0xFFFFFFFFL ), Integer.MIN_VALUE, "0xFFFFFFFF" );

            int[] values = { 0, -1, +1, -2, +2, 12345, -12345, Integer.MAX_VALUE, Integer.MIN_VALUE };
            for ( int value : values )
            {
                checkEqual( SerializeUtil.unsignedToSigned( SerializeUtil.signedToUnsigned( value ) ), value, "round trip " + value );
            }
        } );
    }
}
