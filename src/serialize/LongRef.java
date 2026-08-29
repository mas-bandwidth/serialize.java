package serialize;

/**
 * Mutable holder for a 64-bit value passed through a unified serialize function.
 * Unsigned 64-bit wire values live bit-transparently in the long.
 */
public final class LongRef
{
    public long value;

    public LongRef() {}

    public LongRef( long value ) { this.value = value; }
}
