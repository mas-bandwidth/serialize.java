package serialize;

/** Mutable holder for a double passed through a unified serialize function. */
public final class DoubleRef
{
    public double value;

    public DoubleRef() {}

    public DoubleRef( double value ) { this.value = value; }
}
