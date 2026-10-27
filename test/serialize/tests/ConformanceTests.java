package serialize.tests;

import serialize.BitStream;
import serialize.BoolRef;
import serialize.DoubleRef;
import serialize.FloatRef;
import serialize.Int128Value;
import serialize.IntRef;
import serialize.LongRef;
import serialize.MeasureStream;
import serialize.ReadStream;
import serialize.Ref;
import serialize.Refusal;
import serialize.UInt128Value;
import serialize.WriteStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.test;

/**
 * The shared conformance corpus, run through this library's reader, writer and
 * measure.
 *
 * {@code conformance/} is a verbatim vendored copy of the corpus in
 * mas-bandwidth/serialize, one file per covered operation, and CI fails if the
 * two diverge. Every vector in it runs here, and nothing in this file
 * regenerates an expectation: the corpus is the authority, and a suite that
 * computes its own answers proves only that the port agrees with itself.
 *
 * The directory is DISCOVERED at run time rather than named in this source, so
 * a vector file vendored in but never mentioned still runs, and an empty
 * directory fails the run. A vector whose operation this runner cannot drive
 * FAILS, and so does a vector naming a parameter it does not understand: a
 * gate that skips what it does not understand reports green over a rule nobody
 * checked.
 *
 * One STEP MACHINE drives both the single operation files and the sequence,
 * object and message files. A single operation vector is a one or two step
 * sequence built from the record's own parameters, so the sequence files
 * cannot drift away from the operation files.
 *
 * An accepted vector must decode to the stated value and consume the stated
 * bits. A vector carrying {@code writer = canonical} is additionally re-emitted
 * through the write stream and compared byte for byte, flush included, which is
 * where the trailing bits obligation bites. A vector carrying
 * {@code measure_at_least} is run through the measure stream, as a floor rather
 * than an equality, because a measure is a bound and not the packet size.
 *
 * A vector carrying {@code writer_input} hands the writer that float32 and
 * requires the record's bytes exactly, on a refused vector too: its input is
 * the record's, not the decode.
 *
 * A refused vector must be refused FOR THE REASON IT STATES, from the closed
 * list in STANDARD.md, and the reason compared is the stream's first; it must
 * leave the caller's scalar destination holding the sentinel it was seeded
 * with, and must leave the stream TERMINAL.
 * Terminality is checked by BEHAVIOR rather than by an accessor, so the check
 * is the same one every port in the family runs: every later step of a sequence
 * must refuse too, and a further read the vector does not name must fail,
 * consume no bits and write nothing to its own destination. STANDARD.md leaves
 * a caller-owned BUFFER unspecified after a refusal — {@code bytes},
 * and {@code string} — so those destinations are not checked.
 *
 * NEGATIVE CONTROLS run this runner through {@link #runControl}, with one
 * rule of the library deleted, and require the failing vectors to be EXACTLY
 * the ones {@code conformance-controls/<rule>.expected} names. {@link Controls}
 * builds those readers.
 *
 * THE BUFFER CONTRACT. Every stream presented here carries the eight bytes of
 * slack {@link serialize.BitReader} requires, filled with a non-zero pattern,
 * so a decode that depends on memory past the end cannot pass by reading zeros.
 */
public final class ConformanceTests
{
    private ConformanceTests() {}

    /**
     * The environment variable that points this runner at another corpus,
     * such as mas-bandwidth/serialize main HEAD in the nightly conformance
     * functional test. Unset or empty, the vendored {@code conformance/} runs.
     */
    static final String CORPUS_DIR_VARIABLE = "SERIALIZE_CONFORMANCE_DIR";

    /** The same for {@code conformance-controls/}, which {@link Controls} reads. */
    static final String CONTROLS_DIR_VARIABLE = "SERIALIZE_CONFORMANCE_CONTROLS_DIR";

    private static final Path CORPUS = corpusDir();

    /**
     * Resolves a corpus directory: the override when it is set and non-empty,
     * otherwise the vendored directory. A set override is taken as written and
     * never falls back, so a nightly pointed at a missing directory fails on
     * the empty corpus rather than quietly running the vendored copy.
     */
    static Path resolveDir( String override, String vendored )
    {
        return override == null || override.isEmpty() ? Paths.get( vendored ) : Paths.get( override );
    }

    /** The conformance corpus this run reads. */
    static Path corpusDir()
    {
        return resolveDir( System.getenv( CORPUS_DIR_VARIABLE ), "conformance" );
    }

    /** The negative controls' directory this run reads. */
    static Path controlsDir()
    {
        return resolveDir( System.getenv( CONTROLS_DIR_VARIABLE ), "conformance-controls" );
    }

    /** Non-zero, so a read that strays past the end of the data is visible. */
    private static final byte SLACK_FILL = (byte) 0xA5;

    /** The buffer contract: at least 8 bytes past the data. */
    private static final int SLACK = 8;

    /**
     * The sentinel a bit pattern destination is seeded with. It fits 32 bits,
     * so it survives the narrowing this runner performs on the way to float's
     * own width and a destination the library correctly left alone still reads
     * as written.
     */
    private static final BigInteger SENTINEL_BITS = BigInteger.valueOf( 0xCAFEF00DL );

    /** The sentinel a number destination is seeded with: small and negative, so it fits every width. */
    private static final BigInteger SENTINEL_NUMBER = BigInteger.valueOf( -1234567 );

    private static final BigInteger MASK_64 = BigInteger.ONE.shiftLeft( 64 ).subtract( BigInteger.ONE );

    private static final BigInteger MASK_128 = BigInteger.ONE.shiftLeft( 128 ).subtract( BigInteger.ONE );

    // what the run covered, reported at the end: a corpus whose writer or
    // measure legs never ran is a corpus running at less than its own strength
    private static int vectorsChecked;

    private static int writerChecks;

