# Using serialize.java

Everything the library does, by example. The wire format itself is
defined by the C++ reference's
[STANDARD.md](https://github.com/mas-bandwidth/serialize/blob/main/STANDARD.md);
this document teaches the Java surface that speaks it.

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
writer.flush();                                    // ALWAYS flush before touching the bytes
int packetBytes = (int) writer.getBytesProcessed(); // 5 bytes: 7 + 1 + 32 bits

// read
ReadStream reader = new ReadStream( buffer, packetBytes );
Player decoded = new Player();
serializePlayer( reader, decoded );                // -> true
// decoded.health.value == 87

// measure
MeasureStream measure = new MeasureStream();
serializePlayer( measure, player );                // -> true
// measure.getBitsProcessed() == 40
```

`WriteStream` needs a buffer whose size is a multiple of 8 (the writer
stores qwords to memory); the packet to send is the first
`getBytesProcessed()` bytes. `MeasureStream` touches no memory at all —
it prices a message so you can size buffers; its bound is conservative
(see [Measuring](#measuring)).

**The read allocation contract**, matching the C++ reference: the byte
array handed to `ReadStream` (or `BitReader`) must extend **at least 8
bytes past the end of the data being read**, because the reader loads
8-byte windows at byte granularity. The bytes past the end are loaded
but never interpreted — they can never influence a decoded value or an
accept/reject decision. Reading a packet out of the buffer it was
written into, as above, satisfies the contract for free; a packet
arriving from a socket just needs to land in an array 8 bytes longer
than the largest packet.

All three streams expose `getBitsProcessed()`, `getBytesProcessed()`,
`isWriting()` / `isReading()`, `getAlignBits()`, and `reset(...)` for
allocation-free reuse — point a stream at a buffer again and all state
clears, no new stream object needed.

## Writes trust, reads validate

The check model is the family standard's ("Writes assume trusted data"):
**the caller is responsible for well-formed writes**, and reads validate
everything, because the wire is a trust boundary.

On the read side, every failure — a truncated read, a value outside its
range, nonzero alignment padding, a malformed string — returns `false`,
and hostile bytes never throw. A failed read is terminal for the stream:
nothing after the failing operation has a defined position. `reset(...)`
points the stream at data again and clears all state.

```java
byte[] data = new byte[16];                        // 1 byte of data + the 8-byte slack
ReadStream r = new ReadStream( data, 1 );          // 8 bits of data
r.serializeBits( v, 32 );                          // -> false: past the end

r.reset( data, 1 );                                // point at data again, state cleared
r.serializeBits( v, 8 );                           // -> true

// an offset smuggled into the bit headroom of a range is refused
ReadStream r2 = new ReadStream( hostile, 1 );      // 8 bits carrying 255
r2.serializeInt( ranged, 0, 200 );                 // -> false: 255 > 200
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

The wire for conforming writes is byte identical in both modes.

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
64 bits the bytes are identical to `serializeInt64`. Its bounds must
satisfy `min < max` strictly.

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

## Floats and doubles: bit transparent

`serializeFloat` (32 bits) and `serializeDouble` (64 bits) reproduce the
transmitted pattern exactly — every pattern is legal on the wire: NaNs
with any payload, signaling NaNs, infinities, negative zero, denormals.
The library moves patterns with `floatToRawIntBits` /
`intBitsToFloat`, so nothing is quieted or canonicalized in either
direction.

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
release); reads refuse integers smuggled above the quantization ceiling.
`SerializeUtil.compressedFloatMaxIntegerValue` exposes the declaration's
step count for callers pricing a format.

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

Reads validate the payload in every mode: malformed UTF-8 (overlongs,
surrogate code points, values above U+10FFFF, truncated sequences, stray
continuations) and interior NULs fail the read. A Java string carrying
an unpaired surrogate is a writer contract violation (asserted under
`-ea`): the UTF-8 encoder would replace it, so it cannot reach the wire
faithfully.

## Wide strings: UTF-16 code units

`serializeWideString(ref, bufferSize)` sends the unit count, then one
32-bit group per UTF-16 code unit — never a code point — with no
alignment anywhere: the one place the wide path deliberately differs
from its narrow counterpart. A Java string *is* a sequence of UTF-16
code units, so astral characters are two groups on the wire, exactly as
the family's 2-byte-wchar_t ports split them. `bufferSize` counts wide
characters.

```java
w.serializeWideString( new Ref<>( "😀A" ), 8 );  // 3 code units: 99 bits
```

Unpaired surrogates in a written string are a contract violation
(asserted under `-ea` — the wide wire cannot carry ill-formed UTF-16,
because conforming readers refuse it). Reads refuse groups above 0xFFFF,
interior NUL groups, and unpaired, misordered or dangling surrogates.

## The relative integer

`serializeIntRelative(previous, ref)` prices strictly increasing
unsigned 32-bit sequences — sequence numbers, ack chains.
`current > previous` always, no wrapping. A difference of 1 costs a
single bit; small differences ride payload tiers of 5/8/13/18/23 bits;
past the ladder, six zero flags carry `current` itself as 32 raw bits,
and the reader enforces the ordering on that absolute form too.

```java
w.serializeIntRelative( 100, new IntRef( 101 ) );  // 1 bit
w.serializeIntRelative( 100, new IntRef( 2100 ) ); // a mid-ladder tier
// read side: pass the same previous, get current back
r.serializeIntRelative( 100, seq );                // seq.value == 101
```

`previous` is caller state, not wire: both sides already know it.
Writing `current <= previous` is a contract violation, asserted under
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

The reader carries the same allocation contract as `ReadStream`: 8 bytes
of slack past the data.

## Wire compatibility

The same values produce the same bytes in every family implementation.
This is not aspiration but pinned fact: the test suite carries the
family's golden vectors — including the golden wire message covering
every operation class, byte for byte — plus the discriminating float
vectors, the string and wide-string pins, every relative-integer tier,
and the fixed point shapes at every group count, all minted from the
canonical C++ reference's own output. If your message serializes with
the same declarations on both ends, a stream written by any family
implementation reads in any other.

Two doctrines worth knowing at the edges:

- **Trailing bits**: writers zero the unused bits of the final byte;
  readers never reject a stream for their contents.
- **Past-end data**: the slack bytes past the end of the data are loaded
  but never interpreted — they cannot influence a decoded value or an
  accept/reject decision.
