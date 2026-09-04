package serialize;

/**
 * The unified stream interface: a message's single serialize function runs
 * against a {@link WriteStream}, a {@link ReadStream} or a
 * {@link MeasureStream} through this interface, mirroring the templated
 * serialize functions of the C++ reference.
 *
 * Every method returns false on refusal. On the write and measure sides the
 * data is trusted and misuse is caught by debug asserts only (run with -ea);
 * on the read side every refusal rule of STANDARD.md binds in every mode and
 * hostile bytes never throw. A refused read leaves its scalar destination
 * unwritten and is terminal for the stream — the exception is a caller-owned
 * buffer, whose contents are unspecified after a refusal.
 */
public interface BitStream
{
    /** True for write and measure streams. */
    boolean isWriting();

    /** True for read streams. */
    boolean isReading();

    /** Raw bits at a width in [1,32]. */
    boolean serializeBits( IntRef value, int bits );

    /** Raw bits at a width in [1,64]: the low 32 bits first as a 32-bit group, then the remainder. */
    boolean serializeBits64( LongRef value, int bits );

    /** A ranged 32-bit integer: value - min in bitsRequired(min,max) bits. */
    boolean serializeInt( IntRef value, int min, int max );

    /** A ranged 64-bit integer: value - min in bitsRequired64(min,max) bits, low 32-bit group first past 32 bits. */
    boolean serializeInt64( LongRef value, long min, long max );

    /**
     * A ranged 128-bit integer: the offset in 32-bit groups from least
     * significant upward. min <= max, and a degenerate min == max range costs
     * zero bits — nothing on the wire, the value taken from min.
     */
    boolean serializeInt128( Ref<Int128Value> value, Int128Value min, Int128Value max );

    /** An unsigned 8-bit integer: 8 raw bits. */
    boolean serializeUint8( IntRef value );

    /** An unsigned 16-bit integer: 16 raw bits. */
    boolean serializeUint16( IntRef value );

    /** An unsigned 32-bit integer: 32 raw bits. */
    boolean serializeUint32( IntRef value );

    /** An unsigned 64-bit integer: 64 raw bits, low 32-bit group first. */
    boolean serializeUint64( LongRef value );

    /** An unsigned 128-bit integer: 128 raw bits, the low 64-bit half first. */
    boolean serializeUint128( Ref<UInt128Value> value );

    /** One bit: 1 for true, 0 for false. */
    boolean serializeBool( BoolRef value );

    /** The 32 bits of the IEEE-754 representation, bit transparent in both directions. */
    boolean serializeFloat( FloatRef value );

    /** The 64 bits of the IEEE-754 representation, bit transparent in both directions. */
    boolean serializeDouble( DoubleRef value );

    /** A float quantized to a resolution: the exact two-rounding float32 arithmetic of STANDARD.md. */
    boolean serializeCompressedFloat( FloatRef value, float min, float max, float resolution );

    /** Aligns first, then {@code bytes} raw bytes. The count is not written: both sides must agree on it. */
    boolean serializeBytes( byte[] data, int bytes );

    /** Zero pad bits until the bit index is a multiple of 8; nothing if already aligned. */
    boolean serializeAlign();

    /** A UTF-8 string: the byte length as a ranged int over [0, bufferSize-1], then the bytes (which aligns). */
    boolean serializeString( Ref<String> value, int bufferSize );

    /**
     * A wide string: the UTF-16 code unit count as a ranged int over
     * [0, bufferSize-1], then each code unit as a 32-bit group. No alignment
     * anywhere in this operation.
     */
    boolean serializeWideString( Ref<String> value, int bufferSize );

    /**
     * The strictly increasing relative-integer ladder over the domain 0 to
     * 2^31 - 1 inclusive. current must exceed previous, and both lie in the
     * domain; a read whose reconstruction leaves the domain or fails to exceed
     * previous is refused.
     */
    boolean serializeIntRelative( int previous, IntRef current );

    /**
     * A fixed point value on storage of 64 bits or fewer: the raw (scaled)
     * value lives in {@code value}, integerBits + fractionBits is the storage
     * width (8, 16, 32 or 64), and the bounds are whole units. Byte identical
     * to serializeInt64 of the raw value over the raw bounds.
     */
    boolean serializeFixed( LongRef value, int integerBits, int fractionBits, long minUnits, long maxUnits );

    /**
     * The 128-bit storage counterpart of {@link #serializeFixed}:
     * integerBits + fractionBits must equal 128.
     */
    boolean serializeFixed128( Ref<Int128Value> value, int integerBits, int fractionBits, long minUnits, long maxUnits );

    /**
     * Runs a nested object's own serialize function inline. It contributes no
     * bytes of its own: no framing, no length prefix and no alignment is
     * inserted around it, so the nested object's operations appear at exactly
     * this position in the stream. A refusal inside the nesting propagates out
     * of it, and a read stream consults its failure state first, so a nested
     * object on a failed stream refuses without invoking the object.
     */
    boolean serializeObject( Serializer object );

    /** The number of pad bits an align would cost right now. Always 7 on a measure stream. */
    int getAlignBits();

    /** The number of bits processed so far. */
    long getBitsProcessed();

    /** The number of bytes processed so far: bits rounded up to a byte. */
    long getBytesProcessed();
}