    private static int measureChecks;

    private static int writerInputChecks;

    // vector names are unique across the corpus
    private static final Set<String> names = new HashSet<>();

    // the vectors that failed, by name, for the negative controls
    private static final Set<String> failedNames = new LinkedHashSet<>();

    /**
     * Runs this suite on its own ({@code java serialize.tests.ConformanceTests}), so
     * the interop job can hold this reader and the pinned C++ reader to the same
     * corpus in one place. AllTests calls {@link #run} directly.
     */
    public static void main( String[] args )
    {
        run();
        System.exit( Harness.finish() );
    }

    static void run()
    {
        run( corpusFiles() );
    }

    /**
     * A NEGATIVE CONTROL's verdict: runs the files quietly and reports whether
     * the vectors that failed are exactly the ones the expected list names,
     * with no failure outside any vector. A null list runs the files and
     * reports whether the run went red at all, which is the unknown operation
     * control's verdict.
     */
    public static boolean runControl( List<Path> files, Path expected )
    {
        Harness.setQuiet( true );
        run( files );
        if ( expected == null )
        {
            return Harness.failures() > 0;
        }
        Set<String> expectedNames = new LinkedHashSet<>();
        try
        {
            for ( String line : Files.readAllLines( expected ) )
            {
                String name = line.trim();
                if ( !name.isEmpty() && !name.startsWith( "#" ) )
                {
                    expectedNames.add( name );
                }
            }
        }
        catch ( IOException error )
        {
            throw new UncheckedIOException( error );
        }
        boolean match = !expectedNames.isEmpty();
        for ( String name : expectedNames )
        {
            if ( !failedNames.contains( name ) )
            {
                System.out.printf( "    CONTROL: %s should have failed and passed%n", name );
                match = false;
            }
        }
        for ( String name : failedNames )
        {
            if ( !expectedNames.contains( name ) )
            {
                System.out.printf( "    CONTROL: %s failed and is not the control's rule%n", name );
                match = false;
            }
        }
        if ( Harness.failures() != failedNames.size() )
        {
            System.out.printf( "    CONTROL: %d failure(s) outside any vector%n", Harness.failures() - failedNames.size() );
            match = false;
        }
        System.out.printf( "    %d vector(s) failed, the control names %d%n", failedNames.size(), expectedNames.size() );
        return match;
    }

    private static void run( List<Path> files )
    {
        test( "conformance: the vendored corpus is present", () -> {
            check( !files.isEmpty(), "no vector files under " + CORPUS.toAbsolutePath() );
        } );

        for ( Path file : files )
        {
            String fileName = file.getFileName().toString();
            List<Vector> vectors = new ArrayList<>();
            test( "conformance " + fileName + ": the file parses to vectors", () -> {
                vectors.addAll( parse( file ) );
                check( !vectors.isEmpty(), fileName + " parsed to no vectors" );
            } );
            for ( Vector vector : vectors )
            {
                test( "conformance " + fileName + ": " + vector.name, () -> {
                    try
                    {
                        vector.run();
                    }
                    catch ( RuntimeException | Error error )
                    {
                        failedNames.add( vector.name );
                        throw error;
                    }
                } );
            }
        }

        System.out.printf( "    %d vectors from %d file(s): %d writer checks, %d writer input checks, %d measure checks%n",
                           vectorsChecked, files.size(), writerChecks, writerInputChecks, measureChecks );
    }

    // Discovery, not a list of filenames: a vendored file no one named still runs.
    private static List<Path> corpusFiles()
    {
        List<Path> files = new ArrayList<>();
        if ( !Files.isDirectory( CORPUS ) )
        {
            return files;
        }
        try ( DirectoryStream<Path> entries = Files.newDirectoryStream( CORPUS, "*.txt" ) )
        {
            for ( Path entry : entries )
            {
                files.add( entry );
            }
        }
        catch ( IOException error )
        {
            throw new UncheckedIOException( error );
        }
        files.sort( null );
        return files;
    }

    // STANDARD.md, "The vector format" and its lexical rules: `#` begins a
    // comment at the START of a line and nowhere else, blank lines separate
    // records, and each record is a key and a value one per line.
    private static List<Vector> parse( Path file )
    {
        List<String> lines;
        try
        {
            lines = Files.readAllLines( file );
        }
        catch ( IOException error )
        {
            throw new UncheckedIOException( error );
        }

        List<Vector> vectors = new ArrayList<>();
        Vector current = new Vector( file );
        for ( String rawLine : lines )
        {
            if ( rawLine.startsWith( "#" ) )
            {
                continue;
            }
            String line = rawLine.trim();
            if ( line.isEmpty() )
            {
                if ( current.started() )
                {
                    vectors.add( current );
                    current = new Vector( file );
                }
                continue;
            }
            int space = line.indexOf( ' ' );
            String key = space < 0 ? line : line.substring( 0, space );
            String value = space < 0 ? "" : line.substring( space + 1 ).trim();
            current.put( key, value );
        }
        if ( current.started() )
        {
            vectors.add( current );
        }
        return vectors;
    }

    // ---------------------------------------------------------------------
    // numbers
    //
    // A vector's number is signed decimal or 0x hexadecimal and a parser must
    // accept values up to 128 bits wide, which is wider than any Java integral
    // type, so they travel as BigInteger and land in the operation's own width
    // at the call site.

    private static BigInteger parseNumber( String text )
    {
        String body = text;
        boolean negative = false;
        if ( body.startsWith( "-" ) )
        {
            negative = true;
            body = body.substring( 1 );
        }
        else if ( body.startsWith( "+" ) )
        {
            body = body.substring( 1 );
        }
        check( !body.isEmpty(), "malformed number: " + text );
        BigInteger value;
        if ( body.startsWith( "0x" ) || body.startsWith( "0X" ) )
        {
            String digits = body.substring( 2 );
            check( !digits.isEmpty(), "malformed number: " + text );
            value = new BigInteger( digits, 16 );
        }
        else
        {
            value = new BigInteger( body, 10 );
        }
        return negative ? value.negate() : value;
    }

