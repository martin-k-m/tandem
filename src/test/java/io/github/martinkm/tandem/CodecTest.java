package io.github.martinkm.tandem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CodecTest {

    @Test
    void stringCodecIsTheIdentity() {
        Codec<String> codec = Codec.ofString();
        assertEquals("hello", codec.decode(codec.encode("hello")));
        assertEquals("", codec.decode(codec.encode("")));
    }

    @Test
    void numberCodecsRoundTrip() {
        assertEquals(42, Codec.ofInt().decode(Codec.ofInt().encode(42)));
        assertEquals(-7L, Codec.ofLong().decode(Codec.ofLong().encode(-7L)));
        assertEquals(3.5, Codec.ofDouble().decode(Codec.ofDouble().encode(3.5)));
        assertEquals(Long.MAX_VALUE, Codec.ofLong().decode(Codec.ofLong().encode(Long.MAX_VALUE)));
    }

    @Test
    void booleanCodecRoundTrips() {
        assertEquals(true, Codec.ofBoolean().decode(Codec.ofBoolean().encode(true)));
        assertEquals(false, Codec.ofBoolean().decode(Codec.ofBoolean().encode(false)));
    }

    @Test
    void numberCodecsTolerateSurroundingWhitespace() {
        // The file store reads a value back verbatim, and a hand-edited log or a
        // trailing newline should not turn a good recorded output into a failure.
        assertEquals(42, Codec.ofInt().decode(" 42 "));
        assertEquals(3.5, Codec.ofDouble().decode("3.5\n"));
    }

    private enum Colour {
        RED,
        GREEN,
        BLUE
    }

    @Test
    void enumCodecRecordsTheConstantName() {
        Codec<Colour> codec = Codec.ofEnum(Colour.class);
        assertEquals("GREEN", codec.encode(Colour.GREEN));
        assertEquals(Colour.GREEN, codec.decode("GREEN"));
        assertEquals(Colour.BLUE, codec.decode(codec.encode(Colour.BLUE)));
    }

    @Test
    void enumCodecRejectsANameTheTypeNoLongerHolds() {
        Codec<Colour> codec = Codec.ofEnum(Colour.class);
        assertThrows(IllegalArgumentException.class, () -> codec.decode("PURPLE"));
    }

    @Test
    void enumCodecRecordsAStepOutputInAStore() {
        InMemoryStore store = new InMemoryStore();
        Workflow<String, Colour> workflow =
                Workflow.<String>named("pick")
                        .step("choose", (String in, StepContext ctx) -> Colour.RED, Codec.ofEnum(Colour.class))
                        .build();

        WorkflowEngine engine = new WorkflowEngine(store, Sleeper.none(), new java.util.Random(1));
        assertEquals(Colour.RED, engine.run(workflow, "go", "run-1").outputOrThrow());

        // The recorded enum output survives in the store and decodes back to the
        // same constant, which is the whole point of a codec on that step.
        assertEquals(
                Colour.RED,
                Codec.ofEnum(Colour.class).decode(store.loadOutput("run-1", "choose").orElseThrow()));
    }
}
