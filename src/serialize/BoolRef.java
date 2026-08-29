package serialize;

/** Mutable holder for a boolean passed through a unified serialize function. */
public final class BoolRef
{
    public boolean value;

    public BoolRef() {}

    public BoolRef( boolean value ) { this.value = value; }
}