    /** The 128-bit two's complement pattern of a value, which is how every numeric expectation compares. */
    private static BigInteger pattern128( BigInteger value )
    {
        return value.and( MASK_128 );
    }

    private static BigInteger unsigned64( long value )
    {
        return BigInteger.valueOf( value ).and( MASK_64 );
    }

    private static Int128Value toInt128( BigInteger value )
    {
        BigInteger bits = pattern128( value );
        return new Int128Value( bits.shiftRight( 64 ).longValue(), bits.and( MASK_64 ).longValue() );
    }

    private static BigInteger fromInt128( Int128Value value )
    {
        return BigInteger.valueOf( value.hi ).shiftLeft( 64 ).add( unsigned64( value.lo ) );
    }

    private static UInt128Value toUInt128( BigInteger value )
    {
        BigInteger bits = pattern128( value );
        return new UInt128Value( bits.shiftRight( 64 ).longValue(), bits.and( MASK_64 ).longValue() );
    }

    private static BigInteger fromUInt128( UInt128Value value )
    {
        return unsigned64( value.hi ).shiftLeft( 64 ).or( unsigned64( value.lo ) );
    }

    // ---------------------------------------------------------------------
    // the step machine

    private enum Kind
    {
        BITS,
        BOOL,
        UINT128,
        ALIGN,
        INT,
        INT64,
        INT128,
        INT_RELATIVE,
        FLOAT,
        DOUBLE,
        COMPRESSED_FLOAT,
        BYTES,
        STRING,
        FIXED,
        END,
        OBJECT,                             // opens a nested object over the steps that follow
        REJECT                              // the caller refusing: reject() at the top level, a false return inside an object
    }

    private static boolean valueIsABitPattern( Kind kind )
    {
        return kind == Kind.BITS || kind == Kind.UINT128 || kind == Kind.FLOAT
            || kind == Kind.DOUBLE || kind == Kind.COMPRESSED_FLOAT;
    }

    private static boolean valueIsANumber( Kind kind )
    {
        return kind == Kind.INT || kind == Kind.INT64 || kind == Kind.INT128
            || kind == Kind.INT_RELATIVE || kind == Kind.FIXED;
    }

    private static final class Step
    {
        Kind kind;
        int width;                          // bits, count, buffer_size, or the steps an object wraps
        BigInteger min = BigInteger.ZERO;
        BigInteger max = BigInteger.ZERO;
        float floatMin;
        float floatMax;
        float resolution;
        int integerBits;
        int fractionBits;
        int previous;

        // destinations
        BigInteger bits = BigInteger.ZERO;  // the decoded value where a bit pattern is what is pinned
        BigInteger number = BigInteger.ZERO;
        boolean booleanValue;
        byte[] buffer = new byte[0];
        String text = "";
    }

    /**
     * Runs one step against any stream. The step carries the value in and out,
     * so the reader leg's decoded values are exactly what the writer and the
     * measure legs are handed. The destination is written back whether the
     * operation accepted or refused, which is what makes the sentinel check
     * after a refusal meaningful.
     */
    private static boolean runStep( BitStream stream, Step step )
    {
        switch ( step.kind )
        {
            case BITS:
            {
                LongRef value = new LongRef( step.bits.longValue() );
                boolean ok = stream.serializeBits64( value, step.width );
                step.bits = unsigned64( value.value );
                return ok;
            }
            case BOOL:
            {
                BoolRef value = new BoolRef( step.booleanValue );
                boolean ok = stream.serializeBool( value );
                step.booleanValue = value.value;
                return ok;
            }
            case UINT128:
            {
                Ref<UInt128Value> value = new Ref<>( toUInt128( step.bits ) );
                boolean ok = stream.serializeUint128( value );
                step.bits = fromUInt128( value.value );
                return ok;
            }
            case ALIGN:
                return stream.serializeAlign();

            case INT:
            {
                IntRef value = new IntRef( step.number.intValue() );
                boolean ok = stream.serializeInt( value, step.min.intValue(), step.max.intValue() );
                step.number = BigInteger.valueOf( value.value );
                return ok;
            }
            case INT64:
            {
                LongRef value = new LongRef( step.number.longValue() );
                boolean ok = stream.serializeInt64( value, step.min.longValue(), step.max.longValue() );
                step.number = BigInteger.valueOf( value.value );
                return ok;
            }
            case INT128:
            {
                Ref<Int128Value> value = new Ref<>( toInt128( step.number ) );
                boolean ok = stream.serializeInt128( value, toInt128( step.min ), toInt128( step.max ) );
                step.number = fromInt128( value.value );
                return ok;
            }
            case INT_RELATIVE:
            {
                IntRef value = new IntRef( step.number.intValue() );
                boolean ok = stream.serializeIntRelative( step.previous, value );
                step.number = BigInteger.valueOf( value.value );
                return ok;
            }
            case FLOAT:
            {
                FloatRef value = new FloatRef( Float.intBitsToFloat( step.bits.intValue() ) );
                boolean ok = stream.serializeFloat( value );
                step.bits = BigInteger.valueOf( Integer.toUnsignedLong( Float.floatToRawIntBits( value.value ) ) );
                return ok;
            }
            case DOUBLE:
            {
                DoubleRef value = new DoubleRef( Double.longBitsToDouble( step.bits.longValue() ) );
                boolean ok = stream.serializeDouble( value );
                step.bits = unsigned64( Double.doubleToRawLongBits( value.value ) );
                return ok;
            }
            case COMPRESSED_FLOAT:
            {
                FloatRef value = new FloatRef( Float.intBitsToFloat( step.bits.intValue() ) );
                boolean ok = stream.serializeCompressedFloat( value, step.floatMin, step.floatMax, step.resolution );
                step.bits = BigInteger.valueOf( Integer.toUnsignedLong( Float.floatToRawIntBits( value.value ) ) );
                return ok;
            }
            case BYTES:
            {
                if ( step.buffer.length != step.width )
                {
                    step.buffer = new byte[step.width];
                }
                return stream.serializeBytes( step.buffer, step.width );
            }
            case STRING:
            {
                Ref<String> value = new Ref<>( step.text );
                boolean ok = stream.serializeString( value, step.width );
                step.text = value.value;
                return ok;
            }
            case FIXED:
            {
                if ( step.integerBits + step.fractionBits == 128 )
                {
                    Ref<Int128Value> value = new Ref<>( toInt128( step.number ) );
                    boolean ok = stream.serializeFixed128( value, step.integerBits, step.fractionBits,
                                                           step.min.longValue(), step.max.longValue() );
                    step.number = fromInt128( value.value );
                    return ok;
                }
                LongRef value = new LongRef( step.number.longValue() );
                boolean ok = stream.serializeFixed( value, step.integerBits, step.fractionBits,
                                                    step.min.longValue(), step.max.longValue() );
                step.number = BigInteger.valueOf( value.value );
                return ok;
            }
            case END:
                return stream.serializeEnd();

            // the top level reject: the caller refuses through the library's entry
            // point. Inside an object runSteps handles it as the callback's false.
            case REJECT:
                return stream.reject();

            case OBJECT:
            default:
                // nesting is driven by runSteps, which owns the step range an object
                // wraps; a bare object step reaching here is a runner bug
                return false;
        }
    }

