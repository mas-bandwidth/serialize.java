package serialize.tests;

import serialize.Int128Value;
import serialize.IntRef;
import serialize.ReadStream;
import serialize.Ref;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/**
 * The shared conformance corpus, run through this library's reader.
 *
 * {@code conformance/} is a verbatim vendored copy of the corpus in
 * mas-bandwidth/serialize, one file per operation, and CI fails if the two
 * diverge. Every vector in it runs here: an accepted vector must decode to
 * the stated value and consume the stated number of bits, and a refused
 * vector must be refused with the destination left unwritten. Nothing in
 * this file regenerates an expectation — the corpus is the authority, and a
 * suite that computes its own answers proves only that the port agrees with
 * itself.
 *
 * The corpus directory is read relative to the working directory, which is
 * the repository root under {@code make test}. An operation with no runner
 * below fails the suite rather than being skipped: a vector that does not
 * run is a vector that proves nothing.
 */
public final class ConformanceTests
{
    private ConformanceTests() {}

    private static final Path CORPUS = Paths.get( "conformance" );

    /** A value no vector decodes to, so a written destination is visible after a refusal. */
    private static final int SENTINEL = 0x5E5E5E5E;

    private static final Int128Value SENTINEL_128 = new Int128Value( 0x5E5E5E5E5E5E5E5EL, 0x5E5E5E5E5E5E5E5EL );

    private static final BigInteger MASK_64 = BigInteger.ONE.shiftLeft( 64 ).subtract( BigInteger.ONE );

    private static final BigInteger MASK_128 = BigInteger.ONE.shiftLeft( 128 ).subtract( BigInteger.ONE );

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
        List<Path> files = corpusFiles();
        test( "conformance: the vendored corpus is present", () -> {
            check( !files.isEmpty(), "no vector files under " + CORPUS.toAbsolutePath() );
        } );

        for ( Path file : files )
        {
            List<Vector> vectors = parse( file );
            String fileName = file.getFileName().toString();
            test( "conformance " + fileName + ": the file holds vectors", () -> {
                check( !vectors.isEmpty(), fileName + " parsed to no vectors" );
            } );
            for ( Vector vector : vectors )
            {
                test( "conformance " + fileName + ": " + vector.name, () -> vector.run() );
            }
        }
    }

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

    // STANDARD.md, "The vector format": text, `#` begins a comment, blank
    // lines separate records, each record is `key` and value one per line.
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
        Vector current = new Vector();
        for ( String rawLine : lines )
        {
            int comment = rawLine.indexOf( '#' );
            String line = ( comment >= 0 ? rawLine.substring( 0, comment ) : rawLine ).trim();
            if ( line.isEmpty() )
            {
                if ( current.started() )
                {
                    vectors.add( current );
                    current = new Vector();
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

    private static final class Vector
    {
        String operation;
        String name;
        final Map<String, String> params = new LinkedHashMap<>();
        byte[] bytes = new byte[0];
        boolean refused;
        String expected;
        long consumed;

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
                    params.put( value.substring( 0, equals ).trim(), value.substring( equals + 1 ).trim() );
                    break;
                }
                case "bytes":
                    bytes = hex( value );
                    break;
                case "expect":
                    if ( value.equals( "refused" ) )
                    {
                        refused = true;
                    }
                    else
                    {
                        int equals = value.indexOf( '=' );
                        check( equals > 0, "malformed expect: " + value );
                        expected = value.substring( equals + 1 ).trim();
                    }
                    break;
                case "consumed":
                    consumed = Long.parseLong( value );
                    break;
                default:
                    check( false, "unknown vector key: " + key );
                    break;
            }
        }

        String param( String key )
        {
            String value = params.get( key );
            check( value != null, "vector " + name + " has no param " + key );
            return value;
        }

        /** The reader's buffer: the vector's bytes plus the 8 bytes of slack the reader loads through. */
        byte[] buffer()
        {
            byte[] buffer = new byte[bytes.length + 8];
            System.arraycopy( bytes, 0, buffer, 0, bytes.length );
            return buffer;
        }

        void run()
        {
            switch ( operation )
            {
                case "int_relative":
                    runIntRelative();
                    break;
                case "int128":
                    runInt128();
                    break;
                default:
                    check( false, "the corpus carries operation " + operation + ", which this suite does not run" );
                    break;
            }
        }

        private void runIntRelative()
        {
            int previous = Integer.parseInt( param( "previous" ) );
            ReadStream reader = new ReadStream( buffer(), bytes.length );
            IntRef current = new IntRef( SENTINEL );
            boolean accepted = reader.serializeIntRelative( previous, current );
            if ( refused )
            {
                check( !accepted, "must refuse" );
                checkEqual( current.value, SENTINEL, "destination unwritten after a refusal" );
                return;
            }
            check( accepted, "must accept" );
            checkEqual( current.value, Long.parseLong( expected ), "decoded value" );
            checkEqual( reader.getBitsProcessed(), consumed, "bits consumed" );
        }

        private void runInt128()
        {
            Int128Value min = toInt128( new BigInteger( param( "min" ) ) );
            Int128Value max = toInt128( new BigInteger( param( "max" ) ) );
            ReadStream reader = new ReadStream( buffer(), bytes.length );
            Ref<Int128Value> value = new Ref<>( SENTINEL_128 );
            boolean accepted = reader.serializeInt128( value, min, max );
            if ( refused )
            {
                check( !accepted, "must refuse" );
                check( value.value.equals( SENTINEL_128 ), "destination unwritten after a refusal" );
                return;
            }
            check( accepted, "must accept" );
            check( fromInt128( value.value ).equals( new BigInteger( expected ) ),
                   "decoded value: got " + fromInt128( value.value ) + ", expected " + expected );
            checkEqual( reader.getBitsProcessed(), consumed, "bits consumed" );
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

    /** The decimal vector value as the library's two's-complement pair. */
    private static Int128Value toInt128( BigInteger value )
    {
        BigInteger bits = value.and( MASK_128 );
        return new Int128Value( bits.shiftRight( 64 ).longValue(), bits.and( MASK_64 ).longValue() );
    }

    /** The library's pair back to a signed decimal, for comparison against the vector. */
    private static BigInteger fromInt128( Int128Value value )
    {
        return BigInteger.valueOf( value.hi ).shiftLeft( 64 ).add( BigInteger.valueOf( value.lo ).and( MASK_64 ) );
    }
}
