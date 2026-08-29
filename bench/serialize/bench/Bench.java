// serialize.java benchmark.
//
// A deliberate operation-for-operation mirror of serialize.c's bench.c --
// itself a mirror of the C++ library's bench.cpp -- so the outputs of all the
// family's benchmarks can be read side by side. Same operations in the same
// order, same iteration counts, same buffer sizes, same LCG-driven input
// data, same best-of-five-trials discipline, same reporting format and units.
//
//   make bench                          the rows, human readable
//   make bench BENCH_ARGS=--csv         the same numbers as CSV: row,op,units,value
//
// The timed run uses default JVM flags and no -ea: asserts compile to nothing
// the number a user gets is the number reported. Iteration counts are
// overridable so the harness can be run at a different scale and the timings
// checked for linearity -- a benchmark whose loop has been optimized away
// does not scale with its iteration count:
//
//   BENCH_BITPACKER_PASSES=256 BENCH_STREAM_PACKETS=100000 make bench
//
// JVM discipline, by hand (no JMH -- zero dependencies): every timed loop
// lives in its own method so the measured code is a normally-compiled method
// body, not an on-stack-replaced interpreter frame; warmup trials (default 2,
// BENCH_WARMUP_TRIALS overrides) run each leg to full C2 compilation before
// the five timed trials; and every loop's work drains into a static sink the
// bench publishes at exit, so no loop can be proven unobservable.
//
// GOLDEN GATED: before any row is timed, the exact buffers its loops write
// are verified byte for byte against pins produced by serialize.c's own bench
// data paths (the same pins the JavaScript and Dart benches carry). The LCG
// is the C bench's uint64 LCG, direct in Java's long: variant 0 diverges on
// any error in a single step, and variant 63 diverges on any error anywhere
// in 64 chained steps, in any field, in any serialize operation. The read leg
// then decodes every variant buffer and verifies every field. A bench that
// fails its goldens reports nothing.
//
// WHAT IS DELIBERATELY NOT MIRRORED
//
// bench.cpp's compile time rows (its template parameter surface) have no
// counterpart in Java, the same omission serialize.c makes. And where bench.c
// writes separate write/read/measure functions per shape -- C has no other
// way -- this bench writes ONE serialize function per shape and runs it
// against all three streams: the family's defining pattern, and exactly what
// generated Java looks like.
//
// Only numbers from a quiet machine are meaningful, and only as ratios
// between family legs measured back to back on the same machine.

package serialize.bench;

import serialize.BitReader;
import serialize.BitStream;
import serialize.BitWriter;
import serialize.BoolRef;
import serialize.FloatRef;
import serialize.IntRef;
import serialize.LongRef;
import serialize.MeasureStream;
import serialize.ReadStream;
import serialize.WriteStream;

import java.util.ArrayList;
import java.util.List;

public final class Bench
{
    /* ----------------------------------------------------------------------
       harness
       ---------------------------------------------------------------------- */

    static final int NUM_TRIALS = 5;
    static final int NUM_VARIANTS = 64;

    static int envInt( String name, int fallback )
    {
        String raw = System.getenv( name );
        if ( raw == null )
        {
            return fallback;
        }
        int value;
        try
        {
            value = Integer.parseInt( raw );
        }
        catch ( NumberFormatException e )
        {
            value = 0;
        }
        if ( value < 1 )
        {
            System.err.println( name + " must be a positive integer" );
            System.exit( 1 );
        }
        return value;
    }

    static final int BITPACKER_BUFFER_SIZE = 64 * 1024;
    static final int BITPACKER_NUM_PASSES = envInt( "BENCH_BITPACKER_PASSES", 4096 );
    static final int STREAM_NUM_PACKETS = envInt( "BENCH_STREAM_PACKETS", 1000000 );
    static final int WARMUP_TRIALS = envInt( "BENCH_WARMUP_TRIALS", 2 );

    static boolean csv = false;

    static double now()
    {
        return System.nanoTime() * 1e-9;
    }

    // the g_sink of the C bench: computed values flow here, and the bench
    // publishes it at exit under an env var the JIT cannot rule out, so no
    // loop's work can be proven unobservable
    static long sink = 0;

    record Result( String row, String op, String units, double value ) {}

    static final List<Result> results = new ArrayList<>();

    static void report( String row, String op, String units, double value )
    {
        results.add( new Result( row, op, units, value ) );
    }

    static void print( String line )
    {
        if ( !csv )
        {
            System.out.print( line );
        }
    }

    static String toHex( byte[] data, int offset, int bytes )
    {
        StringBuilder hex = new StringBuilder( bytes * 2 );
        for ( int i = 0; i < bytes; i++ )
        {
            hex.append( String.format( "%02x", data[offset + i] & 0xFF ) );
        }
        return hex.toString();
    }