    // advances past the steps a nested object owns, so a walk over the top level sees one step per object
    private static int stepSpan( List<Step> steps, int index )
    {
        return steps.get( index ).kind == Kind.OBJECT ? 1 + steps.get( index ).width : 1;
    }

    /**
     * The run state a walk over the steps leaves behind: which top level step a
     * refusal stopped on, and which primitive step it was that refused, which is
     * the destination the sentinel check inspects.
     */
    private static final class Walk
    {
        int stoppedAt = -1;
        Step failedStep;
    }

    /**
     * Runs a range of steps against a stream. A step spelled {@code object <n>}
     * wraps the next n steps in a nested object, driven through the library's own
     * {@code serializeObject} so what the vectors exercise is the composition the
     * operation performs. A {@code reject} step inside an object is the object's
     * serialize function returning false there without refusing on its own, which
     * the object must latch as rejected; at the top level it is {@code reject()}.
     */
    private static boolean runSteps( BitStream stream, List<Step> steps, int from, int count, Walk walk )
    {
        return runSteps( stream, steps, from, count, walk, false );
    }

    private static boolean runSteps( BitStream stream, List<Step> steps, int from, int count, Walk walk,
                                     boolean inObject )
    {
        int end = from + count;
        int i = from;
        while ( i < end )
        {
            Step step = steps.get( i );
            if ( step.kind == Kind.OBJECT )
            {
                final int nested = i + 1;
                final int nestedCount = step.width;
                final boolean alreadyFailed = ( stream instanceof ReadStream ) && ( (ReadStream) stream ).isFailed();
                final boolean[] called = new boolean[1];
                if ( !stream.serializeObject( inner -> {
                    called[0] = true;
                    return runSteps( inner, steps, nested, nestedCount, walk, true );
                } ) )
                {
                    check( !alreadyFailed || !called[0],
                           "serializeObject invoked its callback on an already-failed ReadStream" );
                    if ( walk.stoppedAt < 0 )
                    {
                        walk.stoppedAt = i;
                    }
                    return false;
                }
            }
            else if ( step.kind == Kind.REJECT && inObject )
            {
                // the callback fails here on its own, refusing nothing itself
                return false;
            }
            else if ( !runStep( stream, step ) )
            {
                walk.failedStep = step;
                if ( walk.stoppedAt < 0 )
                {
                    walk.stoppedAt = i;
                }
                return false;
            }
            i += stepSpan( steps, i );
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // rendering and comparison
    //
    // Numeric values — every integer width, and the float, double and
    // compressed_float bit patterns — compare as 128-bit two's complement
    // PATTERNS, so a hexadecimal expectation and its decimal twin are one
    // expectation and nothing goes through a float. STANDARD.md requires that:
    // NaN compares unequal to itself, -0.0 == 0.0, and a tolerance comparison
    // cannot see a quieted signaling bit.

    private static String hexBytes( byte[] data, int count )
    {
        StringBuilder text = new StringBuilder();
        for ( int i = 0; i < count; i++ )
        {
            if ( text.length() > 0 )
            {
                text.append( ' ' );
            }
            text.append( String.format( "%02X", data[i] & 0xFF ) );
        }
        return text.toString();
    }

    private static BigInteger stepPattern( Step step )
    {
        if ( valueIsABitPattern( step.kind ) )
        {
            return pattern128( step.bits );
        }
        if ( valueIsANumber( step.kind ) )
        {
            return pattern128( step.number );
        }
        return null;
    }

    private static String renderStepValue( Step step )
    {
        BigInteger value = stepPattern( step );
        if ( value != null )
        {
            return "0x" + String.format( "%032X", value );
        }
        switch ( step.kind )
        {
            case BOOL:
                return step.booleanValue ? "true" : "false";
            case BYTES:
                return hexBytes( step.buffer, step.width );
            case STRING:
            {
                byte[] utf8 = step.text.getBytes( StandardCharsets.UTF_8 );
                return hexBytes( utf8, utf8.length );
            }
            case ALIGN:
            case END:
            case OBJECT:
            case REJECT:
            default:
                // none has a value of its own; for align and end the corpus states
                // the padding consumed, which a conforming read always finds zero
                return "0";
        }
    }

    private static boolean expectationMatches( Step step, String expected )
    {
        BigInteger value = stepPattern( step );
        if ( value != null )
        {
            return value.equals( pattern128( parseNumber( expected ) ) );
        }
        return renderStepValue( step ).equals( expected );
    }

    // ---------------------------------------------------------------------
    // one vector

    private static final class Vector
    {
        final String file;
        String operation;
        String name;
        final Map<String, String> params = new LinkedHashMap<>();
        final List<String> stepWords = new ArrayList<>();
        byte[] bytes = new byte[0];
        boolean refused;
        String refusal = "";                // the reason a refused vector states
        String expected = "";
        long consumed;
        boolean hasConsumed;
        long measureAtLeast;
        boolean hasMeasure;
        boolean writerCanonical;
        boolean hasWriterInput;
        int writerInputBits;

        Vector( Path file )
        {
            this.file = file.getFileName().toString();
        }

        boolean started()
        {
            return operation != null;
        }

        void put( String key, String value )
        {
            switch ( key )
            {
                case "operation":
                    operation = value;
                    break;
                case "name":
                    name = value;
                    break;
                case "param":
                {
                    int equals = value.indexOf( '=' );
                    check( equals > 0, "malformed param: " + value );
                    String paramName = value.substring( 0, equals ).trim();
                    String paramValue = value.substring( equals + 1 ).trim();
                    if ( paramName.equals( "step" ) )
                    {
                        stepWords.add( paramValue );
                    }
                    else
                    {
                        params.put( paramName, paramValue );
                    }
                    break;
                }
                case "bytes":
                    bytes = hex( value );
                    break;
                case "expect":
                    if ( value.equals( "refused" ) || value.startsWith( "refused " ) )
                    {
                        // the reason is mandatory and comes from the closed list: a
                        // bare `refused`, or a word the list does not hold, is a
                        // malformed vector rather than a looser one
                        String reason = value.substring( "refused".length() ).trim();
                        check( Refusal.fromSpelling( reason ) != null,
                               "expect refused must state a reason from the closed list, not '" + reason + "'" );
                        refused = true;
                        refusal = reason;
                    }
                    else
                    {
                        int equals = value.indexOf( '=' );
                        check( equals > 0, "malformed expect: " + value );
                        String expectKind = value.substring( 0, equals ).trim();
                        check( expectKind.equals( "bits" ) || expectKind.equals( "value" ),
                               "unknown expect kind '" + expectKind + "'" );
                        expected = value.substring( equals + 1 ).trim();
                    }
                    break;
                case "consumed":
                    consumed = Long.parseLong( value );
                    hasConsumed = true;
                    break;
                case "measure_at_least":
                    measureAtLeast = Long.parseLong( value );
                    hasMeasure = true;
                    break;
                case "writer":
                    check( value.equals( "canonical" ), "unknown writer mode '" + value + "'" );
                    writerCanonical = true;
                    break;
                case "writer_input":
                {
                    int equals = value.indexOf( '=' );
                    check( equals > 0 && value.substring( 0, equals ).trim().equals( "bits" ),
                           "malformed writer_input: " + value );
                    writerInputBits = parseNumber( value.substring( equals + 1 ).trim() ).intValue();
                    hasWriterInput = true;
                    break;
                }
                default:
                    check( false, "unknown vector key: " + key );
                    break;
            }
        }

        private void fail( String detail )
        {
            check( false, detail + " [" + file + "]" );
        }

        private String param( String key )
        {
            String value = params.get( key );
            if ( value == null )
            {
                fail( "no param " + key + " on operation " + operation );
            }
            return value;
        }

        private int intParam( String key )
        {
            return parseNumber( param( key ) ).intValue();
        }

        /**
         * A parameter this runner does not understand is a FAILURE and not a
         * silent default: a vector whose declaration is not the one being
         * exercised proves nothing.
         */
        private static boolean operationTakesParam( String operation, String name )
        {
            switch ( name )
            {
                case "preceding_bits":
                    return operation.equals( "align" ) || operation.equals( "bytes" );
                case "bits":
                    return operation.equals( "bits" );
                case "count":
                    return operation.equals( "bytes" );
                case "buffer_size":
                    return operation.equals( "string" );
                case "previous":
                    return operation.equals( "int_relative" );
                case "res":
                    return operation.equals( "compressed_float" );
                case "integer_bits":
                case "fraction_bits":
                    return operation.equals( "fixed" );
                case "min":
                case "max":
                    return operation.equals( "int" ) || operation.equals( "int64" ) || operation.equals( "int128" )
                        || operation.equals( "fixed" ) || operation.equals( "compressed_float" );
                default:
                    return false;
            }
        }

        /** The reader's buffer: the vector's bytes plus the slack the buffer contract requires, non-zero filled. */
        private byte[] readerBuffer()
        {
            byte[] buffer = new byte[bytes.length + SLACK];
            Arrays.fill( buffer, SLACK_FILL );
            System.arraycopy( bytes, 0, buffer, 0, bytes.length );
            return buffer;
        }

        // A step spelling inside a sequence. The spellings are documented at the
        // head of conformance/sequence.txt and conformance/object.txt.
        private Step stepFromWords( String text )
        {
            String[] words = text.trim().split( "\\s+" );
            Step step = new Step();
            switch ( words[0] )
            {
                case "bits":
                    check( words.length == 2, "malformed step: " + text );
                    step.kind = Kind.BITS;
                    step.width = parseNumber( words[1] ).intValue();
                    return step;
                case "bool":
                    check( words.length == 1, "malformed step: " + text );
                    step.kind = Kind.BOOL;
                    return step;
                case "align":
                    check( words.length == 1, "malformed step: " + text );
                    step.kind = Kind.ALIGN;
                    return step;
                case "float":
                    check( words.length == 1, "malformed step: " + text );
                    step.kind = Kind.FLOAT;
                    return step;
                case "double":
                    check( words.length == 1, "malformed step: " + text );
                    step.kind = Kind.DOUBLE;
                    return step;
                case "uint128":
                    check( words.length == 1, "malformed step: " + text );
                    step.kind = Kind.UINT128;
                    return step;
                case "int_relative":
                    check( words.length == 2, "malformed step: " + text );
                    step.kind = Kind.INT_RELATIVE;
                    step.previous = parseNumber( words[1] ).intValue();
                    return step;
                case "compressed_float":
                    check( words.length == 4, "malformed step: " + text );
                    step.kind = Kind.COMPRESSED_FLOAT;
                    step.floatMin = (float) Double.parseDouble( words[1] );
                    step.floatMax = (float) Double.parseDouble( words[2] );
                    step.resolution = (float) Double.parseDouble( words[3] );
                    return step;
                case "object":
                    check( words.length == 2, "malformed step: " + text );
                    step.kind = Kind.OBJECT;
                    step.width = parseNumber( words[1] ).intValue();
                    return step;
                case "bytes":
                    check( words.length == 2, "malformed step: " + text );
                    step.kind = Kind.BYTES;
                    step.width = parseNumber( words[1] ).intValue();
                    return step;
                case "string":
                    check( words.length == 2, "malformed step: " + text );
                    step.kind = Kind.STRING;
                    step.width = parseNumber( words[1] ).intValue();
                    return step;
                case "end":
                    check( words.length == 1, "malformed step: " + text );
                    step.kind = Kind.END;
                    return step;
                case "reject":
                    check( words.length == 1, "malformed step: " + text );
                    check( refused, "a reject step appears only in refused vectors: " + name );
                    step.kind = Kind.REJECT;
                    return step;
                case "int":
                case "int64":
                case "int128":
                    check( words.length == 3, "malformed step: " + text );
                    step.kind = words[0].equals( "int" ) ? Kind.INT
                              : words[0].equals( "int64" ) ? Kind.INT64 : Kind.INT128;
                    step.min = parseNumber( words[1] );
                    step.max = parseNumber( words[2] );
                    return step;
                case "fixed":
                    check( words.length == 5, "malformed step: " + text );
                    step.kind = Kind.FIXED;
                    step.integerBits = parseNumber( words[1] ).intValue();
                    step.fractionBits = parseNumber( words[2] ).intValue();
                    step.min = parseNumber( words[3] );
                    step.max = parseNumber( words[4] );
                    return step;
                default:
                    fail( "no runner for step '" + text + "'" );
                    return step;
            }
        }

        /**
         * The step list for a vector. A single operation vector becomes a one or
         * two step sequence: the operations whose interesting behavior only
         * exists at a non-zero bit index take a {@code preceding_bits}
         * parameter, which becomes a leading bits step.
         */
        private List<Step> buildSteps()
        {
            List<Step> steps = new ArrayList<>();
            if ( operation.equals( "sequence" ) )
            {
                for ( String words : stepWords )
                {
                    steps.add( stepFromWords( words ) );
                }
                check( !steps.isEmpty(), "a sequence with no steps" );
                return steps;
            }

            if ( params.containsKey( "preceding_bits" ) )
            {
                int precedingBits = intParam( "preceding_bits" );
                if ( precedingBits > 0 )
                {
                    Step leading = new Step();
                    leading.kind = Kind.BITS;
                    leading.width = precedingBits;
                    steps.add( leading );
                }
            }

            Step step = new Step();
            switch ( operation )
            {
                case "bits":
                    step.kind = Kind.BITS;
                    step.width = intParam( "bits" );
                    break;
                case "bool":
                    step.kind = Kind.BOOL;
                    break;
                case "uint128":
                    step.kind = Kind.UINT128;
                    break;
                case "align":
                    step.kind = Kind.ALIGN;
                    break;
                case "int":
                    step.kind = Kind.INT;
                    step.min = parseNumber( param( "min" ) );
                    step.max = parseNumber( param( "max" ) );
                    break;
                case "int64":
                    step.kind = Kind.INT64;
                    step.min = parseNumber( param( "min" ) );
                    step.max = parseNumber( param( "max" ) );
                    break;
                case "int128":
                    step.kind = Kind.INT128;
                    step.min = parseNumber( param( "min" ) );
                    step.max = parseNumber( param( "max" ) );
                    break;
                case "int_relative":
                    step.kind = Kind.INT_RELATIVE;
                    step.previous = intParam( "previous" );
                    break;
                case "float":
                    step.kind = Kind.FLOAT;
                    break;
                case "double":
                    step.kind = Kind.DOUBLE;
                    break;
                case "compressed_float":
                    step.kind = Kind.COMPRESSED_FLOAT;
                    step.floatMin = floatParam( "min" );
                    step.floatMax = floatParam( "max" );
                    step.resolution = floatParam( "res" );
                    break;
                case "bytes":
                    step.kind = Kind.BYTES;
                    step.width = intParam( "count" );
                    break;
                case "string":
                    step.kind = Kind.STRING;
                    step.width = intParam( "buffer_size" );
                    break;
                case "end":
                    step.kind = Kind.END;
                    break;
                case "fixed":
                    step.kind = Kind.FIXED;
                    step.integerBits = intParam( "integer_bits" );
                    step.fractionBits = intParam( "fraction_bits" );
                    step.min = parseNumber( param( "min" ) );
                    step.max = parseNumber( param( "max" ) );
                    break;
                default:
                    // a corpus file this runner cannot drive is a gap in the runner, not a pass
                    fail( "no runner for operation '" + operation + "'" );
                    break;
            }
            steps.add( step );
            return steps;
        }

        // res, min and max under compressed_float are float32 operands
        private float floatParam( String key )
        {
            return (float) Double.parseDouble( param( key ) );
        }

        void run()
        {
            vectorsChecked++;
            if ( name == null || !names.add( name ) )
            {
                fail( "the vector name '" + name + "' is not unique across the corpus" );
            }
            for ( String paramName : params.keySet() )
            {
                if ( !operationTakesParam( operation, paramName ) )
                {
                    fail( "no runner for parameter '" + paramName + "' on operation '" + operation + "'" );
                }
            }
            if ( !stepWords.isEmpty() && !operation.equals( "sequence" ) )
            {
                fail( "steps are only meaningful on a sequence" );
            }

            List<Step> steps = buildSteps();

            runReader( steps );

            // the writer input leg stands alone: its input is the record's, not
            // the decode, so it runs on refused vectors too
            if ( hasWriterInput )
            {
                runWriterInput( buildSteps() );
            }

            // the writer and the measure are handed the values the reader decoded,
            // so a canonical vector's round trip is decode then re-emit
            if ( !refused )
            {
                if ( writerCanonical )
                {
                    runWriter( steps );
                }
                if ( hasMeasure )
                {
                    runMeasure( steps );
                }
            }
        }

        private void runReader( List<Step> steps )
        {
            ReadStream stream = new ReadStream( readerBuffer(), bytes.length );

            for ( Step step : steps )
            {
                step.bits = SENTINEL_BITS;
                step.number = SENTINEL_NUMBER;
                step.booleanValue = true;       // a refused bool read must leave this alone
                step.text = "";
            }

            Walk walk = new Walk();
            boolean accepted = runSteps( stream, steps, 0, steps.size(), walk );

            if ( refused )
            {
                if ( accepted )
                {
                    fail( "the read succeeded, the corpus requires refusal" );
                }

                // STANDARD.md, "Refusal reasons": the refusal must be for the
                // reason the vector states, and the reason is the FIRST
                // refusal's. A refusal under another rule is a disagreement
                // about the format however red the stream ends up.
                String reason = stream.getRefusal().toString();
                if ( !reason.equals( refusal ) )
                {
                    fail( "refused for " + reason + ", the corpus states " + refusal );
                }

                // STANDARD.md, "A refused primitive read must leave its destination
                // unwritten". The rule reaches the scalars only: a read into a
                // caller-owned buffer — bytes and string — leaves that
                // buffer unspecified after a refusal, so those are not checked.
                Step failedStep = walk.failedStep;
                if ( failedStep != null )
                {
                    if ( valueIsABitPattern( failedStep.kind ) && !failedStep.bits.equals( SENTINEL_BITS ) )
                    {
                        fail( "the refused read wrote to the destination" );
                    }
                    if ( valueIsANumber( failedStep.kind ) && !failedStep.number.equals( SENTINEL_NUMBER ) )
                    {
                        fail( "the refused read wrote to the destination" );
                    }
                    if ( failedStep.kind == Kind.BOOL && !failedStep.booleanValue )
                    {
                        fail( "the refused read wrote to the destination" );
                    }
                }

                // Failure is terminal, and a sequence states its own successors:
                // every step after the failing one must fail too, however many
                // readable bits the stream still holds.
                for ( int i = walk.stoppedAt + stepSpan( steps, walk.stoppedAt ); i < steps.size();
                      i += stepSpan( steps, i ) )
                {
                    if ( runSteps( stream, steps, i, stepSpan( steps, i ), new Walk() ) )
                    {
                        fail( "step " + ( i + 1 ) + " succeeded after step " + ( walk.stoppedAt + 1 )
                              + " was refused; failure must be terminal" );
                    }
                }

                // and the same rule against a read the vector does not name, so
                // every refused vector carries the terminality check and not only
                // the sequences that spell a successor
                failUnlessStreamIsTerminal( stream );
                return;
            }

            if ( !accepted )
            {
                fail( "the read was refused, the corpus requires it to be accepted" );
            }

            String[] entries = expected.split( "\\|", -1 );
            // one expect entry per step, objects and aligns included, which state
            // `-`. A leading preceding_bits step carries no entry of its own: it
            // exists to place the stream, and the record states only the operation
            // under test, so the entries align to the END of the step list.
            int offset = steps.size() - entries.length;
            if ( offset < 0 )
            {
                fail( "the expect list states more values than the vector has steps" );
            }
            for ( int i = 0; i < entries.length; i++ )
            {
                String entry = entries[i].trim();
                if ( entry.equals( "-" ) )
                {
                    continue;
                }
                Step step = steps.get( offset + i );
                if ( !expectationMatches( step, entry ) )
                {
                    fail( "step " + ( offset + i + 1 ) + " decoded " + renderStepValue( step )
                          + ", the corpus states " + entry );
                }
            }

            if ( hasConsumed && stream.getBitsProcessed() != consumed )
            {
                fail( "consumed " + stream.getBitsProcessed() + " bits, the corpus states " + consumed );
            }
        }

        // Failure is terminal, and a refused vector is where that rule is
        // testable. The stream is checked by BEHAVIOR rather than by an accessor,
        // so the same check ports to every implementation in the family: a further
        // read must fail, consume no bits and leave its destination alone.
        private void failUnlessStreamIsTerminal( ReadStream stream )
        {
            IntRef after = new IntRef( 0xFFFFFFFF );
            long bitsBefore = stream.getBitsProcessed();
            Refusal reasonBefore = stream.getRefusal();
            boolean[] objectCalled = new boolean[1];
            if ( stream.serializeObject( s -> {
                objectCalled[0] = true;
                return true;
            } ) )
            {
                fail( "the stream accepted an object read after the refusal: failure is not terminal" );
            }
            if ( objectCalled[0] )
            {
                fail( "the object read after the refusal invoked its callback" );
            }
            if ( stream.serializeBits( after, 8 ) )
            {
                fail( "the stream accepted a read after the refusal: failure is not terminal" );
            }
            if ( after.value != 0xFFFFFFFF )
            {
                fail( "the read after the refusal wrote to its destination" );
            }
            if ( stream.getBitsProcessed() != bitsBefore )
            {
                fail( "the read after the refusal consumed bits" );
            }
            if ( stream.getRefusal() != reasonBefore )
            {
                fail( "the read after the refusal overwrote the refusal reason" );
            }
            if ( file.equals( "object.txt" ) )
            {
                ReadStream callbackRefused = new ReadStream( readerBuffer(), bytes.length );
                if ( callbackRefused.serializeObject( s -> false ) )
                {
                    fail( "serializeObject accepted a callback returning false" );
                }
                if ( !callbackRefused.isFailed() )
                {
                    fail( "serializeObject callback refusal did not latch the stream" );
                }
                if ( callbackRefused.getRefusal() != Refusal.REJECTED )
                {
                    fail( "serializeObject callback-only refusal did not latch rejected" );
                }
                IntRef probe = new IntRef( 0xFFFFFFFF );
                if ( callbackRefused.serializeInt( probe, 7, 7 ) || probe.value != 0xFFFFFFFF )
                {
                    fail( "zero-bit read succeeded after serializeObject callback refusal" );
                }

                ReadStream swallowed = new ReadStream( readerBuffer(), bytes.length );
                if ( swallowed.serializeObject( s -> {
                    runSteps( s, buildSteps(), 0, buildSteps().size(), new Walk() );
                    return true;
                } ) )
                {
                    fail( "serializeObject accepted a callback that returned true after an inner refusal" );
                }
                if ( !swallowed.isFailed() || swallowed.getRefusal() != reasonBefore )
                {
                    fail( "serializeObject did not preserve the inner refusal when the callback returned true" );
                }
            }
        }

        /**
         * The writer leg. A vector marked {@code writer = canonical} states the
         * bytes a conforming writer emits for its value, so the runner writes the
         * decoded steps back and compares the WHOLE emitted stream. That is what
         * pins the trailing bits obligation: the unused bits of the final byte
         * must be zero, and a writer leaking anything into them produces a byte
         * the vector does not carry.
         */
        private void runWriter( List<Step> steps )
        {
            writerChecks++;
            byte[] scratch = new byte[512];
            Arrays.fill( scratch, SLACK_FILL );
            WriteStream stream = new WriteStream( scratch, scratch.length );

            if ( !runSteps( stream, steps, 0, steps.size(), new Walk() ) )
            {
                fail( "the writer refused a canonical vector" );
            }
            stream.flush();

            long written = stream.getBytesProcessed();
            if ( written != bytes.length )
            {
                fail( "the writer emitted " + written + " bytes, the corpus states " + bytes.length );
            }
            for ( int i = 0; i < written; i++ )
            {
                if ( scratch[i] != bytes[i] )
                {
                    fail( "the writer emitted " + hexBytes( scratch, (int) written )
                          + ", the corpus states " + hexBytes( bytes, bytes.length ) );
                }
            }
        }

        /**
         * The writer input leg. A canonical vector can only re-emit the value it
         * decoded, which lands on a quantum where every plausible arithmetic
         * agrees, so a vector carrying {@code writer_input} hands the writer the
         * value a caller would have, between quanta or in the fusion band, and
         * the emission must be the vector's bytes exactly.
         */
        private void runWriterInput( List<Step> steps )
        {
            writerInputChecks++;
            if ( steps.size() != 1 || steps.get( 0 ).kind != Kind.COMPRESSED_FLOAT )
            {
                fail( "writer_input is only meaningful on a single compressed_float" );
            }
            steps.get( 0 ).bits = BigInteger.valueOf( Integer.toUnsignedLong( writerInputBits ) );

            byte[] scratch = new byte[512];
            Arrays.fill( scratch, SLACK_FILL );
            WriteStream stream = new WriteStream( scratch, scratch.length );
            if ( !runSteps( stream, steps, 0, steps.size(), new Walk() ) )
            {
                fail( "the writer refused its input" );
            }
            stream.flush();

            long written = stream.getBytesProcessed();
            boolean same = written == bytes.length;
            for ( int i = 0; same && i < written; i++ )
            {
                same = scratch[i] == bytes[i];
            }
            if ( !same )
            {
                fail( "the writer handed its input emitted " + hexBytes( scratch, (int) written )
                      + ", the corpus states " + hexBytes( bytes, bytes.length ) );
            }
        }

        /**
         * The measure leg. STANDARD.md makes a measure a BOUND and not the packet
         * size, so the corpus states a floor and the check is an inequality. A
         * measure that computes alignment from a running bit index starting at
         * zero under-counts every unaligned start and falls below the floor, which
         * is the non-conforming accounting the document names.
         */
        private void runMeasure( List<Step> steps )
        {
            measureChecks++;
            MeasureStream stream = new MeasureStream();
            if ( !runSteps( stream, steps, 0, steps.size(), new Walk() ) )
            {
                fail( "the measure refused a step; a measure refuses nothing at runtime" );
            }
            if ( stream.getBitsProcessed() < measureAtLeast )
            {
                fail( "measured " + stream.getBitsProcessed() + " bits, the corpus requires at least "
                      + measureAtLeast );
            }
        }
    }

    private static byte[] hex( String text )
    {
        String[] pairs = text.isEmpty() ? new String[0] : text.split( "\\s+" );
        byte[] bytes = new byte[pairs.length];
        for ( int i = 0; i < pairs.length; i++ )
        {
            bytes[i] = (byte) Integer.parseInt( pairs[i], 16 );
        }
        return bytes;
    }
}
