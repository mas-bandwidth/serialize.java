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
 * A second mode, {@code read}, plants a READ defect instead, for the hostile
 * read target's own negative control (see READ_NEEDLE).
 *
 * Usage: {@code java serialize.fuzz.Sabotage <library source dir> <scratch source dir> [roundtrip|read]}
 */
public final class Sabotage
{
    private Sabotage() {}

    // mode "roundtrip" (the default): the write/read asymmetry
    static final String FILE = "WriteStream.java";

    static final String NEEDLE = "        long unsignedValue = value.value - min;\n";

    static final String REPLACEMENT = "        long unsignedValue = value.value - min + ( ( value.value & 0xFFFFL ) == 0x5A5AL ? 1 : 0 );"
        + " // NEGATIVE CONTROL: a planted write/read asymmetry\n";

    // mode "read": a planted READ defect for the hostile read target, an
    // off-by-one in the ranged int refusal, so the offset one past the span is
    // accepted and decodes to max + 1. Only hostile bytes reach it: a
    // conforming writer never sends that offset, so the round trip cannot see
    // it, and the hostile read's in-contract check must.
    static final String READ_FILE = "ReadStream.java";

    static final String READ_NEEDLE = "        if ( Integer.compareUnsigned( unsignedValue, max - min ) > 0 )\n";

    static final String READ_REPLACEMENT = "        if ( Integer.compareUnsigned( unsignedValue, max - min + 1 ) > 0 ) // NEGATIVE CONTROL: a planted read defect\n";

    public static void main( String[] args ) throws IOException
    {
        if ( args.length != 2 && !( args.length == 3 && ( args[2].equals( "read" ) || args[2].equals( "roundtrip" ) ) ) )
        {
            System.err.println( "usage: java serialize.fuzz.Sabotage <library source dir> <scratch source dir> [roundtrip|read]" );
            System.exit( 2 );
        }
        final boolean read = args.length == 3 && args[2].equals( "read" );
        final String file = read ? READ_FILE : FILE;
        final String needle = read ? READ_NEEDLE : NEEDLE;
        final String replacement = read ? READ_REPLACEMENT : REPLACEMENT;
        Path from = Paths.get( args[0] );
        Path to = Paths.get( args[1] );
        Files.createDirectories( to );

        int patched = 0;
        try ( DirectoryStream<Path> sources = Files.newDirectoryStream( from, "*.java" ) )
        {
            for ( Path source : sources )
            {
                String text = new String( Files.readAllBytes( source ), StandardCharsets.UTF_8 );
                if ( source.getFileName().toString().equals( file ) )
                {
                    int first = text.indexOf( needle );
                    if ( first < 0 || text.indexOf( needle, first + 1 ) >= 0 )
                    {
                        System.err.println( "Sabotage: the needle must match exactly once in " + file + "; update it, never delete the control" );
                        System.exit( 1 );
                    }
                    text = text.replace( needle, replacement );
                    patched++;
                }
                Files.write( to.resolve( source.getFileName() ), text.getBytes( StandardCharsets.UTF_8 ) );
            }
        }
        if ( patched != 1 )
        {
            System.err.println( "Sabotage: " + file + " not found in " + from );
            System.exit( 1 );
        }
        System.out.println( "Sabotage: planted the " + ( read ? "read defect" : "asymmetry" ) + " in " + to.resolve( file ) );
    }
}