    static void gateFail( String row, String what, String expected, String got )
    {
        System.err.println( "GOLDEN GATE FAILED: " + row + " " + what );
        System.err.println( "  expected " + expected );
        System.err.println( "  got      " + got );
        System.err.println( "reporting nothing." );
        System.exit( 1 );
    }

    /* ----------------------------------------------------------------------
       the C bench's uint64 LCG, direct: Java's long is 64 bits and wraps
       two's complement, which IS arithmetic mod 2^64
       ---------------------------------------------------------------------- */

    static final long LCG_MUL = 0x5851F42D4C957F2DL;
    static final long LCG_ADD = 0x14057B7EF767814FL;

    static long rng = 1;

    static void lcgSeed()
    {
        rng = 1;
    }

    static void lcgStep()
    {
        rng = rng * LCG_MUL + LCG_ADD;
    }

    // the low 32 bits of (rng >> s), for s in [0,63]
    static int shr( int s )
    {
        return (int) ( rng >>> s );
    }

    /* ----------------------------------------------------------------------
       pins

       Produced by serialize.c's own bench data paths (its static vary and
       write functions, run to the letter): the bitpacker's pass buffer, and
       for each packet shape the first and last of the 64 variant buffers the
       read leg decodes. Byte identical across the family, so these gate this
       bench's wire against the C reference, not against itself.
       ---------------------------------------------------------------------- */

    static final int PIN_BITPACKER_BYTES_PER_PASS = 65518;
    static final String PIN_BITPACKER_FIRST_64 =
        "e5e6dd7856e4a656da4c1f909b173ac12a3e2f56da3c5011ce0b72f32e37efc6" +
        "b32237b5d266fa80dcbcd00956f179b1d2e6818a705e909b77b979379e15b9a9";
    static final String PIN_BITPACKER_LAST_8 = "cd0315e1bc20376f";

    record Pin( int bytesPerPacket, String variant0, String variant63 ) {}

    static final Pin PIN_STREAM = new Pin( 49,
        "44fd43634d97ff0006d03f0000f0850000a08001809085f800fa8758dfaed800ac1f3e5d7c9bbad9f81736557493b2d1f0",
        "7e6bc3e30c348874a5bb360182748e0000a080018090858274d786d778b6ae016b1f3e5d7c9bbad9f81736557493b2d1f0" );

    static final Pin PIN_INT = new Pin( 14,
        "44fd43634df7f390f5be45153d1b",
        "7e6bc3e30c14d7508df23ce1b915" );

    static final Pin PIN_BITS = new Pin( 20,
        "fc073080fe51ff10eb5bdfec8acd07d03fc4fa06",
        "41a42bddb5f1daf01acf7866eb1aa4bb36bcc603" );

    static final Pin PIN_GEN = new Pin( 21,
        "00fdfd43ac6f7cf0430cb1c6fa1ec007d03fc4fa66",
        "ba6b6bc36b3c416ac30bafbdc69116a4bb36bcc6d3" );

    /* ----------------------------------------------------------------------
       bitpacker

       The raw bit packer with mixed widths: 227 bits per group of 16 writes,
       repeated until fewer than 256 bits remain in a 64KB buffer. The pass
       buffer carries 8 bytes of slack past its size: the BitReader allocation
       contract (the reader loads 64-bit windows at byte granularity).
       ---------------------------------------------------------------------- */

    static final int NUM_WIDTHS = 16;
    static final int[] BITPACKER_WIDTHS = { 1, 32, 7, 13, 3, 25, 8, 19, 4, 28, 11, 16, 2, 30, 6, 22 };
    static final int[] bitpackerValues = new int[NUM_WIDTHS];

    static void initBitpackerValues()
    {
        for ( int i = 0; i < NUM_WIDTHS; i++ )
        {
            int width = BITPACKER_WIDTHS[i];
            int mask = ( width == 32 ) ? -1 : ( 1 << width ) - 1;
            bitpackerValues[i] = ( 0x9e3779b9 * ( i + 1 ) ) & mask;
        }
    }

    record GatedBitpacker( byte[] buffer, int bytesPerPass ) {}

