package serialize.tests;

/**
 * Runs every suite. Exits nonzero on any failure.
 *
 * Two shapes, both gates. The default is the checked shape, run with
 * {@code -ea}, where the write-side contracts are asserts and the suite must
 * exercise them; a forgotten {@code -ea} would pass silently while testing
 * nothing on the write side, so it is refused. The release shape is asked for
 * by name — {@code java AllTests --release}, with assertions off — and proves
 * the read side's refusals bind without them: every obligation STANDARD.md
 * places on a reader is a check, never an assert.
 */
public final class AllTests
{
    private AllTests() {}

    public static void main( String[] args )
    {
        boolean release = args.length > 0 && args[0].equals( "--release" );
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if ( assertionsEnabled == release )
        {
            System.out.println( release
                ? "error: --release is the assertions-off shape — run it without -ea"
                : "error: run with -ea — the write-side contracts are asserts and the suite must exercise them" );
            System.exit( 2 );
        }

        System.out.println( release ? "serialize.java test suite (release shape, assertions off)"
                                    : "serialize.java test suite (checked shape, assertions on)" );
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
        ConformanceTests.run();

        System.exit( Harness.finish() );
    }
}
