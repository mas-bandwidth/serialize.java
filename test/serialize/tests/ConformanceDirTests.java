package serialize.tests;

import java.nio.file.Paths;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.test;

/**
 * Where the conformance runner and the negative controls read their corpus:
 * the vendored directories, unless SERIALIZE_CONFORMANCE_DIR and
 * SERIALIZE_CONFORMANCE_CONTROLS_DIR name others. The nightly conformance
 * functional test sets them to mas-bandwidth/serialize main HEAD. Resolution
 * only: nothing here reads the environment or touches the file system.
 */
final class ConformanceDirTests
{
    private ConformanceDirTests() {}

    static void run()
    {
        test( "conformance directory: unset, the vendored corpus", () -> {
            check( ConformanceTests.resolveDir( null, "conformance" ).equals( Paths.get( "conformance" ) ),
                   "no override reads conformance/" );
            check( ConformanceTests.resolveDir( null, "conformance-controls" ).equals( Paths.get( "conformance-controls" ) ),
                   "no override reads conformance-controls/" );
        } );

        test( "conformance directory: empty, the vendored corpus", () -> {
            check( ConformanceTests.resolveDir( "", "conformance" ).equals( Paths.get( "conformance" ) ),
                   "an empty override is no override" );
        } );

        test( "conformance directory: set, the override exactly, with no fallback", () -> {
            check( ConformanceTests.resolveDir( "/work/upstream/conformance", "conformance" )
                       .equals( Paths.get( "/work/upstream/conformance" ) ),
                   "an absolute override is read as written" );
            check( ConformanceTests.resolveDir( "upstream/conformance-controls", "conformance-controls" )
                       .equals( Paths.get( "upstream/conformance-controls" ) ),
                   "a relative override is read as written" );
            check( ConformanceTests.resolveDir( "no-such-directory", "conformance" )
                       .equals( Paths.get( "no-such-directory" ) ),
                   "a missing override is not replaced by the vendored copy" );
        } );

        test( "conformance directory: the variable names", () -> {
            check( ConformanceTests.CORPUS_DIR_VARIABLE.equals( "SERIALIZE_CONFORMANCE_DIR" ), "the corpus" );
            check( ConformanceTests.CONTROLS_DIR_VARIABLE.equals( "SERIALIZE_CONFORMANCE_CONTROLS_DIR" ), "the controls" );
        } );
    }
}