    // One pass, held byte for byte against the C reference, then read back in
    // full. Returns the written pass buffer and its size for the timing loops.
    static GatedBitpacker gateBitpacker()
    {
        byte[] buffer = new byte[BITPACKER_BUFFER_SIZE + 8];    // +8: the reader's slack contract
        BitWriter writer = new BitWriter( buffer, BITPACKER_BUFFER_SIZE );
        BitReader reader = new BitReader( buffer, BITPACKER_BUFFER_SIZE );

        while ( writer.getBitsAvailable() >= 256 )
        {
            for ( int i = 0; i < NUM_WIDTHS; i++ )
            {
                writer.writeBits( bitpackerValues[i], BITPACKER_WIDTHS[i] );
            }
        }
        writer.flushBits();
        int bytesPerPass = (int) writer.getBytesWritten();
        if ( bytesPerPass != PIN_BITPACKER_BYTES_PER_PASS )
        {
            gateFail( "bitpacker", "bytes per pass", String.valueOf( PIN_BITPACKER_BYTES_PER_PASS ), String.valueOf( bytesPerPass ) );
        }
        String first64 = toHex( buffer, 0, 64 );
        if ( !first64.equals( PIN_BITPACKER_FIRST_64 ) )
        {
            gateFail( "bitpacker", "first 64 bytes", PIN_BITPACKER_FIRST_64, first64 );
        }
        String last8 = toHex( buffer, bytesPerPass - 8, 8 );
        if ( !last8.equals( PIN_BITPACKER_LAST_8 ) )
        {
            gateFail( "bitpacker", "last 8 bytes", PIN_BITPACKER_LAST_8, last8 );
        }
        reader.reset( buffer, BITPACKER_BUFFER_SIZE );
        while ( reader.getBitsRemaining() >= 256 )
        {
            for ( int i = 0; i < NUM_WIDTHS; i++ )
            {
                int value = reader.readBits( BITPACKER_WIDTHS[i] );
                if ( value != bitpackerValues[i] )
                {
                    gateFail( "bitpacker", "read back", String.valueOf( bitpackerValues[i] ), String.valueOf( value ) );
                }
            }
        }

        return new GatedBitpacker( buffer, bytesPerPass );
    }

    // the timed loops, each in its own method: the measured body is a
    // normally-compiled method, never an on-stack-replaced frame

    static double timeBitpackerWrite( BitWriter writer, byte[] buffer )
    {
        double start = now();
        for ( int pass = 0; pass < BITPACKER_NUM_PASSES; pass++ )
        {
            writer.reset( buffer, BITPACKER_BUFFER_SIZE );
            while ( writer.getBitsAvailable() >= 256 )
            {
                for ( int i = 0; i < NUM_WIDTHS; i++ )
                {
                    writer.writeBits( bitpackerValues[i], BITPACKER_WIDTHS[i] );
                }
            }
            writer.flushBits();
            sink += writer.getBytesWritten();
        }
        return now() - start;
    }

    static double timeBitpackerRead( BitReader reader, byte[] buffer )
    {
        double start = now();
        for ( int pass = 0; pass < BITPACKER_NUM_PASSES; pass++ )
        {
            reader.reset( buffer, BITPACKER_BUFFER_SIZE );
            long sum = 0;
            while ( reader.getBitsRemaining() >= 256 )
            {
                for ( int i = 0; i < NUM_WIDTHS; i++ )
                {
                    sum += reader.readBits( BITPACKER_WIDTHS[i] );
                }
            }
            sink += sum;
        }
        return now() - start;
    }

    static void benchBitpacker( GatedBitpacker gated )
    {
        byte[] buffer = gated.buffer();
        BitWriter writer = new BitWriter( buffer, BITPACKER_BUFFER_SIZE );
        BitReader reader = new BitReader( buffer, BITPACKER_BUFFER_SIZE );

        double bestWrite = Double.POSITIVE_INFINITY;
        double bestRead = Double.POSITIVE_INFINITY;

        for ( int trial = 0; trial < WARMUP_TRIALS + NUM_TRIALS; trial++ )
        {
            double elapsed = timeBitpackerWrite( writer, buffer );
            if ( trial >= WARMUP_TRIALS && elapsed < bestWrite )
            {
                bestWrite = elapsed;
            }

            elapsed = timeBitpackerRead( reader, buffer );
            if ( trial >= WARMUP_TRIALS && elapsed < bestRead )
            {
                bestRead = elapsed;
            }
        }

        double totalMB = (double) gated.bytesPerPass() * BITPACKER_NUM_PASSES / ( 1024 * 1024 );
        report( "bitpacker", "write", "MB/s", totalMB / bestWrite );
        report( "bitpacker", "read", "MB/s", totalMB / bestRead );
        print( String.format( "bitpacker write:  %8.1f MB/s%n", totalMB / bestWrite ) );
        print( String.format( "bitpacker read:   %8.1f MB/s%n", totalMB / bestRead ) );
    }

    /* ----------------------------------------------------------------------
       packet shapes

       Each shape is one serialize function run against all three streams, a
       vary function that drives most fields from the serially dependent LCG
       the optimizer cannot fold, and a field-equality check for the read
       gate. Packets are Ref-holder objects created once and mutated in
       place: the loops allocate nothing of their own, so what the timings
       show is the library. The stream's uint64 field is a plain long --
       Java has real 64-bit integers, so there is no wide edge to pay.
       ---------------------------------------------------------------------- */

    interface ShapeInit<P>      { void run( P packet ); }
    interface ShapeVary<P>      { void run( P packet ); }
    interface ShapeSerialize<P> { boolean run( BitStream stream, P packet ); }
    interface ShapeCheck<P>     { boolean run( P expected, P decoded ); }
    interface ShapeSink<P>      { long run( P decoded ); }

    // shape: the representative stream packet (ints, bits, bool, floats, uint64, bytes)

