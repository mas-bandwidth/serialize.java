package serialize.tests;

import serialize.*;

/**
 * The golden wire format battery's message: GoldenWireData /
 * GoldenWireSerialize / golden_wire_bytes from serialize.h, ported field for
 * field and operation for operation. The byte array is copied MECHANICALLY
 * from the reference — never re-derived — and pinned forever: if a pin
 * against it fails, the wire format has changed, and that is never something
 * to fix by editing this file.
 */
final class GoldenWire
{
    private GoldenWire() {}

    // serialize.h golden_wire_bytes: 112 bytes, verbatim
    static final byte[] GOLDEN_WIRE_BYTES = bytes(
        0x5D, 0xDA, 0xF7, 0xE6, 0xD5, 0x77, 0xDF, 0x56, 0xEF, 0x9F, 0x75, 0x19,
        0x52, 0xBC, 0xDA, 0x0F, 0x49, 0x40, 0xF4, 0x55, 0x55, 0x55, 0x55, 0x55,
        0x55, 0x55, 0xFF, 0xFC, 0xD1, 0x48, 0xE0, 0x59, 0xD1, 0x48, 0xC0, 0x7B,
        0xF3, 0x6A, 0xE2, 0x59, 0xD1, 0x48, 0x84, 0xB7, 0x06, 0xDE, 0xAD, 0xBE,
        0xEF, 0xCA, 0xFE, 0x01, 0x06, 0x67, 0x6F, 0x6C, 0x64, 0x65, 0x6E, 0xE3,
        0x21, 0x00, 0x00, 0xC0, 0x21, 0x00, 0x00, 0x00, 0x22, 0x00, 0x00, 0x00,
        0xC0, 0x60, 0x00, 0x80, 0xA2, 0x7C, 0xFC, 0xEC, 0x26, 0xCB, 0xFF, 0xFF,
        0x4B, 0x1D, 0x1F, 0xEF, 0xD2, 0x1A, 0x1F, 0x01, 0xE9, 0xFF, 0xFF, 0x09,
        0x19, 0x2A, 0x3B, 0x4C, 0x5D, 0x6E, 0x7F, 0x78, 0x6F, 0x5E, 0x4D, 0x3C,
        0x2B, 0x1A, 0x09, 0x04 );

    // the golden stream ends 3 bits into its final byte: 891 consumed bits,
    // 5 trailing bits the writer must zero and the reader must ignore
    static final long GOLDEN_BITS = 891;

    static byte[] bytes( int... values )
    {
        byte[] result = new byte[values.length];
        for ( int i = 0; i < values.length; i++ )
        {
            result[i] = (byte) values[i];
        }
        return result;
    }

    /** GoldenWireData, field for field. Holders throughout, so one instance serves write and read. */
    static final class Data
    {
        final IntRef bits4 = new IntRef();
        final IntRef bits11 = new IntRef();
        final IntRef bits24 = new IntRef();
        final IntRef bits32 = new IntRef();
        final IntRef intSmall = new IntRef();
        final IntRef intFull = new IntRef();
        final BoolRef flag = new BoolRef();
        final FloatRef floatValue = new FloatRef();
        final FloatRef compressedFloatValue = new FloatRef();
        final DoubleRef doubleValue = new DoubleRef();
        final IntRef uint8Value = new IntRef();
        final IntRef uint16Value = new IntRef();
        final IntRef uint32Value = new IntRef();
        final LongRef uint64Value = new LongRef();
        final IntRef relativeNear = new IntRef();
        final IntRef relativeFar = new IntRef();
        final byte[] bytes = new byte[7];
        final Ref<String> string = new Ref<>( "" );
        final Ref<String> wstring = new Ref<>( "" );
        final LongRef fixedQ8_8 = new LongRef();
        final LongRef fixedQ16_16 = new LongRef();
        final LongRef fixedQ48_16 = new LongRef();
        final LongRef fixedQ16_16Unsigned = new LongRef();
        final Ref<Int128Value> fixedQ112_16Wide = new Ref<>( Int128Value.ZERO );
        final Ref<Int128Value> fixedQ64_64Wide = new Ref<>( Int128Value.ZERO );
    }

    /** GoldenWireInit, field for field. */
    static Data goldenData()
    {
        Data data = new Data();
        data.bits4.value = 13;
        data.bits11.value = 1445;
        data.bits24.value = 11259375;
        data.bits32.value = 0xDEADBEEF;
        data.intSmall.value = -37;
        data.intFull.value = -123456789;
        data.flag.value = true;
        data.floatValue.value = 3.1415926f;
        data.compressedFloatValue.value = 5.0f;
        data.doubleValue.value = 1.0 / 3.0;
        data.uint8Value.value = 0x7F;
        data.uint16Value.value = 0x1234;
        data.uint32Value.value = 0x12345678;
        data.uint64Value.value = 0x123456789ABCDEF0L;
        data.relativeNear.value = 101;              // difference of 1 from the base: the one-bit branch
        data.relativeFar.value = 2100;              // difference of 2000 from the base: the twelve-bit bucket
        byte[] goldenByteData = bytes( 0xDE, 0xAD, 0xBE, 0xEF, 0xCA, 0xFE, 0x01 );
        System.arraycopy( goldenByteData, 0, data.bytes, 0, 7 );
        data.string.value = "golden";
        // built from explicit code points so the source file encoding can never change the golden bytes
        data.wstring.value = new String( new char[] { 0x043C, 0x0438, 0x0440 } );   // cyrillic, BMP only
        data.fixedQ8_8.value = -( 3 * 256 + 64 );                                   // -3.25 in Q8.8
        data.fixedQ16_16.value = 1234 * 65536 + 32768;                              // 1234.5 in Q16.16
        data.fixedQ48_16.value = -( 54321L * 65536 + 12345 );                       // -54321.1883... in Q48.16
        data.fixedQ16_16Unsigned.value = 29999L * 65536 + 65535;                    // every fraction bit set
        data.fixedQ112_16Wide.value = Int128Value.fromLong( -( 98765432109L * 65536 + 4321 ) );   // 75 bits, three groups
        data.fixedQ64_64Wide.value = new Int128Value( 0x0123456789ABCDEFL, 0x0FEDCBA987654321L ); // four groups
        return data;
    }

