package serialize;

/**
 * Mutable holder for an object value (String, UInt128Value, Int128Value)
 * passed through a unified serialize function.
 */
public final class Ref<T>
{
    public T value;

    public Ref() {}

    public Ref( T value ) { this.value = value; }
}