    static final class BenchPacket
    {
        final IntRef a = new IntRef();
        final IntRef b = new IntRef();
        final IntRef c = new IntRef();
        final IntRef bits7 = new IntRef();
        final IntRef bits13 = new IntRef();
        final IntRef bits23 = new IntRef();
        final BoolRef flag = new BoolRef();
        final FloatRef x = new FloatRef();
        final FloatRef y = new FloatRef();
        final FloatRef z = new FloatRef();
        final LongRef big = new LongRef();
        final byte[] blob = new byte[17];
    }

    static void initBenchPacket( BenchPacket p )
    {
        p.a.value = -37;
        p.b.value = 12345;
        p.c.value = 987654;
        p.bits7.value = 97;
        p.bits13.value = 5000;
        p.bits23.value = 1234567;
        p.flag.value = true;
        p.x.value = 1.5f;
        p.y.value = -3.25f;
        p.z.value = 100.125f;
        p.big.value = 0x123456789abcdef0L;
        for ( int i = 0; i < 17; i++ )
        {
            p.blob[i] = (byte) ( ( i * 31 ) & 0xff );
        }
    }

    static void varyBenchPacket( BenchPacket p )
    {
        lcgStep();
        p.a.value = ( shr( 8 ) & 63 ) - 32;
        p.b.value = shr( 16 ) & 65535;
        p.c.value = ( shr( 24 ) & 0xfffff ) - 500000;
        p.bits7.value = shr( 0 ) & 127;
        p.bits13.value = shr( 3 ) & 8191;
        p.bits23.value = shr( 5 ) & 8388607;
        p.flag.value = ( rng & 1 ) != 0;
        p.x.value = (float) ( rng & 0xffff );       // exact in float32
        p.big.value = rng;                          // the full 64 bits, direct
        p.blob[0] = (byte) ( shr( 32 ) & 0xff );
    }

    static boolean serializeBenchPacket( BitStream stream, BenchPacket p )
    {
        return stream.serializeInt( p.a, -100, 100 )
            && stream.serializeInt( p.b, 0, 65535 )
            && stream.serializeInt( p.c, -1000000, 1000000 )
            && stream.serializeBits( p.bits7, 7 )
            && stream.serializeBits( p.bits13, 13 )
            && stream.serializeBits( p.bits23, 23 )
            && stream.serializeBool( p.flag )
            && stream.serializeFloat( p.x )
            && stream.serializeFloat( p.y )
            && stream.serializeFloat( p.z )
            && stream.serializeUint64( p.big )
            && stream.serializeBytes( p.blob, 17 );
    }

    static boolean checkBenchPacket( BenchPacket expected, BenchPacket decoded )
    {
        if ( expected.a.value != decoded.a.value
            || expected.b.value != decoded.b.value
            || expected.c.value != decoded.c.value
            || expected.bits7.value != decoded.bits7.value
            || expected.bits13.value != decoded.bits13.value
            || expected.bits23.value != decoded.bits23.value
            || expected.flag.value != decoded.flag.value
            || expected.x.value != decoded.x.value
            || expected.y.value != decoded.y.value
            || expected.z.value != decoded.z.value
            || expected.big.value != decoded.big.value )
        {
            return false;
        }
        for ( int i = 0; i < 17; i++ )
        {
            if ( expected.blob[i] != decoded.blob[i] )
            {
                return false;
            }
        }
        return true;
    }

    // shape 1: a realistic packet of ten bounded ints

    static final class IntFields
    {
        final IntRef f0 = new IntRef();
        final IntRef f1 = new IntRef();
        final IntRef f2 = new IntRef();
        final IntRef f3 = new IntRef();
        final IntRef f4 = new IntRef();
        final IntRef f5 = new IntRef();
        final IntRef f6 = new IntRef();
        final IntRef f7 = new IntRef();
        final IntRef f8 = new IntRef();
        final IntRef f9 = new IntRef();
    }

    static void varyIntFields( IntFields f )
    {
        lcgStep();
        f.f0.value = ( shr( 8 ) & 63 ) - 32;
        f.f1.value = shr( 16 ) & 65535;
        f.f2.value = ( shr( 24 ) & 0xfffff ) - 500000;
        f.f3.value = shr( 2 ) & 3;
        f.f4.value = ( shr( 11 ) & 15 ) - 8;
        f.f5.value = shr( 22 ) & 511;
        f.f6.value = ( shr( 33 ) & 2047 ) - 1024;
        f.f7.value = shr( 40 ) & 255;
        f.f8.value = ( shr( 30 ) & 0xfffff ) - 500000;
        f.f9.value = shr( 57 ) & 63;
    }

