package serialize.fuzz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plants a fresh-refusal destination write in a private copy of the real reader. */
public final class SentinelSabotage
{
    private SentinelSabotage() {}

    public static void main( String[] args ) throws IOException
    {
        if ( args.length != 3 ) throw new IllegalArgumentException( "source output control" );
        String method;
        String write;
        switch ( args[2] )
        {
            case "bool": method = "serializeBool"; write = "value.value = false;"; break;
            case "bits": method = "serializeBits"; write = "value.value = ( value.value & 0xFFFF ) | 0x5A5A0000;"; break;
            case "bits64": method = "serializeBits64"; write = "value.value = ( value.value & 0xFFFFFFFFL ) | 0x5A5A5A5A00000000L;"; break;
            case "float": method = "serializeFloat"; write = "value.value = Float.intBitsToFloat( 0x5A5A5A5A );"; break;
            case "double": method = "serializeDouble"; write = "value.value = Double.longBitsToDouble( 0x5A5A5A5A5A5A5A5AL );"; break;
            case "int128": method = "serializeInt128"; write = "value.value = new Int128Value( 0x5A5A5A5A5A5A5A5AL, value.value.lo );"; break;
            case "uint128": method = "serializeUint128"; write = "value.value = new UInt128Value( value.value.hi, 0x5A5A5A5A5A5A5A5AL );"; break;
            default: throw new IllegalArgumentException( "unknown control " + args[2] );
        }
        String text = Files.readString( Path.of( args[0] ), StandardCharsets.UTF_8 );
        String signature = "public boolean " + method + "(";
        int start = text.indexOf( signature );
        FuzzCheck.check( start >= 0 && text.indexOf( signature, start + 1 ) < 0, "ambiguous sabotage signature " + method );
        int end = text.indexOf( "    @Override", start + signature.length() );
        if ( end < 0 ) end = text.length();
        String body = text.substring( start, end );
        String needle = "return fail( Refusal.PAST_END );";
        int at = body.indexOf( needle );
        FuzzCheck.check( at >= 0, "missing fresh-refusal sabotage site " + method );
        // Braces preserve single-line if semantics in the wide reader.
        body = body.replace( needle, "{ " + write + " " + needle + " }" );
        Path out = Path.of( args[1] );
        Files.createDirectories( out.getParent() );
        Files.writeString( out, text.substring( 0, start ) + body + text.substring( end ), StandardCharsets.UTF_8 );
    }
}