    /** GoldenWireSerialize, operation for operation. The && chain mirrors the reference macros' early return. */
    static boolean serialize( BitStream stream, Data data )
    {
        final int relativeBase = 100;
        return stream.serializeBits( data.bits4, 4 )
            && stream.serializeBits( data.bits11, 11 )
            && stream.serializeBits( data.bits24, 24 )
            && stream.serializeBits( data.bits32, 32 )
            && stream.serializeInt( data.intSmall, -100, +100 )
            && stream.serializeInt( data.intFull, Integer.MIN_VALUE, Integer.MAX_VALUE )
            && stream.serializeBool( data.flag )
            && stream.serializeFloat( data.floatValue )
            && stream.serializeCompressedFloat( data.compressedFloatValue, 0.0f, 10.0f, 0.01f )
            && stream.serializeDouble( data.doubleValue )
            && stream.serializeUint8( data.uint8Value )
            && stream.serializeUint16( data.uint16Value )
            && stream.serializeUint32( data.uint32Value )
            && stream.serializeUint64( data.uint64Value )
            && stream.serializeIntRelative( relativeBase, data.relativeNear )
            && stream.serializeIntRelative( relativeBase, data.relativeFar )
            && stream.serializeAlign()
            && stream.serializeBytes( data.bytes, 7 )
            && stream.serializeString( data.string, 16 )
            && stream.serializeWideString( data.wstring, 8 )
            // the fixed point section starts byte aligned, so every byte pinned above it stays put
            && stream.serializeAlign()
            && stream.serializeFixed( data.fixedQ8_8, 8, 8, -100, +100 )
            && stream.serializeFixed( data.fixedQ16_16, 16, 16, -2000, +2000 )
            && stream.serializeFixed( data.fixedQ48_16, 48, 16, -100000, +100000 )
            && stream.serializeFixed( data.fixedQ16_16Unsigned, 16, 16, 0, 30000 )
            // the wide fixed section starts byte aligned as well
            && stream.serializeAlign()
            // +-2^57 units: 75 bits, the three-group structure
            && stream.serializeFixed128( data.fixedQ112_16Wide, 112, 16, -144115188075855872L, +144115188075855872L )
            // full unit range: 128 bits, the four-group structure
            && stream.serializeFixed128( data.fixedQ64_64Wide, 64, 64, Long.MIN_VALUE, Long.MAX_VALUE );
    }

    /**
     * True when every decoded field equals the golden values exactly —
     * including the compressed float, which the golden value pins exactly by
     * construction (5.0 in [0,10] normalizes to exactly 0.5). Floats compare
     * as bit patterns per the transparency doctrine.
     */
    static boolean matchesGolden( Data data )
    {
        Data expected = goldenData();
        if ( data.bits4.value != expected.bits4.value ) return false;
        if ( data.bits11.value != expected.bits11.value ) return false;
        if ( data.bits24.value != expected.bits24.value ) return false;
        if ( data.bits32.value != expected.bits32.value ) return false;
        if ( data.intSmall.value != expected.intSmall.value ) return false;
        if ( data.intFull.value != expected.intFull.value ) return false;
        if ( data.flag.value != expected.flag.value ) return false;
        if ( Float.floatToRawIntBits( data.floatValue.value ) != Float.floatToRawIntBits( expected.floatValue.value ) ) return false;
        if ( Float.floatToRawIntBits( data.compressedFloatValue.value ) != Float.floatToRawIntBits( expected.compressedFloatValue.value ) ) return false;
        if ( Double.doubleToRawLongBits( data.doubleValue.value ) != Double.doubleToRawLongBits( expected.doubleValue.value ) ) return false;
        if ( data.uint8Value.value != expected.uint8Value.value ) return false;
        if ( data.uint16Value.value != expected.uint16Value.value ) return false;
        if ( data.uint32Value.value != expected.uint32Value.value ) return false;
        if ( data.uint64Value.value != expected.uint64Value.value ) return false;
        if ( data.relativeNear.value != expected.relativeNear.value ) return false;
        if ( data.relativeFar.value != expected.relativeFar.value ) return false;
        for ( int i = 0; i < 7; i++ )
        {
            if ( data.bytes[i] != expected.bytes[i] ) return false;
        }
        if ( !expected.string.value.equals( data.string.value ) ) return false;
        if ( !expected.wstring.value.equals( data.wstring.value ) ) return false;
        if ( data.fixedQ8_8.value != expected.fixedQ8_8.value ) return false;
        if ( data.fixedQ16_16.value != expected.fixedQ16_16.value ) return false;
        if ( data.fixedQ48_16.value != expected.fixedQ48_16.value ) return false;
        if ( data.fixedQ16_16Unsigned.value != expected.fixedQ16_16Unsigned.value ) return false;
        if ( !expected.fixedQ112_16Wide.value.equals( data.fixedQ112_16Wide.value ) ) return false;
        if ( !expected.fixedQ64_64Wide.value.equals( data.fixedQ64_64Wide.value ) ) return false;
        return true;
    }
}