    static boolean serializeIntFields( BitStream stream, IntFields f )
    {
        return stream.serializeInt( f.f0, -100, 100 )
            && stream.serializeInt( f.f1, 0, 65535 )
            && stream.serializeInt( f.f2, -1000000, 1000000 )
            && stream.serializeInt( f.f3, 0, 3 )
            && stream.serializeInt( f.f4, -15, 15 )
            && stream.serializeInt( f.f5, 0, 1000 )
            && stream.serializeInt( f.f6, -2048, 2047 )
            && stream.serializeInt( f.f7, 0, 255 )
            && stream.serializeInt( f.f8, -600000, 600000 )
            && stream.serializeInt( f.f9, 0, 100 );
    }

    static boolean checkIntFields( IntFields expected, IntFields decoded )
    {
        return expected.f0.value == decoded.f0.value
            && expected.f1.value == decoded.f1.value
            && expected.f2.value == decoded.f2.value
            && expected.f3.value == decoded.f3.value
            && expected.f4.value == decoded.f4.value
            && expected.f5.value == decoded.f5.value
            && expected.f6.value == decoded.f6.value
            && expected.f7.value == decoded.f7.value
            && expected.f8.value == decoded.f8.value
            && expected.f9.value == decoded.f9.value;
    }

    // shape 2: mixed bit widths including one wider than 32 bits. The 48-bit
    // field travels as its low dword then the 16-bit remainder in two lanes --
    // STANDARD.md's splitting rule, mirrored from the family benches.

    static final class BitsFields
    {
        final IntRef b7 = new IntRef();
        final IntRef b13 = new IntRef();
        final IntRef b23 = new IntRef();
        final IntRef b3 = new IntRef();
        final IntRef b32 = new IntRef();
        final IntRef b11 = new IntRef();
        final IntRef b19 = new IntRef();
        final IntRef b48lo = new IntRef();
        final IntRef b48hi = new IntRef();
    }

    static void varyBitsFields( BitsFields f )
    {
        lcgStep();
        f.b7.value = shr( 0 ) & 127;
        f.b13.value = shr( 3 ) & 8191;
        f.b23.value = shr( 5 ) & 8388607;
        f.b3.value = shr( 29 ) & 7;
        f.b32.value = shr( 16 );
        f.b11.value = shr( 37 ) & 2047;
        f.b19.value = shr( 44 ) & 524287;
        f.b48lo.value = shr( 0 );
        f.b48hi.value = shr( 32 ) & 0xffff;
    }

    static boolean serializeBitsFields( BitStream stream, BitsFields f )
    {
        return stream.serializeBits( f.b7, 7 )
            && stream.serializeBits( f.b13, 13 )
            && stream.serializeBits( f.b23, 23 )
            && stream.serializeBits( f.b3, 3 )
            && stream.serializeBits( f.b32, 32 )
            && stream.serializeBits( f.b11, 11 )
            && stream.serializeBits( f.b19, 19 )
            && stream.serializeBits( f.b48lo, 32 )
            && stream.serializeBits( f.b48hi, 16 );
    }

    static boolean checkBitsFields( BitsFields expected, BitsFields decoded )
    {
        return expected.b7.value == decoded.b7.value
            && expected.b13.value == decoded.b13.value
            && expected.b23.value == decoded.b23.value
            && expected.b3.value == decoded.b3.value
            && expected.b32.value == decoded.b32.value
            && expected.b11.value == decoded.b11.value
            && expected.b19.value == decoded.b19.value
            && expected.b48lo.value == decoded.b48lo.value
            && expected.b48hi.value == decoded.b48hi.value;
    }

    // shape 3: a "generated packet" mixing bounded ints, bits and bools, the
    // way schema generated code looks. The 48-bit timestamp travels in two lanes.

    static final class GenFields
    {
        final IntRef sequence = new IntRef();
        final IntRef ackBits = new IntRef();
        final IntRef entityId = new IntRef();
        final IntRef posX = new IntRef();
        final IntRef posY = new IntRef();
        final IntRef posZ = new IntRef();
        final IntRef yaw = new IntRef();
        final BoolRef moving = new BoolRef();
        final BoolRef firing = new BoolRef();
        final IntRef timestampLo = new IntRef();
        final IntRef timestampHi = new IntRef();
        final IntRef weapon = new IntRef();
    }

    static void varyGenFields( GenFields f )
    {
        lcgStep();
        f.sequence.value = shr( 8 ) & 65535;
        f.ackBits.value = shr( 16 );
        f.entityId.value = shr( 0 ) & 4095;
        f.posX.value = ( shr( 20 ) & 32767 ) - 16384;
        f.posY.value = ( shr( 25 ) & 32767 ) - 16384;
        f.posZ.value = ( shr( 30 ) & 32767 ) - 16384;
        f.yaw.value = shr( 3 ) & 511;
        f.moving.value = ( rng & 1 ) != 0;
        f.firing.value = ( rng & 2 ) != 0;
        f.timestampLo.value = shr( 0 );
        f.timestampHi.value = shr( 32 ) & 0xffff;
        f.weapon.value = shr( 60 ) & 15;
    }

