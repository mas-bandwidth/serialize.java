package serialize.fuzz;

/**
 * The harness's trap: the Java spelling of the reference's {@code fuzz_check},
 * which calls {@code __builtin_trap()}. A failed check throws this, Jazzer
 * reports the uncaught throwable as a finding and writes the input that
 * produced it as a crash reproducer.
 *
 * It is unchecked so it escapes every serialize body untouched, and it has
 * its own type so a negative control can tell a check that fired from any
 * other throwable: a ReadStream that throws on hostile bytes is a finding
 * too, but a different one.
 */
public final class FuzzCheck extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private FuzzCheck( String message )
    {
        super( message );
    }

    /** Traps when the condition is false. */
    public static void check( boolean condition, String what )
    {
        if ( !condition )
        {
            throw new FuzzCheck( what );
        }
    }
}
