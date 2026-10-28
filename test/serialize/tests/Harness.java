package serialize.tests;

/**
 * The hand-rolled test harness: expect helpers, per-test isolation, counts,
 * and a nonzero exit on any failure. Zero dependencies by family law.
 */
public final class Harness
{
    private Harness() {}

    private static int tests;
    private static int failed;
    private static int checks;
    private static boolean quiet;

    /** Prints only the failures: the negative controls run the whole corpus once per control. */
    public static void setQuiet( boolean value )
    {
        quiet = value;
    }

    /** The number of tests that have failed so far. */
    public static int failures()
    {
        return failed;
    }

    private static final class CheckError extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        CheckError( String message ) { super( message ); }
    }

    public static void test( String name, Runnable body )
    {
        tests++;
        if ( !quiet )
        {
            System.out.printf( "    %s%n", name );
        }
        try
        {
            body.run();
        }
        catch ( CheckError error )
        {
            failed++;
            if ( quiet )
            {
                System.out.printf( "    %s%n", name );
            }
            System.out.printf( "        FAILED: %s%n", error.getMessage() );
        }
        catch ( Throwable error )
        {
            // a thrown exception is itself a failure: hostile data must never throw
            failed++;
            if ( quiet )
            {
                System.out.printf( "    %s%n", name );
            }
            System.out.printf( "        FAILED (threw %s: %s)%n", error.getClass().getName(), error.getMessage() );
            for ( StackTraceElement frame : error.getStackTrace() )
            {
                System.out.printf( "            at %s%n", frame );
            }
        }
    }

    public static void check( boolean condition )
    {
        check( condition, "check failed" );
    }

    public static void check( boolean condition, String message )
    {
        checks++;
        if ( !condition )
        {
            throw new CheckError( message );
        }
    }

    public static void checkEqual( long actual, long expected, String what )
    {
        check( actual == expected, what + ": got " + actual + ", expected " + expected );
    }

    public static void checkEqual( String actual, String expected, String what )
    {
        check( expected.equals( actual ), what + ": got \"" + actual + "\", expected \"" + expected + "\"" );
    }

    public static void checkBytesEqual( byte[] actual, byte[] expected, int length, String what )
    {
        for ( int i = 0; i < length; i++ )
        {
            check( actual[i] == expected[i],
                   String.format( "%s: byte %d is 0x%02X, expected 0x%02X", what, i, actual[i], expected[i] ) );
        }
    }

    /** Are assertions enabled? The write-side contracts are asserts, and exist only in the checked shape. */
    public static boolean assertionsEnabled()
    {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    /** Did the body's debug contract fire? */
    public static boolean fires( Runnable body )
    {
        try
        {
            body.run();
            return false;
        }
        catch ( AssertionError error )
        {
            return true;
        }
    }

    public static int finish()
    {
        System.out.printf( "%n%d tests, %d checks, %d failed%n", tests, checks, failed );
        if ( failed == 0 )
        {
            System.out.println( "ALL TESTS PASS" );
            return 0;
        }
        System.out.println( "TESTS FAILED" );
        return 1;
    }
}