    static boolean serializeGenFields( BitStream stream, GenFields f )
    {
        return stream.serializeInt( f.sequence, 0, 65535 )
            && stream.serializeBits( f.ackBits, 32 )
            && stream.serializeBits( f.entityId, 12 )
            && stream.serializeInt( f.posX, -16384, 16383 )
            && stream.serializeInt( f.posY, -16384, 16383 )
            && stream.serializeInt( f.posZ, -16384, 16383 )
            && stream.serializeBits( f.yaw, 9 )
            && stream.serializeBool( f.moving )
            && stream.serializeBool( f.firing )
            && stream.serializeBits( f.timestampLo, 32 )
            && stream.serializeBits( f.timestampHi, 16 )
            && stream.serializeInt( f.weapon, 0, 15 );
    }

    static boolean checkGenFields( GenFields expected, GenFields decoded )
    {
        return expected.sequence.value == decoded.sequence.value
            && expected.ackBits.value == decoded.ackBits.value
            && expected.entityId.value == decoded.entityId.value
            && expected.posX.value == decoded.posX.value
            && expected.posY.value == decoded.posY.value
            && expected.posZ.value == decoded.posZ.value
            && expected.yaw.value == decoded.yaw.value
            && expected.moving.value == decoded.moving.value
            && expected.firing.value == decoded.firing.value
            && expected.timestampLo.value == decoded.timestampLo.value
            && expected.timestampHi.value == decoded.timestampHi.value
            && expected.weapon.value == decoded.weapon.value;
    }

    /* ----------------------------------------------------------------------
       variant generation and the golden gate, shared by every packet shape
       ---------------------------------------------------------------------- */

    static final int VARIANT_BUFFER_SIZE = 256;     // holds every shape, with the reader's 8 bytes of slack to spare

    record GatedShape<P>( P packet, P decoded, byte[][] variants, int bytesPerPacket ) {}

    // Pre-writes the 64 variant buffers the read leg decodes, using the same
    // LCG sequence as the write loop, then holds the wire against the C pins
    // and decodes every variant back, verifying every field. Returns the
    // variant buffers and the constant per-packet byte size.
    static <P> GatedShape<P> gateShape( String row, P packet, P decoded, ShapeInit<P> init, ShapeVary<P> vary,
                                        ShapeSerialize<P> serialize, ShapeCheck<P> check, Pin pin )
    {
        init.run( packet );
        lcgSeed();
        byte[][] variants = new byte[NUM_VARIANTS][];
        int bytesPerPacket = 0;
        WriteStream writer = new WriteStream( new byte[VARIANT_BUFFER_SIZE], VARIANT_BUFFER_SIZE );
        for ( int k = 0; k < NUM_VARIANTS; k++ )
        {
            byte[] buffer = new byte[VARIANT_BUFFER_SIZE];
            vary.run( packet );
            writer.reset( buffer, VARIANT_BUFFER_SIZE );
            if ( !serialize.run( writer, packet ) )
            {
                gateFail( row, "variant write", "ok", "refused" );
            }
            writer.flush();
            bytesPerPacket = (int) writer.getBytesProcessed();
            variants[k] = buffer;
        }

        if ( bytesPerPacket != pin.bytesPerPacket() )
        {
            gateFail( row, "bytes per packet", String.valueOf( pin.bytesPerPacket() ), String.valueOf( bytesPerPacket ) );
        }
        String hex0 = toHex( variants[0], 0, bytesPerPacket );
        if ( !hex0.equals( pin.variant0() ) )
        {
            gateFail( row, "variant 0 wire", pin.variant0(), hex0 );
        }
        String hex63 = toHex( variants[63], 0, bytesPerPacket );
        if ( !hex63.equals( pin.variant63() ) )
        {
            gateFail( row, "variant 63 wire", pin.variant63(), hex63 );
        }

        // decode every variant and verify every field against a replay of the LCG
        init.run( packet );
        lcgSeed();
        ReadStream reader = new ReadStream( variants[0], bytesPerPacket );
        for ( int k = 0; k < NUM_VARIANTS; k++ )
        {
            vary.run( packet );
            reader.reset( variants[k], bytesPerPacket );
            if ( !serialize.run( reader, decoded ) )
            {
                gateFail( row, "variant " + k + " decode", "ok", "refused" );
            }
            if ( !check.run( packet, decoded ) )
            {
                gateFail( row, "variant " + k + " fields", "writer values", "different values" );
            }
        }

        return new GatedShape<>( packet, decoded, variants, bytesPerPacket );
    }

    /* ----------------------------------------------------------------------
       the timed legs, shared by the stream row and the shape rows: each in
       its own method, best-of-five taken by the caller after warmup
       ---------------------------------------------------------------------- */

    static <P> double timeShapeWrite( WriteStream writer, byte[] buffer, P packet, ShapeVary<P> vary,
                                      ShapeSerialize<P> serialize )
    {
        double start = now();
        for ( int i = 0; i < STREAM_NUM_PACKETS; i++ )
        {
            vary.run( packet );
            writer.reset( buffer, VARIANT_BUFFER_SIZE );
            if ( !serialize.run( writer, packet ) )
            {
                System.exit( 1 );
            }
            writer.flush();
            sink += writer.getBytesProcessed();
        }
        return now() - start;
    }

