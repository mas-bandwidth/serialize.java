package serialize.fuzz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The fuzz harness's NEGATIVE CONTROL: a fuzzer nobody has seen go red is a
 * claim rather than a measurement. This copies the library source into a
 * scratch directory and plants ONE write/read asymmetry in the copy; the
 * Makefile builds that copy, runs the round trip target against it under the
 * smoke budget, and is green only when the fuzzer traps on it.
 *
 * The asymmetry is in the writer of the ranged 64 bit integer: a value whose
 * low 16 bits are 0x5A5A is written one higher than it is. Every other value
 * round trips, so a fuzzer that only replays its seeds, or a harness that
 * stopped comparing, stays green and fails the control. Finding it takes the
 * fuzzer reaching the int64 operations through the op program and steering
 * the value pool to the planted pattern.
 *
 * A needle that stops matching FAILS this tool rather than passing as a
 * no-op, as Controls.java does for the conformance controls: if it fails,
 * update the needle; never delete the control.
 *
 * Usage: {@code java serialize.fuzz.Sabotage <library source dir> <scratch source dir>}
 */
public final class Sabotage
{
    private Sabotage() {}

    static final String FILE = "WriteStream.java";

    static final String NEEDLE = "        long unsignedValue = value.value - min;\n";

    static final String REPLACEMENT = "        long unsignedValue = value.value - min + ( ( value.value & 0xFFFFL ) == 0x5A5AL ? 1 : 0 );"
        + " // NEGATIVE CONTROL: a planted write/read asymmetry\n";

    public static void main( String[] args ) throws IOException
    {
        if ( args.length != 2 )
        {
            System.err.println( "usage: java serialize.fuzz.Sabotage <library source dir> <scratch source dir>" );
            System.exit( 2 );
        }
        Path from = Paths.get( args[0] );
        Path to = Paths.get( args[1] );
        Files.createDirectories( to );

        int patched = 0;
        try ( DirectoryStream<Path> sources = Files.newDirectoryStream( from, "*.java" ) )
        {
            for ( Path source : sources )
            {
                String text = new String( Files.readAllBytes( source ), StandardCharsets.UTF_8 );
                if ( source.getFileName().toString().equals( FILE ) )
                {
                    int first = text.indexOf( NEEDLE );
                    if ( first < 0 || text.indexOf( NEEDLE, first + 1 ) >= 0 )
                    {
                        System.err.println( "Sabotage: the needle must match exactly once in " + FILE + "; update it, never delete the control" );
                        System.exit( 1 );
                    }
                    text = text.replace( NEEDLE, REPLACEMENT );
                    patched++;
                }
                Files.write( to.resolve( source.getFileName() ), text.getBytes( StandardCharsets.UTF_8 ) );
            }
        }
        if ( patched != 1 )
        {
            System.err.println( "Sabotage: " + FILE + " not found in " + from );
            System.exit( 1 );
        }
        System.out.println( "Sabotage: planted the asymmetry in " + to.resolve( FILE ) );
    }
}
