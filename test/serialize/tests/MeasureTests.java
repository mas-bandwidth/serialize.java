package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** The measure stream: a conservative bound, never an exact-from-zero count. */
final class MeasureTests
{
    private MeasureTests() {}

    static void run()
    {
        test( "measure: the standard's worked example — { bits(8); align; bits(8) } measures 23", () -> {
            MeasureStream measure = new MeasureStream();
            check( measure.serializeBits( new IntRef( 0xAB ), 8 ) );
            check( measure.serializeAlign() );
            check( measure.serializeBits( new IntRef( 0xCD ), 8 ) );
            // 8 + 7 + 8: the conservative answer, sufficient at every starting
            // position. An exact-from-zero measure reports 16 and is non-conforming.
            checkEqual( measure.getBitsProcessed(), 23, "conservative align accounting" );
        } );

        test( "measure: align charges 7 even at a byte boundary", () -> {
            MeasureStream measure = new MeasureStream();
            check( measure.serializeBits( new IntRef( 0xAB ), 8 ) );
            checkEqual( measure.getAlignBits(), 7, "getAlignBits is always worst case" );
            check( measure.serializeAlign() );
            checkEqual( measure.getBitsProcessed(), 15, "7 charged at an aligned position" );
        } );

        test( "measure: exact widths for every position-independent operation", () -> {
            MeasureStream measure = new MeasureStream();
            check( measure.serializeBits( new IntRef( 5 ), 3 ) );
            check( measure.serializeInt( new IntRef( -37 ), -100, 100 ) );
            check( measure.serializeInt64( new LongRef( 0 ), -5000000000L, 5000000000L ) );
            check( measure.serializeBool( new BoolRef( true ) ) );
            check( measure.serializeFloat( new FloatRef( 1.0f ) ) );
            check( measure.serializeDouble( new DoubleRef( 1.0 ) ) );
            check( measure.serializeUint8( new IntRef( 1 ) ) );
            check( measure.serializeUint16( new IntRef( 1 ) ) );
            check( measure.serializeUint32( new IntRef( 1 ) ) );
            check( measure.serializeUint64( new LongRef( 1 ) ) );
            check( measure.serializeUint128( new Ref<>( UInt128Value.ZERO ) ) );
            check( measure.serializeCompressedFloat( new FloatRef( 5.0f ), 0.0f, 10.0f, 0.01f ) );
            checkEqual( measure.getBitsProcessed(),
                        3 + 8 + 34 + 1 + 32 + 64 + 8 + 16 + 32 + 64 + 128 + 10,
                        "exact widths" );
        } );

        test( "measure: bytes charges align worst case plus the payload", () -> {
            MeasureStream measure = new MeasureStream();
            check( measure.serializeBytes( new byte[7], 7 ) );
            checkEqual( measure.getBitsProcessed(), 7 + 7 * 8, "7 align + 56 payload" );
        } );

        test( "measure: bounds the golden write at every one of the 8 starting offsets", () -> {
            for ( int offset = 0; offset < 8; offset++ )
            {
                byte[] buffer = new byte[256];
                WriteStream writer = new WriteStream( buffer, 256 );
                if ( offset > 0 )
                {
                    check( writer.serializeBits( new IntRef( 0 ), offset ) );
                }
                check( GoldenWire.serialize( writer, GoldenWire.goldenData() ) );

                MeasureStream measure = new MeasureStream();
                if ( offset > 0 )
                {
                    check( measure.serializeBits( new IntRef( 0 ), offset ) );
                }
                check( GoldenWire.serialize( measure, GoldenWire.goldenData() ) );

                check( measure.getBitsProcessed() >= writer.getBitsProcessed(),
                       "offset " + offset + ": measured " + measure.getBitsProcessed()
                       + " bits, wrote " + writer.getBitsProcessed() );
            }
        } );
    }
}