    static <P> double timeShapeRead( ReadStream reader, byte[][] variants, int bytesPerPacket, P decoded,
                                     ShapeSerialize<P> serialize, ShapeSink<P> sinkOf )
    {
        double start = now();
        for ( int i = 0; i < STREAM_NUM_PACKETS; i++ )
        {
            reader.reset( variants[i & ( NUM_VARIANTS - 1 )], bytesPerPacket );
            if ( !serialize.run( reader, decoded ) )
            {
                System.exit( 1 );
            }
            sink += sinkOf.run( decoded );
        }
        return now() - start;
    }

    static <P> double timeShapeMeasure( MeasureStream measure, P packet, ShapeVary<P> vary,
                                        ShapeSerialize<P> serialize )
    {
        double start = now();
        for ( int i = 0; i < STREAM_NUM_PACKETS; i++ )
        {
            vary.run( packet );
            measure.reset();
            if ( !serialize.run( measure, packet ) )
            {
                System.exit( 1 );
            }
            sink += measure.getBitsProcessed();
        }
        return now() - start;
    }

    /* ----------------------------------------------------------------------
       stream

       The representative packet through all three streams: MB/s and
       M packets/s for write and read, M packets/s for measure.
       ---------------------------------------------------------------------- */

    static GatedShape<BenchPacket> gateStream()
    {
        GatedShape<BenchPacket> gated = gateShape( "stream", new BenchPacket(), new BenchPacket(),
            Bench::initBenchPacket, Bench::varyBenchPacket, Bench::serializeBenchPacket, Bench::checkBenchPacket,
            PIN_STREAM );

        // measure gate: the measured bits equal the written bits for this packet
        MeasureStream measure = new MeasureStream();
        boolean ok = serializeBenchPacket( measure, gated.packet() );
        if ( !ok || measure.getBytesProcessed() != gated.bytesPerPacket() )
        {
            gateFail( "stream", "measure bytes", String.valueOf( gated.bytesPerPacket() ),
                      String.valueOf( measure.getBytesProcessed() ) );
        }

        return gated;
    }

    static void benchStream( GatedShape<BenchPacket> gated )
    {
        BenchPacket packet = gated.packet();
        BenchPacket decoded = gated.decoded();
        byte[][] variants = gated.variants();
        byte[] buffer = new byte[VARIANT_BUFFER_SIZE];
        WriteStream writer = new WriteStream( buffer, VARIANT_BUFFER_SIZE );
        ReadStream reader = new ReadStream( variants[0], gated.bytesPerPacket() );
        MeasureStream measure = new MeasureStream();

        double bestWrite = Double.POSITIVE_INFINITY;
        double bestRead = Double.POSITIVE_INFINITY;
        double bestMeasure = Double.POSITIVE_INFINITY;

        for ( int trial = 0; trial < WARMUP_TRIALS + NUM_TRIALS; trial++ )
        {
            initBenchPacket( packet );
            lcgSeed();

            double elapsed = timeShapeWrite( writer, buffer, packet, Bench::varyBenchPacket, Bench::serializeBenchPacket );
            if ( trial >= WARMUP_TRIALS && elapsed < bestWrite )
            {
                bestWrite = elapsed;
            }

            elapsed = timeShapeRead( reader, variants, gated.bytesPerPacket(), decoded, Bench::serializeBenchPacket,
                                     (BenchPacket d) -> d.b.value );
            if ( trial >= WARMUP_TRIALS && elapsed < bestRead )
            {
                bestRead = elapsed;
            }

            // measure prices the packet without touching memory; that it is
            // nearly free is the property worth tracking. The vary call stays
            // so the loop is the loop the other family benches time.
            elapsed = timeShapeMeasure( measure, packet, Bench::varyBenchPacket, Bench::serializeBenchPacket );
            if ( trial >= WARMUP_TRIALS && elapsed < bestMeasure )
            {
                bestMeasure = elapsed;
            }
        }

        double totalMB = (double) gated.bytesPerPacket() * STREAM_NUM_PACKETS / ( 1024 * 1024 );
        double packets = STREAM_NUM_PACKETS / 1000000.0;

        report( "stream", "write", "MB/s", totalMB / bestWrite );
        report( "stream", "write", "Mpackets/s", packets / bestWrite );
        report( "stream", "read", "MB/s", totalMB / bestRead );
        report( "stream", "read", "Mpackets/s", packets / bestRead );
        report( "stream", "measure", "Mpackets/s", packets / bestMeasure );
        print( String.format( "stream write:     %8.1f MB/s  (%.1f M packets/s)%n", totalMB / bestWrite, packets / bestWrite ) );
        print( String.format( "stream read:      %8.1f MB/s  (%.1f M packets/s)%n", totalMB / bestRead, packets / bestRead ) );
        print( String.format( "stream measure:   %19.1f M packets/s%n", packets / bestMeasure ) );
    }

