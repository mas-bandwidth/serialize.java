package serialize.tests;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The NEGATIVE CONTROLS. The corpus is the conformance instrument, and an
 * instrument nobody has seen go red is a claim rather than a measurement
 * (STANDARD.md, "The conformance corpus"). Two kinds of control prove it
 * responds:
 *
 * The unknown operation control runs the runner over
 * {@code conformance-controls/unknown-operation.txt}, a vector whose operation
 * has no runner, and requires the run to go RED rather than skip it.
 *
 * Every other control is a reader with ONE refusal rule deleted, built from a
 * scratch copy of the library and run against the real corpus. It passes only
 * when the vectors that fail are EXACTLY the ones
 * {@code conformance-controls/<rule>.expected} names: a sabotage that breaks
 * more than its rule, or less, is a control that measures something other
 * than what it claims. The lists are vendored verbatim from the reference,
 * which builds the same sabotage of its own reader from the same lists.
 *
 * A sabotage is a string replacement in the library source, and a needle that
 * stops matching FAILS this run rather than passing as a no-op: a control that
 * has quietly stopped sabotaging anything is worse than no control at all. If
 * one fails, update the needle; never delete the control. Each replacement
 * keeps the line compiling and reading every variable the deleted rule read:
 * the added conjunct is false exactly when the rule would have fired.
 *
 * Each control's reader is loaded in a class loader of its own, beside a
 * fresh copy of the test classes, so no state crosses between controls. The
 * controls run with assertions OFF: a reader with a rule deleted produces
 * values a checked build asserts on, and what must go red is the corpus,
 * cleanly.
 *
 * Usage: {@code java serialize.tests.Controls <library classes> <test classes> <scratch dir>},
 * from the repository root. The corpus and the lists are the vendored
 * directories unless SERIALIZE_CONFORMANCE_DIR and
 * SERIALIZE_CONFORMANCE_CONTROLS_DIR name others ({@link ConformanceTests#resolveDir}).
 */
public final class Controls
{
    private Controls() {}

    /** One sabotage: replace every occurrence of the needle in one source file. */
    private static final class Patch
    {
        final String file;
        final String needle;
        final String replacement;

        Patch( String file, String needle, String replacement )
        {
            this.file = file;
            this.needle = needle;
            this.replacement = replacement;
        }
    }

    private static final class Control
    {
        final String name;
        final String rule;
        final Patch[] patches;

        Control( String name, String rule, Patch... patches )
        {
            this.name = name;
            this.rule = rule;
            this.patches = patches;
        }
    }

    private static final String DELETED = " // NEGATIVE CONTROL: this rule is deleted on purpose";

    private static Patch read( String needle, String replacement )
    {
        return new Patch( "ReadStream.java", needle, replacement );
    }

    // The decoder that turns an accepted payload into a String is strict
    // (REPORT), so the UTF-8 rule is held twice: the validator decides, and
    // the decoder refuses what slipped past it. Deleting the rule deletes it
    // from both.
    private static final Patch DECODER_REPLACES = read(
        ".onMalformedInput( CodingErrorAction.REPORT )",
        ".onMalformedInput( CodingErrorAction.REPLACE )" + " /* NEGATIVE CONTROL */" );

    private static final Control[] CONTROLS =
    {
        new Control( "align-padding", "readers must verify that the alignment padding bits are zero",
            new Patch( "BitReader.java",
                "            if ( value != 0 )",
                "            if ( value == 0xFFFFFFFF )" + DELETED ) ),

        // a ranged int's two rules, at both of its sites: the int operation, and
        // the bounded tiers of int_relative, whose payloads are ranged ints
        new Control( "ranged-reconstruction", "a ranged int decodes to its offset plus min",
            read( "        // add in the unsigned domain: wraps when the range is wider than 2^31\n"
                + "        value.value = unsignedValue + min;",
                  "        value.value = unsignedValue;" + DELETED ),
            read( "                long difference = Integer.toUnsignedLong( unsignedValue ) + tierMin;",
                  "                long difference = Integer.toUnsignedLong( unsignedValue ) + 0 * tierMin;" + DELETED ) ),

        new Control( "ranged-range", "a ranged int whose offset is past the span is refused",
            // the widened right hand side is at least 0xFFFFFFFF and the offset is at most 0xFFFFFFFF
            read( "        if ( Integer.compareUnsigned( unsignedValue, max - min ) > 0 )",
                  "        if ( Integer.toUnsignedLong( unsignedValue ) > Integer.toUnsignedLong( max - min ) + 0xFFFFFFFFL )" + DELETED ),
            read( "                if ( Integer.compareUnsigned( unsignedValue, tierMax - tierMin ) > 0 )",
                  "                if ( Integer.toUnsignedLong( unsignedValue ) > Integer.toUnsignedLong( tierMax - tierMin ) + 0xFFFFFFFFL )" + DELETED ) ),

        // the degenerate fixed point field consumes no bits and would otherwise
        // always succeed; both storage widths are sabotaged
        new Control( "fixed-degenerate-terminal", "every read consults the failure state, zero bit reads included",
            read( "    public boolean serializeFixed( LongRef value, int integerBits, int fractionBits, long minUnits, long maxUnits )\n"
                + "    {\n"
                + "        if ( failed ) return false;",
                  "    public boolean serializeFixed( LongRef value, int integerBits, int fractionBits, long minUnits, long maxUnits )\n"
                + "    {\n"
                + "        if ( failed && minUnits != maxUnits ) return false;" + DELETED ),
            read( "    public boolean serializeFixed128( Ref<Int128Value> value, int integerBits, int fractionBits, long minUnits, long maxUnits )\n"
                + "    {\n"
                + "        if ( failed ) return false;",
                  "    public boolean serializeFixed128( Ref<Int128Value> value, int integerBits, int fractionBits, long minUnits, long maxUnits )\n"
                + "    {\n"
                + "        if ( failed && minUnits != maxUnits ) return false;" + DELETED ) ),

        // the finite rule, once per width and once for the compressed float
        // decode: zero is finite, so the added conjunct never holds
        new Control( "float-finite", "a float read refuses NaN and the infinities",
            read( "        if ( !SerializeUtil.floatBitsAreFinite( bits ) )",
                  "        if ( !SerializeUtil.floatBitsAreFinite( bits ) && bits == 0 )" + DELETED ) ),

        new Control( "double-finite", "a double read refuses NaN and the infinities",
            read( "        if ( !SerializeUtil.doubleBitsAreFinite( bits ) )",
                  "        if ( !SerializeUtil.doubleBitsAreFinite( bits ) && bits == 0L )" + DELETED ) ),

        new Control( "compressed-float-finite", "a compressed float decode that is not finite is refused",
            read( "        if ( !SerializeUtil.floatBitsAreFinite( Float.floatToRawIntBits( decoded ) ) )",
                  "        if ( !SerializeUtil.floatBitsAreFinite( Float.floatToRawIntBits( decoded ) ) && Float.floatToRawIntBits( decoded ) == 0 )" + DELETED ) ),

        // without the trailing data rule the end reads only the low bits of
        // whatever remains, so a stream with whole bytes left passes when they are zero
        new Control( "end-trailing-data", "an end refuses a whole byte after the object",
            read( "        if ( remaining >= 8 )",
                  "        if ( remaining >= 8 && reader.getBitsRead() < 0 )" + DELETED ) ),

        new Control( "end-padding", "an end refuses non-zero padding in the final byte",
            read( "            if ( padding != 0 )",
                  "            if ( padding == 0xFFFFFFFF )" + DELETED ) ),

        new Control( "int-relative-canonical", "the absolute tier refuses a difference a bounded tier carries",
            read( "        if ( absolute <= Integer.MAX_VALUE && absolute > previous && absolute - previous <= 69914 )",
                  "        if ( absolute <= Integer.MAX_VALUE && absolute > previous && absolute - previous <= 69914 && absolute < 0 )" + DELETED ) ),

        new Control( "string-embedded-nul", "a string read refuses a zero byte among its payload",
            read( "            if ( utf8[i] == 0 )",
                  "            if ( utf8[i] == 0 && i < 0 )" + DELETED ) ),

        new Control( "string-utf8", "a string read refuses a payload that is not well formed UTF-8",
            read( "        if ( !SerializeUtil.isValidUtf8( utf8, length.value ) )",
                  "        if ( !SerializeUtil.isValidUtf8( utf8, length.value ) && length.value < 0 )" + DELETED ),
            DECODER_REPLACES ),

        // one clause of the UTF-8 rule alone: the encoded surrogates, ED A0 80 through ED BF BF
        new Control( "string-utf8-surrogate", "a string read refuses an encoded surrogate code point",
            new Patch( "SerializeUtil.java",
                "                if ( lead == 0xED && byte1 >= 0xA0 )",
                "                if ( lead == 0xED && byte1 >= 0xA0 && byte1 > 0xFF )" + DELETED ),
            DECODER_REPLACES ),

        // a reader whose ranged 64 bit integer passes through a double, as a port
        // backed by a double number type would: every integer up to 2^53
        // survives, and the wide vectors past it do not
        new Control( "int64-through-double", "a 64 bit value is carried exactly, never through a double",
            read( "        // add in the unsigned domain: wraps when the range is wider than 2^63\n"
                + "        value.value = unsignedValue + min;",
                  "        value.value = (long) (double) ( unsignedValue + min ); // NEGATIVE CONTROL: the value goes through a double on purpose" ) ),

        // PROBE ONLY (not for merge): the object latch, entry and exit deleted
        new Control( "object-terminal", "a nested object on a failed stream refuses before its callback runs",
            read( "        if ( failed ) return false;         // the latch: a failed stream refuses everything after\n"
                + "        if ( !object.serialize( this ) || failed )",
                  "        if ( failed && object == null ) return false;" + DELETED + "\n"
                + "        if ( !object.serialize( this ) )" ) ),

        // the refusal reason: this reader refuses every stream the real one
        // refuses, and names the wrong rule for one of them
        new Control( "wrong-reason", "a refusal names the rule that refused it",
            read( "                return fail( Refusal.EMBEDDED_NUL );",
                  "                return fail( Refusal.INVALID_UTF8 ); // NEGATIVE CONTROL: the wrong reason on purpose" ) ),
    };

    public static void main( String[] args ) throws Exception
    {
        if ( args.length != 3 )
        {
            System.out.println( "usage: Controls <library classes> <test classes> <scratch dir>" );
            System.exit( 2 );
        }
        Path libraryClasses = Paths.get( args[0] );
        Path testClasses = Paths.get( args[1] );
        Path scratch = Paths.get( args[2] );

        List<String> failures = new ArrayList<>();

        System.out.println( "control unknown-operation: a vector whose operation has no runner must go red" );
        boolean red = runConformance( libraryClasses, testClasses,
                                      List.of( ConformanceTests.controlsDir().resolve( "unknown-operation.txt" ) ), null );
        if ( !red )
        {
            failures.add( "unknown-operation: the runner went green over an operation it cannot drive" );
        }

        List<Path> corpus = corpusFiles();
        for ( Control control : CONTROLS )
        {
            System.out.printf( "%ncontrol %s: %s%n", control.name, control.rule );
            Path expected = ConformanceTests.controlsDir().resolve( control.name + ".expected" );
            if ( !Files.isRegularFile( expected ) )
            {
                failures.add( control.name + ": no " + expected + " naming the vectors its rule owns" );
                continue;
            }
            Path source = scratch.resolve( control.name ).resolve( "src" );
            Path classes = scratch.resolve( control.name ).resolve( "classes" );
            String problem = sabotage( control, source );
            if ( problem != null )
            {
                failures.add( control.name + ": " + problem );
                continue;
            }
            if ( !compile( source, classes ) )
            {
                failures.add( control.name + ": the sabotaged library does not compile" );
                continue;
            }
            if ( !runConformance( classes, testClasses, corpus, expected ) )
            {
                failures.add( control.name + ": the sabotaged reader did not fail exactly its rule's vectors" );
            }
        }

        System.out.println();
        if ( !failures.isEmpty() )
        {
            for ( String failure : failures )
            {
                System.out.println( "CONTROL FAILED: " + failure );
            }
            System.out.println( "THE CONTROLS DID NOT ALL HOLD" );
            System.exit( 1 );
        }
        System.out.printf( "ALL %d CONTROLS HOLD: every sabotaged reader fails exactly its rule's vectors%n",
                           CONTROLS.length + 1 );
    }

    private static List<Path> corpusFiles() throws IOException
    {
        List<Path> files = new ArrayList<>();
        try ( DirectoryStream<Path> entries = Files.newDirectoryStream( ConformanceTests.corpusDir(), "*.txt" ) )
        {
            for ( Path entry : entries )
            {
                files.add( entry );
            }
        }
        files.sort( null );
        return files;
    }

    // copies the library source with the control's patches applied; returns
    // a problem, or null
    private static String sabotage( Control control, Path destination ) throws IOException
    {
        Path original = Paths.get( "src", "serialize" );
        Files.createDirectories( destination );
        try ( DirectoryStream<Path> entries = Files.newDirectoryStream( destination, "*.java" ) )
        {
            for ( Path entry : entries )
            {
                Files.delete( entry );
            }
        }
        List<String> files = new ArrayList<>();
        try ( DirectoryStream<Path> entries = Files.newDirectoryStream( original, "*.java" ) )
        {
            for ( Path entry : entries )
            {
                files.add( entry.getFileName().toString() );
            }
        }
        for ( String file : files )
        {
            String text = new String( Files.readAllBytes( original.resolve( file ) ), StandardCharsets.UTF_8 );
            for ( Patch patch : control.patches )
            {
                if ( !patch.file.equals( file ) )
                {
                    continue;
                }
                if ( !text.contains( patch.needle ) )
                {
                    return "found nothing to sabotage in " + file + ". The line it deletes has moved or changed, "
                         + "so the control no longer proves that deleting '" + control.rule + "' turns the corpus red. "
                         + "Update the needle in Controls.java; do not delete the control.";
                }
                text = text.replace( patch.needle, patch.replacement );
            }
            Files.write( destination.resolve( file ), text.getBytes( StandardCharsets.UTF_8 ) );
        }
        for ( Patch patch : control.patches )
        {
            if ( !files.contains( patch.file ) )
            {
                return "no source file " + patch.file;
            }
        }
        return null;
    }

    private static boolean compile( Path source, Path classes ) throws IOException
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if ( compiler == null )
        {
            System.out.println( "    no system Java compiler: run the controls on a JDK" );
            return false;
        }
        Files.createDirectories( classes );
        List<String> arguments = new ArrayList<>( Arrays.asList( "--release", "17", "-nowarn", "-d", classes.toString() ) );
        try ( DirectoryStream<Path> entries = Files.newDirectoryStream( source, "*.java" ) )
        {
            for ( Path entry : entries )
            {
                arguments.add( entry.toString() );
            }
        }
        return compiler.run( null, null, null, arguments.toArray( new String[0] ) ) == 0;
    }

    // runs the conformance runner against a library build in a class loader of
    // its own, assertions off, and returns its control verdict
    private static boolean runConformance( Path libraryClasses, Path testClasses, List<Path> files, Path expected )
        throws Exception
    {
        URL[] urls = { libraryClasses.toUri().toURL(), testClasses.toUri().toURL() };
        try ( URLClassLoader loader = new URLClassLoader( urls, ClassLoader.getPlatformClassLoader() ) )
        {
            loader.setDefaultAssertionStatus( false );
            Class<?> runner = loader.loadClass( "serialize.tests.ConformanceTests" );
            Method method = runner.getMethod( "runControl", List.class, Path.class );
            return (Boolean) method.invoke( null, files, expected );
        }
    }
}
