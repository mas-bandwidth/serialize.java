package serialize.tests;

import serialize.*;

import static serialize.tests.Harness.check;
import static serialize.tests.Harness.checkEqual;
import static serialize.tests.Harness.test;

/**
 * The end of the object, and the refusal reasons: the closed list, its
 * spelling, and the first reason latched by a failed stream.
 */
final class EndTests
{
    private EndTests() {}

    static void run()
    {
        test( "refusal reasons: the closed list, spelled as STANDARD.md spells it", () -> {
            String[] spellings = {
                "past_end", "value_out_of_range", "nonzero_padding", "trailing_data",
                "non_finite_float", "invalid_utf8", "embedded_nul", "non_canonical", "rejected",
            };
            checkEqual( Refusal.values().length, spellings.length + 1, "nine reasons and none" );
            for ( String spelling : spellings )
            {
                Refusal refusal = Refusal.fromSpelling( spelling );
                check( refusal != null, spelling + " is in the list" );
                check( refusal.toString().equals( spelling ), spelling + " spells itself" );
            }
            check( Refusal.fromSpelling( "none" ) == null, "none is not a reason" );
            check( Refusal.fromSpelling( "refused" ) == null, "a bare refusal is not a reason" );
            check( Refusal.NONE.toString().equals( "none" ), "the state of a stream that refused nothing" );
        } );

        test( "refusal reasons: the first is latched, the echoes keep it, and reset clears it", () -> {
            byte[] buffer = GoldenWire.bytes( 0x09, 0, 0, 0, 0, 0, 0, 0, 0 );
            ReadStream reader = new ReadStream( buffer, 1 );
            check( reader.getRefusal() == Refusal.NONE, "nothing refused yet" );
            check( !reader.serializeInt( new IntRef(), 0, 8 ), "offset 9 of span 8 refused" );
            check( reader.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "value_out_of_range" );
            check( !reader.serializeBits( new IntRef(), 32 ), "a read past the end on the failed stream" );
            check( !reader.serializeEnd(), "the end on the failed stream" );
            check( reader.getRefusal() == Refusal.VALUE_OUT_OF_RANGE, "the echoes keep the first reason" );

            reader.reset( buffer, 1 );
            check( reader.getRefusal() == Refusal.NONE, "reset clears the reason" );
            check( reader.serializeBits( new IntRef(), 8 ) && reader.serializeEnd(), "and the stream reads again" );
        } );

        test( "end: a writer emits nothing, a measure costs nothing, and the writer zeroes the padding", () -> {
            byte[] buffer = new byte[64];
            java.util.Arrays.fill( buffer, (byte) 0xFF );       // the zeros must come from the writer
            WriteStream writer = new WriteStream( buffer, 64 );
            check( writer.serializeBits( new IntRef( 0xDEADBEEF ), 32 ) );
            check( writer.serializeBits( new IntRef( 5 ), 3 ) );
            check( writer.serializeEnd() );
            writer.flush();
            checkEqual( writer.getBitsProcessed(), 35, "the end writes nothing" );
            checkEqual( writer.getBytesProcessed(), 5, "five bytes" );
            checkEqual( buffer[4] & 0xF8, 0, "writers must write zero" );

            MeasureStream measure = new MeasureStream();
            check( measure.serializeBits( new IntRef( 5 ), 3 ) );
            check( measure.serializeEnd() );
            checkEqual( measure.getBitsProcessed(), 3, "the end costs nothing" );
        } );

        test( "end: the exact stream ends where the object does, and the end consumes the padding", () -> {
            byte[] buffer = GoldenWire.bytes( 0xEF, 0xBE, 0xAD, 0xDE, 0x05, 0, 0, 0, 0, 0, 0, 0, 0 );
            ReadStream reader = new ReadStream( buffer, 5 );
            IntRef head = new IntRef();
            IntRef tail = new IntRef();
            check( reader.serializeBits( head, 32 ) && reader.serializeBits( tail, 3 ) );
            check( reader.serializeEnd(), "the exact stream ends" );
            checkEqual( reader.getBitsProcessed(), 40, "the end consumed the five bits of padding" );
            check( reader.getRefusal() == Refusal.NONE, "nothing refused" );

            // nothing is left: even one more bit is past the end, while a zero-bit read still succeeds
            check( reader.serializeInt( new IntRef(), 7, 7 ), "a zero-bit read after the end" );
            check( !reader.serializeBool( new BoolRef() ), "a one-bit read after the end" );
            check( reader.getRefusal() == Refusal.PAST_END, "is past_end" );
        } );

        test( "end: each padding bit alone is refused as nonzero_padding, the field bits never", () -> {
            for ( int bit = 3; bit < 8; bit++ )
            {
                byte[] buffer = GoldenWire.bytes( 0xEF, 0xBE, 0xAD, 0xDE, 0x05 | ( 1 << bit ), 0, 0, 0, 0, 0, 0, 0, 0 );
                ReadStream reader = new ReadStream( buffer, 5 );
                IntRef head = new IntRef();
                IntRef tail = new IntRef();
                check( reader.serializeBits( head, 32 ) && reader.serializeBits( tail, 3 ), "the fields never look" );
                checkEqual( tail.value, 5, "the field decodes" );
                check( !reader.serializeEnd(), "padding bit " + bit + " refused" );
                check( reader.getRefusal() == Refusal.NONZERO_PADDING, "padding bit " + bit + ": nonzero_padding" );
                IntRef after = new IntRef( 0x1234 );
                check( !reader.serializeBits( after, 1 ) && after.value == 0x1234, "the refusal is terminal" );
            }

            // every field bit set, the padding clean: accepted
            byte[] full = GoldenWire.bytes( 0x07, 0, 0, 0, 0, 0, 0, 0, 0 );
            ReadStream reader = new ReadStream( full, 1 );
            check( reader.serializeBits( new IntRef(), 3 ) && reader.serializeEnd(), "every field bit set is accepted" );
        } );

        test( "end: a whole byte after the object is trailing_data, checked before the padding", () -> {
            // the empty object: an empty stream ends at once, a zero byte does not
            check( new ReadStream( new byte[8], 0 ).serializeEnd(), "the empty stream ends" );
            ReadStream zero = new ReadStream( new byte[8], 1 );
            check( !zero.serializeEnd(), "one zero byte after the empty object" );
            check( zero.getRefusal() == Refusal.TRAILING_DATA, "is trailing_data" );

            // an aligned object refuses a byte more
            byte[] aligned = GoldenWire.bytes( 0xAB, 0x00, 0, 0, 0, 0, 0, 0, 0, 0 );
            ReadStream exact = new ReadStream( aligned, 1 );
            check( exact.serializeBits( new IntRef(), 8 ) && exact.serializeEnd(), "the aligned object ends" );
            ReadStream extra = new ReadStream( aligned, 2 );
            check( extra.serializeBits( new IntRef(), 8 ) && !extra.serializeEnd(), "a byte more is refused" );
            check( extra.getRefusal() == Refusal.TRAILING_DATA, "as trailing_data" );

            // three bits, set padding AND a byte more: trailing data is checked first
            byte[] both = GoldenWire.bytes( 0x0D, 0x00, 0, 0, 0, 0, 0, 0, 0, 0 );
            ReadStream reader = new ReadStream( both, 2 );
            check( reader.serializeBits( new IntRef(), 3 ) && !reader.serializeEnd(), "refused" );
            check( reader.getRefusal() == Refusal.TRAILING_DATA, "as trailing_data, not nonzero_padding" );
        } );
    }
}
