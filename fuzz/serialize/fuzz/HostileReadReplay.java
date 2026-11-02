package serialize.fuzz;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import serialize.ReadStream;

/** Deterministic hostile replay. Coverage counts FIRST refusals, never calls on a latched stream. */
public final class HostileReadReplay
{
    private HostileReadReplay() {}

    private static final Set<String> EXPECTED = Set.of(
        "Bits", "BitsAfterEnd", "Int", "IntFull", "IntBounded", "Int64-value64", "Int64-ranged64",
        "Fixed-fixed32", "Fixed-fixed64", "Fixed128-wide", "Int128-ranged128", "Int128-full128",
        "Bool", "Float", "Double", "CompressedFloat", "CompressedFloatPrecomputed",
        "Uint64", "Uint128-value128", "Bits64-raw", "Bytes", "String", "End-stream", "Align-stream",
        "IntRelative-previous", "Uint8-value8", "Uint16-value16", "Uint32-value32" );

    private static void replay( byte[] data, Set<String> first, Set<String> opposite )
    {
        HostileReadFuzzer.fuzzerTestOneInput( data );
        byte[] payload = Arrays.copyOfRange( data, HostileReadFuzzer.NUM_OPS, data.length );
        for ( int pass = 0; pass < 2; pass++ )
        {
            HostileReadFuzzer.Trace trace = new HostileReadFuzzer.Trace();
            HostileReadFuzzer.fuzzRead( new ReadStream( payload, payload.length ), data, trace, pass != 0 );
            if ( trace.firstRefusal != null ) ( pass == 0 ? first : opposite ).add( trace.firstRefusal );
        }
    }

    public static void main( String[] args )
    {
        Set<String> first = new HashSet<>();
        Set<String> opposite = new HashSet<>();
        int count = 0;
        // Zero payloads keep each preceding ranged field valid. Every byte truncation
        // reaches later members of grouped operations, rather than an earlier range error.
        for ( int select = 0; select < 16; select++ )
            for ( int param : new int[] { 0, 1, 15 } )
                for ( int bytes = 0; bytes <= 80; bytes++ )
                    for ( int fill : new int[] { 0, 255 } )
                    {
                        byte[] data = new byte[HostileReadFuzzer.NUM_OPS + bytes];
                        Arrays.fill( data, 0, HostileReadFuzzer.NUM_OPS, (byte) ( param * 16 + select ) );
                        Arrays.fill( data, HostileReadFuzzer.NUM_OPS, data.length, (byte) fill );
                        replay( data, first, opposite );
                        count++;
                    }
        byte[] badPadding = new byte[HostileReadFuzzer.NUM_OPS + 1];
        Arrays.fill( badPadding, (byte) 12 );
        badPadding[0] = 0; // consume one zero bit, then refuse the nonzero alignment padding
        badPadding[HostileReadFuzzer.NUM_OPS] = (byte) 254;
        replay( badPadding, first, opposite );
        count++;
        Set<String> missing = new HashSet<>( EXPECTED ); missing.removeAll( first );
        FuzzCheck.check( missing.isEmpty(), "missing first-refusal coverage for sentinel 5A: " + missing );
        missing = new HashSet<>( EXPECTED ); missing.removeAll( opposite );
        FuzzCheck.check( missing.isEmpty(), "missing first-refusal coverage for sentinel A5: " + missing );
        System.out.println( "hostile replay: " + count + " inputs, " + EXPECTED.size() + " first-refusal operations under both sentinels" );
    }
}