    /* ----------------------------------------------------------------------
       packet shapes: write and read, M packets/s
       ---------------------------------------------------------------------- */

    static <P> void benchShape( String row, String label, GatedShape<P> gated, ShapeInit<P> init, ShapeVary<P> vary,
                                ShapeSerialize<P> serialize, ShapeSink<P> sinkOf )
    {
        P packet = gated.packet();
        P decoded = gated.decoded();
        byte[][] variants = gated.variants();
        byte[] buffer = new byte[VARIANT_BUFFER_SIZE];
        WriteStream writer = new WriteStream( buffer, VARIANT_BUFFER_SIZE );
        ReadStream reader = new ReadStream( variants[0], gated.bytesPerPacket() );

        double bestWrite = Double.POSITIVE_INFINITY;
        double bestRead = Double.POSITIVE_INFINITY;

        for ( int trial = 0; trial < WARMUP_TRIALS + NUM_TRIALS; trial++ )
        {
            init.run( packet );
            lcgSeed();

            double elapsed = timeShapeWrite( writer, buffer, packet, vary, serialize );
            if ( trial >= WARMUP_TRIALS && elapsed < bestWrite )
            {
                bestWrite = elapsed;
            }

            elapsed = timeShapeRead( reader, variants, gated.bytesPerPacket(), decoded, serialize, sinkOf );
            if ( trial >= WARMUP_TRIALS && elapsed < bestRead )
            {
                bestRead = elapsed;
            }
        }

        double packets = STREAM_NUM_PACKETS / 1000000.0;
        report( row, "write", "Mpackets/s", packets / bestWrite );
        report( row, "read", "Mpackets/s", packets / bestRead );
        print( String.format( "%s  write: %6.1f M packets/s   read: %6.1f M packets/s%n",
                              label, packets / bestWrite, packets / bestRead ) );
    }

    /* ---------------------------------------------------------------------- */

    public static void main( String[] args )
    {
        for ( String arg : args )
        {
            if ( arg.equals( "--csv" ) )
            {
                csv = true;
            }
        }
        initBitpackerValues();

        // every row's golden gate runs before any row is timed: a bench that
        // fails its goldens reports nothing at all
        GatedBitpacker gatedBitpacker = gateBitpacker();
        GatedShape<BenchPacket> gatedStream = gateStream();
        GatedShape<IntFields> gatedInt = gateShape( "int_packet", new IntFields(), new IntFields(),
            (IntFields f) -> {}, Bench::varyIntFields, Bench::serializeIntFields, Bench::checkIntFields, PIN_INT );
        GatedShape<BitsFields> gatedBits = gateShape( "bits_packet", new BitsFields(), new BitsFields(),
            (BitsFields f) -> {}, Bench::varyBitsFields, Bench::serializeBitsFields, Bench::checkBitsFields, PIN_BITS );
        GatedShape<GenFields> gatedGen = gateShape( "mixed_packet", new GenFields(), new GenFields(),
            (GenFields f) -> {}, Bench::varyGenFields, Bench::serializeGenFields, Bench::checkGenFields, PIN_GEN );

        print( "\n[serialize.java benchmark]\n\n" );

        benchBitpacker( gatedBitpacker );

        benchStream( gatedStream );

        print( "\n" );

        benchShape( "int_packet", "int packet   (runtime):     ", gatedInt,
            (IntFields f) -> {}, Bench::varyIntFields, Bench::serializeIntFields, (IntFields d) -> d.f0.value );
        benchShape( "bits_packet", "bits packet  (runtime):     ", gatedBits,
            (BitsFields f) -> {}, Bench::varyBitsFields, Bench::serializeBitsFields, (BitsFields d) -> d.b7.value );
        benchShape( "mixed_packet", "mixed packet (runtime):     ", gatedGen,
            (GenFields f) -> {}, Bench::varyGenFields, Bench::serializeGenFields, (GenFields d) -> d.sequence.value );

        print( "\n(the C++ bench also prints a compile time row per shape. that surface is\n" );
        print( " C++ template machinery with no counterpart here, the same omission the\n" );
        print( " C bench makes.)\n" );
        print( "\n" );

        if ( csv )
        {
            StringBuilder out = new StringBuilder( "row,op,units,value\n" );
            for ( Result r : results )
            {
                out.append( String.format( "%s,%s,%s,%.4f%n", r.row(), r.op(), r.units(), r.value() ) );
            }
            System.out.print( out );
        }

        // the g_sink escape: the JIT cannot prove the env var absent, so the
        // accumulated sink is observable and no loop's work can be deleted
        if ( System.getenv( "SERIALIZE_BENCH_SINK" ) != null )
        {
            System.err.println( "sink: " + sink );
        }
    }
}
