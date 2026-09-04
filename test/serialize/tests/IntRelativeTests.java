package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/** The relative-integer ladder: every tier boundary, the fallback, and its refusals. */
final class IntRelativeTests
{
    private IntRelativeTests() {}

    static void run()
    {
        test( "intRelative: every tier boundary round trips at its pinned bit cost", () -> {
            // { difference, expected bits on the wire }
            int[][] cases = {
                { 1, 1 },                   // the one-bit tier
                { 2, 2 + 3 },               // 2–6: two flags + 3 bits
                { 6, 2 + 3 },
                { 7, 3 + 5 },               // 7–23: three flags + 5 bits
                { 23, 3 + 5 },
                { 24, 4 + 9 },              // 24–280: four flags + 9 bits
                { 280, 4 + 9 },
                { 281, 5 + 13 },            // 281–4377: five flags + 13 bits
                { 2000, 5 + 13 },           // the golden battery's twelve-bit bucket
                { 4377, 5 + 13 },
                { 4378, 6 + 17 },           // 4378–69914: six flags + 17 bits
                { 69914, 6 + 17 },
                { 69915, 6 + 32 },          // the fallback: six flags + current as raw 32
                { 100000, 6 + 32 },
            };
            for ( int[] c : cases )
            {
                int previous = 100;
                int current = previous + c[0];

                byte[] buffer = new byte[16];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeIntRelative( previous, new IntRef( current ) ) );
                checkEqual( writer.getBitsProcessed(), c[1], "bit cost of difference " + c[0] );
                writer.flush();

                MeasureStream measure = new MeasureStream();
                check( measure.serializeIntRelative( previous, new IntRef( current ) ) );
                checkEqual( measure.getBitsProcessed(), c[1], "measure agrees for difference " + c[0] );

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                IntRef readBack = new IntRef();
                check( reader.serializeIntRelative( previous, readBack ) );
                checkEqual( readBack.value, current, "round trip difference " + c[0] );
            }
        } );

        test( "intRelative: the fallback rejects current <= previous", () -> {
            byte[] buffer = new byte[8 + 8];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 0 ), 6 ) );            // six false flags
            check( writer.serializeBits( new IntRef( 50 ), 32 ) );          // current = 50 against previous = 100
            writer.flush();

            ReadStream reader = new ReadStream( buffer, 8 );
            IntRef current = new IntRef();
            check( !reader.serializeIntRelative( 100, current ), "50 after 100 refused" );
        } );

        test( "intRelative: the whole domain travels, from zero to the top", () -> {
            // { previous, current } — the widest gap the domain allows, and its top edge
            int[][] cases = {
                { 0, Integer.MAX_VALUE },                                   // the absolute tier, end to end
                { Integer.MAX_VALUE - 1, Integer.MAX_VALUE },               // the one-bit tier at the top
                { Integer.MAX_VALUE - 6, Integer.MAX_VALUE },               // a bounded tier at the top
            };
            for ( int[] c : cases )
            {
                byte[] buffer = new byte[8 + 8];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeIntRelative( c[0], new IntRef( c[1] ) ) );
                writer.flush();

                MeasureStream measure = new MeasureStream();
                check( measure.serializeIntRelative( c[0], new IntRef( c[1] ) ) );
                checkEqual( measure.getBitsProcessed(), writer.getBitsProcessed(), "measure agrees" );

                ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
                IntRef current = new IntRef();
                check( reader.serializeIntRelative( c[0], current ) );
                checkEqual( current.value, c[1], "round trip " + c[0] + " -> " + c[1] );
            }
        } );

        test( "intRelative: a reconstruction past the domain is refused in every tier", () -> {
            // the same bytes decode one step lower: the refusal is on the
            // reconstructed value, not on the byte pattern
            int[] differences = { 1, 5, 20, 200, 3000, 50000 };
            for ( int difference : differences )
            {
                byte[] buffer = new byte[8 + 8];
                WriteStream writer = new WriteStream( buffer, 8 );
                check( writer.serializeIntRelative( 10, new IntRef( 10 + difference ) ) );
                writer.flush();
                int bytes = (int) writer.getBytesProcessed();

                int overflowing = Integer.MAX_VALUE - difference + 1;       // reconstructs one past the domain
                ReadStream reader = new ReadStream( buffer, bytes );
                IntRef current = new IntRef( 0x5E5E5E5E );
                check( !reader.serializeIntRelative( overflowing, current ), "difference " + difference + " past the domain refused" );
                checkEqual( current.value, 0x5E5E5E5E, "the refusal wrote nothing" );

                ReadStream inside = new ReadStream( buffer, bytes );
                IntRef accepted = new IntRef();
                check( inside.serializeIntRelative( overflowing - 1, accepted ), "difference " + difference + " one step inside accepted" );
                checkEqual( accepted.value, Integer.MAX_VALUE, "the twin decodes to the domain top" );
            }
        } );

        test( "intRelative: the absolute tier reads its 32 raw bits unsigned", () -> {
            // a top-bit-set absolute value is outside the domain, whatever previous is
            byte[] buffer = new byte[8 + 8];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 0 ), 6 ) );            // six false flags
            check( writer.serializeBits( new IntRef( 0x80000000 ), 32 ) );  // 2^31, one past the domain
            writer.flush();

            ReadStream reader = new ReadStream( buffer, (int) writer.getBytesProcessed() );
            IntRef current = new IntRef( 0x5E5E5E5E );
            check( !reader.serializeIntRelative( 100, current ), "top bit set refused" );
            checkEqual( current.value, 0x5E5E5E5E, "the refusal wrote nothing" );
        } );

        test( "intRelative: a doctored tier payload out of range refuses", () -> {
            byte[] buffer = new byte[8 + 8];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 0b10 ), 2 ) );         // flags: not one-bit, then the 2–6 tier
            check( writer.serializeBits( new IntRef( 7 ), 3 ) );            // payload 7 > span of [2,6]
            writer.flush();

            ReadStream reader = new ReadStream( buffer, 8 );
            IntRef current = new IntRef();
            check( !reader.serializeIntRelative( 100, current ), "out-of-range tier payload refused" );
        } );

        test( "intRelative: truncated refuses at every stage", () -> {
            // no bits at all
            ReadStream empty = new ReadStream( new byte[8], 0 );
            check( !empty.serializeIntRelative( 100, new IntRef() ), "empty refused" );

            // six flags present, the 32-bit fallback truncated
            byte[] buffer = new byte[8 + 8];
            WriteStream writer = new WriteStream( buffer, 8 );
            check( writer.serializeBits( new IntRef( 0 ), 6 ) );
            writer.flush();
            ReadStream reader = new ReadStream( buffer, 1 );                // 8 bits: flags fit, payload does not
            check( !reader.serializeIntRelative( 100, new IntRef() ), "truncated fallback refused" );
        } );
    }
}
