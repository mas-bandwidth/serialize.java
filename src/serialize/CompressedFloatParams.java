package serialize;

/**
 * The compressed float wire constants a declaration derives, from
 * {@link SerializeUtil#compressedFloatParams}: what the reference's
 * {@code serialize_compressed_float_params} hands back through its out
 * parameters. Pass them to
 * {@link BitStream#serializeCompressedFloatPrecomputed} together with the
 * declaration's {@code min}.
 */
public final class CompressedFloatParams
{
    /** The quantization step count: values quantize to integers in [0, maxIntegerValue], an unsigned 32-bit quantity in [1, 4294967040]. */
    public final long maxIntegerValue;

    /** The wire width, {@code bitsRequired( 0, maxIntegerValue )}, in [1,32]. */
    public final int bits;

    /** The range width {@code max - min}, rounded to float32: the wire depends on this exact value. */
    public final float delta;

    public CompressedFloatParams( long maxIntegerValue, int bits, float delta )
    {
        this.maxIntegerValue = maxIntegerValue;
        this.bits = bits;
        this.delta = delta;
    }
}
