# Using serialize.java

Everything the library does, by example. The wire format itself is
defined by [STANDARD.md](STANDARD.md) — a verbatim vendored copy of the
specification in
[mas-bandwidth/serialize](https://github.com/mas-bandwidth/serialize),
which CI checks for drift; this document teaches the Java surface that
speaks it.

```java
import serialize.*;
```

## One serialize function, three streams

The family's defining pattern: write, read and measure share a single
serialize function through the `BitStream` interface. Every operation
returns a bool, values travel in holder cells — primitive-specialized
`IntRef`, `LongRef`, `BoolRef`, `FloatRef` and `DoubleRef` (Java has no
ref parameters, and a specialized cell keeps the hot path free of
boxing), plus `Ref<T>` for `String` and the 128-bit pair — and the
stream direction decides whether the holder is consumed or filled.

```java
final class Player
{
    final IntRef health = new IntRef();
    final BoolRef alive = new BoolRef();
    final FloatRef heading = new FloatRef();
}

static boolean serializePlayer( BitStream stream, Player player )
{
    return stream.serializeInt( player.health, 0, 100 )
        && stream.serializeBool( player.alive )
        && stream.serializeFloat( player.heading );
}

// write
byte[] buffer = new byte[64];                      // length a multiple of 8
WriteStream writer = new WriteStream( buffer, buffer.length );
serializePlayer( writer, player );                 // -> true
writer.flush();                                    // REQUIRED: the buffer is unspecified until flush
int packetBytes = (int) writer.getBytesProcessed(); // 5 bytes: 7 + 1 + 32 bits

// read
ReadStream reader = new ReadStream( buffer, packetBytes );
Player decoded = new Player();
boolean ok = serializePlayer( reader, decoded )    // -> true
          && reader.serializeEnd();                // the packet ends where its object does
// decoded.health.value == 87

// measure
MeasureStream measure = new MeasureStream();
serializePlayer( measure, player );                // -> true
// measure.getBitsProcessed() == 40
```

`WriteStream` needs a buffer whose size is a multiple of 8 (the writer
stores qwords to memory; asserted under `-ea`, U1); the packet to send is the first
`getBytesProcessed()` bytes. **Flush is required**: the buffer's contents are
unspecified until `flush()` is called after the last operation (U11). `MeasureStream` touches no memory at all —
it prices a message so you can size buffers; its bound is conservative
(see [Measuring](#measuring)).

**The read allocation contract.** The byte array handed to `ReadStream`
(or `BitReader`) may be **any size of at least the data being read**:
an array sized exactly to the packet is correct. The reader loads 8-byte
windows at byte granularity; with **at least 8 bytes of slack** past the
data every window loads straight from the array, which is the fast path.
With less slack, `reset` (and the constructor) copies the final bytes of
the data into a 16-byte zero-padded tail window, and reads near the end
load from it instead. Either way the bytes past the end are loaded but
never interpreted — they can never influence a decoded value or an
accept/reject decision. Every read pays one well-predicted branch to
choose the array or the tail, slack or not: unlike the C++ reference,
which requires the slack and loads unconditionally, this port accepts a
tight array at that per-read cost (whether to require the slack instead
is open in [ROADMAP.md](ROADMAP.md), pending a measurement). Reading a
packet out of the buffer it was written into, as above, has the slack
for free; a packet arriving from a socket lands in an array 8 bytes
longer than the largest packet to keep it.

A byte count outside the array (negative, or past its length) is the
caller's misuse: the stream refuses it at construction or `reset` as
`value_out_of_range`, and every read on it refuses (IMPLEMENTING.md U7).

All three streams expose `getBitsProcessed()`, `getBytesProcessed()`,
`isWriting()` / `isReading()`, `getAlignBits()`, and `reset(...)` for
allocation-free reuse — point a stream at a buffer again and all state
clears, no new stream object needed.

## Writes trust, reads validate

The check model is the family standard's ("Writes assume trusted data"):
**the caller is responsible for well-formed writes**, and reads validate
everything, because the wire is a trust boundary.

On the read side, every failure — a truncated read, a value outside its
range, nonzero alignment padding, a non-finite float, a malformed string,
bytes after the end — returns `false`, and hostile bytes never throw.

**A reader never traps, on the caller's misuse either** (IMPLEMENTING.md
U7, U16). A declaration that cannot be right is refused as
`value_out_of_range` before any bits move, in every build, with or
without `-ea`: a bit count outside `[1,32]` (`[1,64]` for `serializeBits64`),
`min > max` on a ranged read, a string `bufferSize` below 1, a `bytes`
count larger than the destination array (or a `null` one), an
`int_relative` `previous` below 0, a fixed point declaration that does not
fit its Q format, a compressed float declaration that is not conforming,
and precomputed compressed float constants that cannot be right. The
failure state is checked first, so a stream that already refused keeps its
first reason. On a write or a measure the same declarations are asserted
under `-ea`, the one write-misuse mechanism of this port (U9).

**`fail( reason )`** is public on every stream (U14). On a read stream it
latches the reason through the same latch as every refusal (the first
wins), and `null` or `Refusal.NONE` record `rejected`, never none. On a
write or measure stream it records nothing, never asserts, and returns
`false`; a measure refuses nothing (U6). `SerializeUtil.refusalName( reason )`
is the refusal-name function: the family spelling, and `"none"` for no
refusal. (`Enum.name()` returns the Java constant name, `PAST_END`, and is
not the family spelling.)

**Every refusal has a reason.** `getRefusal()` names it from the
standard's closed list, as a `Refusal` whose `toString()` is the family
spelling: `past_end`, `value_out_of_range`, `nonzero_padding`,
`trailing_data`, `non_finite_float`, `invalid_utf8`, `embedded_nul`,
`non_canonical`, `rejected`. The reason is the **first** refusal's: the later
refusals of a failed stream are its echoes. A stream that has refused
nothing reports `Refusal.NONE`, and a refused stream never does.

**The caller can refuse too.** `rejected` is the caller's own refusal of
a value whose bytes were well formed: `stream.reject()` latches it on a
read stream and returns `false`, so a serialize function can
`return stream.reject();`. A nested object whose serialize function
returns `false` with nothing refused inside it latches `rejected` as
well; if a read inside it refused first, that reason stands. Reject is
read only: on a write or measure stream it is caller error, asserted in
the checked build.

**A failed read is terminal.** Nothing after the failing operation has a
defined position, so `ReadStream` latches: the first refusal sets the
failure flag, and every later read on that stream returns `false`
without consuming a bit or writing a destination — a zero-bit read
included. `isFailed()` reports the latch and `getRefusal()` its reason;
`reset(...)` points the stream at data again and clears both, and nothing
else does.

**A refused read leaves its destination unwritten.** When a read of a
scalar fails, the holder cell holds exactly what it held before the
call, so a caller that trusts the cell over the return code cannot
proceed on a value the stream never carried. Two limits: a read into a
caller-owned buffer — `serializeBytes`, `serializeString` — leaves that
buffer's **contents unspecified**
after a refusal, and a composite read may leave earlier members written,
because it is a sequence of primitive reads and each one carries the
rule alone.

```java
byte[] data = new byte[16];                        // 1 byte of data + the 8-byte slack
ReadStream r = new ReadStream( data, 1 );          // 8 bits of data
r.serializeBits( v, 32 );                          // -> false: past the end
r.serializeBits( v, 8 );                           // -> false: the stream has failed, v untouched
r.isFailed();                                      // -> true
r.getRefusal();                                    // -> Refusal.PAST_END, the first reason

r.reset( data, 1 );                                // point at data again, the latch cleared
r.serializeBits( v, 8 );                           // -> true

// an offset smuggled into the bit headroom of a range is refused
ReadStream r2 = new ReadStream( hostile, 1 );      // 8 bits carrying 255
r2.serializeInt( ranged, 0, 200 );                 // -> false: 255 > 200, value_out_of_range
```

One rule follows from clean refusal: check the result of any serialized
value that controls a loop before the loop uses it, or a truncated
packet spins the loop on garbage.

On the write side, `WriteStream` and `MeasureStream` operations always
return `true` — caller contracts (bit counts in range, values within
their declared ranges, min ≤ max, well-formed string payloads, buffers
that fit) are `assert` statements, the Java form of the family's debug
asserts:

- **Checked** (`java -ea`, how `make test` runs): contract violations
  throw `AssertionError` at the call site. Develop and test here.
- **Release** (a plain `java` invocation — assertions are disabled by
  default): asserts compile to nothing at runtime, exactly as a C++
  release build compiles `serialize_assert` to nothing. The caller is
  trusted; misuse produces garbage on the wire (which conforming readers
  refuse), never memory unsafety — the JVM's own bounds checks backstop
  the trusted path.

The wire for conforming writes is byte identical in both modes, and so
is every refusal: the read side's obligations are checks, never asserts.
`make test-release` runs the whole suite in the release shape to prove
it.

## Raw bits

`serializeBits` moves the low `bits` of an `IntRef`, 1 to 32.
`serializeBits64` is its `LongRef` twin, 1 to 64 bits. Values wider than
32 bits go low 32-bit dword first — the family's group rule.

```java
WriteStream w = new WriteStream( buffer, buffer.length );
w.serializeBits( new IntRef( 5 ), 3 );                       // 3 bits on the wire
w.serializeBits( new IntRef( 0xdeadbeef ), 32 );             // full width
w.serializeBits64( new LongRef( 0x123456789abcdef0L ), 64 ); // low 32-bit group first
w.serializeAlign();                                          // zero-pads to the byte boundary
w.flush();
```

`serializeAlign` writes zero bits up to the next byte boundary (nothing
if already aligned); the reader verifies the padding is zero and refuses
otherwise.

## Ranged integers

`serializeInt(ref, min, max)` is the format's defining operation: the
value rides as an offset from `min` in exactly
`SerializeUtil.bitsRequired(min, max)` bits. Both sides must state the
same range — the range is part of the message format, not the wire.

```java
w.serializeInt( new IntRef( -37 ), -100, 100 );   // 8 bits
w.serializeInt( new IntRef( 7 ), 7, 7 );          // degenerate range: ZERO bits
```

Reads refuse values smuggled into the bit headroom of a range (an offset
above `max - min` fails the read — reject, never clamp).

`serializeInt64` is the same operation in the long domain, with offsets
computed in unsigned arithmetic so ranges wider than 2^63 are exact.
`serializeInt128` extends it to 128 bits on the `Int128Value` pair,
written in 32-bit groups least significant first; where the range fits
64 bits the bytes are identical to `serializeInt64`. `min <= max` is the
legal relation on every ranged width: a degenerate `min == max` costs
zero bits at 128 bits exactly as it does at 32, with nothing on the
wire, nothing consumed, and the value taken from `min`.

```java
w.serializeInt64( new LongRef( -5000000000L ), -5000000000L, 5000000000L ); // 34 bits
w.serializeInt128( new Ref<>( Int128Value.fromLong( -1 ) ),
                   Int128Value.MIN_VALUE, Int128Value.MAX_VALUE );          // 128 bits
```

`SerializeUtil.bitsRequired`, `bitsRequired64` and `bitsRequired128`
price a range when designing a message format. The subtraction wraps in
the unsigned domain, so the full int32/int64 ranges and ranges wider
than 2^63/2^127 report exact widths:

```java
SerializeUtil.bitsRequired( 0, 200 );             // 8: the cost of serializeInt over [-100, +100]
SerializeUtil.bitsRequired64( 0, 5000000000L );   // 33
SerializeUtil.bitsRequired128( UInt128Value.ZERO,
    UInt128Value.fromUnsignedLong( 1 ).shiftLeft( 100 ) ); // 101
```

`SerializeUtil` also carries zig-zag conversion (`signedToUnsigned` /
`unsignedToSigned`) for callers that want small-magnitude signed values
in an unsigned encoding.

## The unsigned helpers and bool

Fixed-width conveniences. The 8/16/32-bit helpers ride `IntRef`, 64 bits
rides `LongRef`, and `serializeUint128` is always 128 bits on the
`UInt128Value` pair, low 64-bit half first. `serializeBool` is one bit.

```java
w.serializeUint8( new IntRef( 0x7f ) );
w.serializeUint16( new IntRef( 0x1234 ) );
w.serializeUint32( new IntRef( 0x12345678 ) );
w.serializeUint64( new LongRef( 0xfedcba9876543210L ) ); // unsigned, bit transparent in long
w.serializeUint128( new Ref<>( UInt128Value.fromUnsignedLong( 1 ).shiftLeft( 100 ) ) );
w.serializeBool( new BoolRef( true ) );
```

**Unsigned-long discipline**: Java has no unsigned 64-bit type, so
unsigned wire values ride bit-transparently in `long` — arithmetic wraps
two's complement, which is exactly the unsigned-domain wrap the format's
offset encodings rely on. Where *ordering* or *printing* matters, go
through the JDK's unsigned statics: `Long.compareUnsigned`,
`Long.toUnsignedString`, `Long.divideUnsigned`. The same discipline
applies to `int` for 32-bit wire values (`Integer.compareUnsigned` and
friends).

`Int128Value` / `UInt128Value` are immutable two's complement
`(hi, lo)` pairs with the arithmetic, shift and comparison operations
the serialize surface needs. `Int128Value.fromLong` sign-extends;
`UInt128Value.fromUnsignedLong` zero-extends; `toUnsigned()` /
`fromUnsigned` reinterpret bit-transparently.

## Floats and doubles: finite, bit exact

`serializeFloat` (32 bits) and `serializeDouble` (64 bits) carry
**finite** values, and reproduce every finite pattern exactly — negative
zero and the subnormals included, nothing flushed or canonicalized. NaN
with any payload and both infinities are not values the format carries:
a reader refuses one as `non_finite_float` and leaves the holder cell
untouched, and a writer handed one is a contract violation (asserted
under `-ea`). The reader tests the raw bits before any conversion, so no
float conversion on the way can quiet a signaling NaN into a value that
looks different.

```java
w.serializeFloat( new FloatRef( 3.1415926f ) );
w.serializeDouble( new DoubleRef( 1.0 / 3.0 ) );
w.serializeFloat( new FloatRef( -0.0f ) );        // -0 round trips as -0
```

## The compressed float

`serializeCompressedFloat(ref, min, max, resolution)` quantizes into a
declared range at a resolution — the one lossy operation. The
declaration is part of the message format. The arithmetic is float32
with the standard's two roundings on each side — Java's `float`
arithmetic is strict IEEE-754 with no contraction, so the wire matches
the family's discriminating vectors bit-exactly.

```java
w.serializeCompressedFloat( new FloatRef( 5.0f ), 0.0f, 10.0f, 0.01f ); // 10 bits
// reading it back yields exactly 5.0f: the value sits on a quantum.
// off-quantum values come back within the resolution; re-encoding a
// decoded value is byte-identical (the round trip is idempotent).
```

Finite values outside `[min, max]` clamp on write; writing a non-finite
value is a contract violation (asserted under `-ea`, clamped in
release); reads refuse integers smuggled above the quantization ceiling
as `value_out_of_range`. **The decode must be finite**: at the top of a
declaration that reaches `Float.MAX_VALUE` the final add can round to
infinity (`[3 * 2^103, FLT_MAX]` at resolution `2^104` does), so a
reader refuses a non-finite decode as `non_finite_float`, and a writer
asserts that its declaration's top decodes finite.
`SerializeUtil.compressedFloatMaxIntegerValue` exposes the declaration's
step count for callers pricing a format.

**The precomputed form** (U15), the port of the reference's
`serialize_compressed_float_precomputed`: `SerializeUtil.compressedFloatParams(min, max, res)`
derives the wire constants once, and
`serializeCompressedFloatPrecomputed(ref, p.maxIntegerValue, p.bits, p.delta, min)`
uses them at every call, wire identical to the unified form. A reader refuses
constants that cannot be right (`bits` not `bitsRequired(0, maxIntegerValue)`,
`maxIntegerValue` outside `[1, 4294967040]`, `delta` not positive and finite)
as `value_out_of_range`; a writer asserts them.

```java
CompressedFloatParams p = SerializeUtil.compressedFloatParams( 0.0f, 10.0f, 0.01f );
w.serializeCompressedFloatPrecomputed( new FloatRef( 5.0f ), p.maxIntegerValue, p.bits, p.delta, 0.0f ); // 10 bits
```

## Raw bytes

`serializeBytes(data, bytes)` aligns to the byte boundary (the alignment
is part of the format, padding verified on read) and then bulk-copies
`bytes` bytes. The count is never transmitted: both sides agree by
passing the same count. On read, the array you pass is filled in place.

```java
byte[] blob = new byte[] { (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef };
w.serializeBytes( blob, blob.length );
// ...
byte[] out = new byte[4];
r.serializeBytes( out, out.length );              // out now holds the bytes
```

A zero-length write still performs (and verifies) the align.

## Strings: UTF-8 on the wire

`serializeString(ref, bufferSize)` sends the UTF-8 byte length as
`serializeInt(length, 0, bufferSize - 1)`, then the payload as
`serializeBytes` (which aligns). `bufferSize` is part of the message
format — the same string against different buffer sizes produces
different bytes — and the payload must fit `bufferSize - 1` bytes.

```java
w.serializeString( new Ref<>( "golden" ), 16 );
Ref<String> s = new Ref<>();
r.serializeString( s, 16 );                       // s.value.equals( "golden" )
```

Reads validate the payload in every mode. A zero byte anywhere in the
payload is refused as `embedded_nul`, checked over the whole payload
first; then malformed UTF-8 (overlongs, surrogate code points, values
above U+10FFFF, truncated sequences, stray continuations, bytes that
never appear in UTF-8) is refused as `invalid_utf8`. An accepted payload
is decoded strictly — a `CodingErrorAction.REPORT` decoder, never one
that substitutes U+FFFD — and real text round trips byte for byte: no
normalization, no stripped byte order mark, noncharacters and every
plane kept. A Java string carrying an unpaired surrogate or a NUL is a
writer contract violation (asserted under `-ea`): the UTF-8 encoder would
replace the surrogate, so it cannot reach the wire faithfully.

## The end of a packet

`serializeEnd()` closes the object. A writer's end emits nothing and a
measure's costs nothing; a reader's end requires the stream to end where
the object does. **A reader of a packet reads the end after the last
field of the packet's top-level object**, with the stream holding
exactly the bytes the writer reported — without it, trailing bytes and
padding go unexamined, and one value would have more than one byte
sequence.

```java
ReadStream r = new ReadStream( packet, packetBytes );
boolean ok = serializePlayer( r, player ) && r.serializeEnd();
```

The end refuses a whole byte or more left over as `trailing_data`, even
a zero byte, and that is checked first; then it reads the final byte's
unused bits and refuses any set one as `nonzero_padding`. An accepted end
leaves the stream exactly consumed, so any read after it is past the
end. On a failed stream the end refuses with the first reason, like any
other read.

## The relative integer

`serializeIntRelative(previous, ref)` prices strictly increasing
sequences — sequence numbers, ack chains. **The domain is `0` to
`2^31 - 1` inclusive**, and both `previous` and `current` lie in it.
`current > previous` always, no wrapping: a caller with a wrapping
counter unwraps it before serializing. A difference of 1 costs a single
bit; small differences ride payload tiers of 5/8/13/18/23 bits; past the
ladder, six zero flags carry `current` itself as 32 raw bits. The writer
takes the first tier that fits, and that is the only encoding.

The reader reconstructs `current` in a width that cannot wrap, in every
tier, and refuses the read unless the result lies in the domain and
strictly exceeds `previous` — the absolute tier's 32 raw bits are
unsigned, so a value with the top bit set is outside the domain and is
refused, as `value_out_of_range`. Then the absolute tier is held to the
same canonical rule: carrying a `current` no more than 69914 above
`previous`, a difference a bounded tier would have taken, it is refused
as `non_canonical`. A refused read is terminal and leaves the holder
cell untouched.

```java
w.serializeIntRelative( 100, new IntRef( 101 ) );  // 1 bit
w.serializeIntRelative( 100, new IntRef( 2100 ) ); // a mid-ladder tier
// read side: pass the same previous, get current back
r.serializeIntRelative( 100, seq );                // seq.value == 101
```

`previous` is caller state, not wire: both sides already know it, and it
never arrives off the wire. Writing `current <= previous`, or a
`previous` outside the domain, is a contract violation, asserted under
`-ea`.

## Fixed point

`serializeFixed(ref, integerBits, fractionBits, minUnits, maxUnits)`
carries Q-format fixed point. `ref.value` is the **raw scaled integer**
— the real value times `2^fractionBits` — in a `LongRef`, with storage
of exactly `integerBits + fractionBits` bits (8, 16, 32 or 64; the sign
bit counts toward `integerBits`). The bounds are in **whole units**,
part of the message format. `serializeFixed128` is the 128-bit storage
counterpart: `integerBits + fractionBits` must equal 128, and the value
is a `Ref<Int128Value>`.

```java
// -3.25 in Q8.8 over [-100, +100] whole units: raw is -3.25 * 256 = -832
w.serializeFixed( new LongRef( -832 ), 8, 8, -100, 100 );                    // 16 bits

// 1234.5 in Q16.16 over [-2000, +2000]
w.serializeFixed( new LongRef( 1234L * 65536 + 32768 ), 16, 16, -2000, 2000 );

// 12345.5 in Q48.16 over [-100000, +100000]: 64-bit storage
w.serializeFixed( new LongRef( 12345L * 65536 + 32768 ), 48, 16, -100000, 100000 ); // 34 bits

// Q64.64 over [-1000, +1000] whole units: 128-bit storage
Ref<Int128Value> q6464 = new Ref<>( Int128Value.fromLong( 1 ).shiftLeft( 64 ) ); // exactly 1.0
w.serializeFixed128( q6464, 64, 64, -1000, 1000 );
```

The wire is the offset from `min << fractionBits` in exactly the bit
length of the raw range — byte identical to `serializeInt64` of the raw
value wherever storage fits 64 bits — and the round trip is **exact**:
no quantization, unlike the compressed float. A degenerate `min == max`
range costs zero bits on every storage width. Signedness never reaches
the wire: for the same bounds, signed and unsigned storage produce
identical bytes. Reads refuse raw values smuggled into the bit headroom;
an invalid declaration is caller misuse, asserted under `-ea`.

## Nesting: composition, not an encoding

`serializeObject(serializer)` runs a nested object's own serialize
function inline. It contributes **no bytes of its own** — no framing, no
length prefix and no alignment is inserted around it — so the nested
operations land at exactly the position the call sits at, and nesting a
run of fields changes nothing about the bytes.

`Serializer` is a one-method interface, so a message type implements it
directly and a small nested field can be a lambda:

```java
final class Transform implements Serializer
{
    final FloatRef x = new FloatRef();
    final FloatRef y = new FloatRef();

    @Override public boolean serialize( BitStream stream )
    {
        return stream.serializeFloat( x ) && stream.serializeFloat( y );
    }
}

// the same three fields, nested and flat, produce the same bytes
stream.serializeInt( health, 0, 100 ) &&
stream.serializeObject( transform );

stream.serializeInt( health, 0, 100 ) &&
stream.serializeFloat( transform.x ) && stream.serializeFloat( transform.y );
```

A refusal inside the nesting propagates out of it, and a read stream
consults its failure state first, so a nested object on a stream that has
already failed refuses without invoking the object at all.

## Measuring

`MeasureStream` prices a message without a buffer. For everything except
alignment it is exact; any operation that aligns (`serializeAlign`,
`serializeBytes`, `serializeString`) charges the worst case — 7 bits of
padding — because the measure stream cannot know what alignment the
field will land on inside your message. The guarantee is a bound, never
equality:

```java
measure.getBitsProcessed() >= writer.getBitsProcessed(); // always true
```

Size buffers from the measured bound (rounded up to a multiple of 8
bytes for `WriteStream`).

## The bitpacker underneath

`BitWriter` and `BitReader` are the streams' engine — the family wire in
branchless 64-bit window loads — and are public for code that wants raw
bitpacking without the serialize surface or its checks:

```java
BitWriter bw = new BitWriter( buffer, buffer.length );
bw.writeBits( 5, 3 );
bw.writeAlign();
bw.writeBytes( new byte[] { 1, 2, 3 }, 3 );
bw.flushBits();

BitReader br = new BitReader( buffer, (int) bw.getBytesWritten() );
br.readBits( 3 );                                 // 5
br.readAlign();                                   // true: padding was zero
byte[] out = new byte[3];
br.readBytes( out, 3 );                           // out is now { 1, 2, 3 }
```

The reader carries the same allocation contract as `ReadStream`: any
array of at least the data, 8 bytes of slack past it for the fast path.
`BitReader` and `BitWriter` are the raw engine: their contracts are
asserted under `-ea` only, and a misused one may throw. The refusals of
U7 belong to `ReadStream`.

## Wire compatibility

The same values produce the same bytes in every family implementation.
This is not aspiration but pinned fact: the test suite runs every vector
in [`conformance/`](conformance) — the family's shared corpus, vendored
from mas-bandwidth/serialize and checked for drift by CI — and carries
the family's golden vectors: the golden wire message covering every
operation class byte for byte, the discriminating float vectors, the
string pins, every relative-integer tier, and the fixed
point shapes at every group count, all minted from the canonical C++
reference's own output. If your message serializes with
the same declarations on both ends, a stream written by any family
implementation reads in any other.

Two doctrines worth knowing at the edges:

- **Trailing bits**: writers zero the unused bits of the final byte; a
  field read never looks at them, and a reader's end refuses a set one
  as `nonzero_padding`, and a whole byte more as `trailing_data`.
- **Past-end data**: the slack bytes past the end of the data are loaded
  but never interpreted — they cannot influence a decoded value or an
  accept/reject decision.
