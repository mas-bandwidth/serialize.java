# serialize.java

A bitpacking serialization library for **Java**. Part of the serialize
family, wire compatible with the
[C++](https://github.com/mas-bandwidth/serialize),
[C](https://github.com/mas-bandwidth/serialize.c),
[Go](https://github.com/mas-bandwidth/serialize.go),
[C#](https://github.com/mas-bandwidth/serialize.cs),
[Rust](https://github.com/mas-bandwidth/serialize.rs),
[JavaScript](https://github.com/mas-bandwidth/serialize.js),
[Dart](https://github.com/mas-bandwidth/serialize.dart) and
[Elixir](https://github.com/mas-bandwidth/serialize.elixir) libraries —
the same values produce the same bytes in every implementation, so a
stream written by one reads in any other.
[STANDARD.md](https://github.com/mas-bandwidth/serialize/blob/main/STANDARD.md)
in the C++ reference is the authority on every byte.

## The surface

One package, `serialize`, zero dependencies. The complete family
operation set on three streams — `WriteStream`, `ReadStream` and
`MeasureStream` — sharing the `BitStream` interface, so a single
serialize function writes, reads and measures. Values travel in
primitive-specialized holder cells (`IntRef`, `LongRef`, `BoolRef`,
`FloatRef`, `DoubleRef`) that keep the hot path free of boxing, and in
`Ref<T>` for object values (`String`, the 128-bit pair).
[USAGE.md](USAGE.md) teaches every operation by example.

- **Raw bits**: `serializeBits` (1–32), `serializeBits64` (1–64),
  `serializeAlign`.
- **Ranged integers**: `serializeInt`, `serializeInt64`,
  `serializeInt128` — offset from min in exactly the bit length of the
  range, unsigned-domain arithmetic so ranges wider than 2^63/2^127 are
  exact, zero bits for a degenerate range.
- **Unsigned helpers and bool**: `serializeUint8` / `16` / `32` / `64`,
  `serializeUint128` (the `UInt128Value` pair), `serializeBool`.
- **Floats**: `serializeFloat` and `serializeDouble`, bit transparent
  both ways — every pattern legal, NaN payloads ride byte-for-byte;
  `serializeCompressedFloat`, quantizing in float32 with the standard's
  two roundings on each side.
- **Bytes and strings**: `serializeBytes` (aligned bulk copy, count
  agreed, not transmitted); `serializeString` (UTF-8 on the wire, payload
  validated on read in every mode); `serializeWideString` (one 32-bit
  group per UTF-16 code unit, no alignment anywhere).
- **The relative integer**: `serializeIntRelative` — the flag ladder for
  strictly increasing uint32 sequences, one bit for a difference of 1.
- **Fixed point**: `serializeFixed` at 8/16/32/64-bit storage and
  `serializeFixed128` at 128-bit storage — Q formats, the raw scaled
  integer as an exact ranged offset, byte identical to `serializeInt64`
  wherever storage fits 64 bits.
- **128-bit values**: `Int128Value` / `UInt128Value`, two's complement
  pairs of long halves, mirroring the family's emulated pair types.
- **Utilities**: `SerializeUtil` — `bitsRequired` / `64` / `128`, zig-zag
  conversion, the compressed-float step count.
- **The bitpacker underneath**: `BitWriter` and `BitReader`, the family
  wire in branchless 64-bit window loads. Every stream and both
  bitpackers expose `reset(...)` for allocation-free reuse.

## Quick example

```java
import serialize.*;

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

byte[] buffer = new byte[64];                        // length a multiple of 8
WriteStream writer = new WriteStream( buffer, buffer.length );
serializePlayer( writer, player );                   // -> true
writer.flush();                                      // always flush before touching the bytes
int packetBytes = (int) writer.getBytesProcessed();  // 5 bytes: 7 + 1 + 32 bits

ReadStream reader = new ReadStream( buffer, packetBytes );
serializePlayer( reader, decoded );                  // -> true
```

## Toolchain

The JDK is pinned per project, not taken from the system:
[tending/PINS.md](tending/PINS.md) records the exact version, download
URL and SHA-256. `dist/` is gitignored — re-fetch by the pinned URL,
verify the hash, and unpack so the JDK sits at
`dist/jdk-21.0.12.1/Contents/Home`. The library targets the Java 17
language level (`javac --release 17`), built and tested on the pinned
JDK 21. A plain Makefile drives everything — no Maven, no Gradle:

```
make test    # build the library and tests, run the suite with -ea
make bench   # build and run the benchmark, default JVM flags, no -ea
```

## Testing

`make test` runs the suite with assertions enabled (`-ea`): writer
contracts are `assert` statements, so the tested shape is the checked
shape, and a plain `java` invocation without `-ea` is the release shape
— asserts compile to nothing at runtime, matching the C++ library's
`serialize_assert` under `NDEBUG`. The suite pins the family's golden
vectors byte for byte — the golden wire message covering every operation
class, the discriminating compressed-float vectors (bit patterns, not
tolerances), the string and wide-string pins, every relative-integer
tier, and the fixed point shapes at every group count — plus a sabotage
sweep proving every consumed bit of the golden stream is load bearing,
refusal proofs for hostile input, and the measure bound.

## Benchmark

```
make bench                      # the rows, human readable
make bench BENCH_ARGS=--csv     # the same numbers as CSV: row,op,units,value
```

[bench/serialize/bench/Bench.java](bench/serialize/bench/Bench.java) is
an operation-for-operation mirror of the family benchmark (serialize.c's
`bench.c`, itself a mirror of the C++ `bench.cpp`): the raw bitpacker,
the representative stream packet through write, read and measure, and
three packet shapes, at the same iteration counts with the same
LCG-driven inputs and best-of-five-trials discipline. Every row is
golden gated before any row is timed — the exact buffers the loops write
are verified byte for byte against pins produced by the C reference's
own bench data paths, and a bench that fails its goldens reports
nothing. JVM discipline is by hand, zero dependencies: per-shape loop
methods so type profiles stay monomorphic, warmup trials to full C2
compilation before the timed trials, and a published sink so no loop can
be proven unobservable. `BENCH_BITPACKER_PASSES` and
`BENCH_STREAM_PACKETS` scale the loops for linearity checks.

The timed run uses default JVM flags and no `-ea`, so the number
reported is the number a user gets. Current numbers at the family scale
(4096 bitpacker passes, 1,000,000 packets per stream row), measured on a
MacBook Air (Apple Silicon) — only numbers from a quiet machine are
meaningful, and only as ratios between family legs measured back to back
on the same machine:

```
bitpacker write:    2049.4 MB/s
bitpacker read:     1674.5 MB/s
stream write:       3024.1 MB/s  (64.7 M packets/s)
stream read:        2828.0 MB/s  (60.5 M packets/s)
stream measure:               17531.3 M packets/s

int packet   (runtime):       write:   63.7 M packets/s   read:   57.2 M packets/s
bits packet  (runtime):       write:   71.7 M packets/s   read:   69.7 M packets/s
mixed packet (runtime):       write:   52.6 M packets/s   read:   54.6 M packets/s
```

(The measure row prices packets without touching memory; that it is
nearly free is the property worth tracking, not the magnitude.)

## License

[BSD 3-Clause](LICENSE), © Más Bandwidth LLC.
