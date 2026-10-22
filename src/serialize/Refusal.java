package serialize;

/**
 * Why a read was refused: the closed list STANDARD.md defines under "Refusal
 * reasons". Every implementation in the family spells these names
 * identically, and every refused conformance vector states the one it
 * expects; {@link #toString} returns that spelling.
 *
 * A {@link ReadStream} latches the FIRST refusal and keeps reporting it:
 * every later read on the failed stream fails too, but the reason is the
 * cause, not the echo. {@link #NONE} is the state of a stream that has
 * refused nothing, and is not one of the eight reasons.
 */
public enum Refusal
{
    /** The stream has not refused anything. */
    NONE( "none" ),

    /** A read needs more bits than the stream holds. */
    PAST_END( "past_end" ),

    /**
     * A decoded value outside its declared range or domain: a ranged offset
     * past its span, a string length past its buffer, a compressed integer
     * past max_integer_value, an int_relative outside its domain or not
     * increasing.
     */
    VALUE_OUT_OF_RANGE( "value_out_of_range" ),

    /** A set bit in an align's padding, or in the unused bits of the final byte at the end. */
    NONZERO_PADDING( "nonzero_padding" ),

    /** A whole byte or more after the end of the object. */
    TRAILING_DATA( "trailing_data" ),

    /** A float or double that is NaN or an infinity, or a compressed_float decode that is not finite. */
    NON_FINITE_FLOAT( "non_finite_float" ),

    /** A string payload that is not well-formed UTF-8. */
    INVALID_UTF8( "invalid_utf8" ),

    /** A zero byte among a string's transmitted bytes. */
    EMBEDDED_NUL( "embedded_nul" ),

    /**
     * An encoding a conforming writer never produces for the value it
     * carries: the int_relative absolute tier carrying a bounded difference.
     */
    NON_CANONICAL( "non_canonical" );

    private final String spelling;

    Refusal( String spelling )
    {
        this.spelling = spelling;
    }

    /** The family wide name, exactly as STANDARD.md and the corpus spell it: "past_end", for example. */
    @Override
    public String toString()
    {
        return spelling;
    }

    /**
     * The reason a family spelling names, or null if the closed list does not
     * hold it. "none" is not a reason and returns null.
     */
    public static Refusal fromSpelling( String spelling )
    {
        for ( Refusal refusal : values() )
        {
            if ( refusal != NONE && refusal.spelling.equals( spelling ) )
            {
                return refusal;
            }
        }
        return null;
    }
}
