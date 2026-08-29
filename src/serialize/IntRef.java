package serialize;

/**
 * Mutable holder for a 32-bit value passed through a unified serialize function.
 * Java has no ref parameters, so the streams read and write through this cell;
 * a primitive-specialized holder keeps the hot path free of boxing.
 */
public final class IntRef
{
    public int value;

    public IntRef() {}

    public IntRef( int value ) { this.value = value; }
}
