package serialize.tests;

/** Runs every suite. Exits nonzero on any failure. Run with -ea: the write-side contracts are asserts. */
public final class AllTests
{
    private AllTests() {}

    public static void main( String[] args )
    {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if ( !assertionsEnabled )
        {
            System.out.println( "error: run with -ea — the write-side contracts are asserts and the suite must exercise them" );
            System.exit( 2 );
        }

        System.out.println( "serialize.java test suite" );
        System.out.println();

        BitpackerTests.run();
        StreamTests.run();
        Int128Tests.run();
        FloatTests.run();
        FixedTests.run();
        IntRelativeTests.run();
        StringTests.run();
        MeasureTests.run();
        GoldenWireTests.run();

        System.exit( Harness.finish() );
    }
}
