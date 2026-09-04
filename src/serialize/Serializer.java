package serialize;

/**
 * An object that serializes itself against any stream: the operand of the
 * family's {@code object} operation.
 *
 * One method, so a message type implements it directly and a nested field
 * can also be a lambda or a method reference. The same body runs against a
 * {@link WriteStream}, a {@link ReadStream} and a {@link MeasureStream},
 * which is what makes composition free of a second description of the
 * message.
 *
 * @see BitStream#serializeObject(Serializer)
 */
@FunctionalInterface
public interface Serializer
{
    /**
     * Serializes this object against the stream.
     * @param stream the stream to run against.
     * @return false to refuse, which propagates out of the nesting.
     */
    boolean serialize( BitStream stream );
}
