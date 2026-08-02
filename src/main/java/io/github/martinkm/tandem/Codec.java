package io.github.martinkm.tandem;

/**
 * Turns a step's output into text and back.
 *
 * <p>This is what makes a step <em>replayable</em>. When a run resumes, a step
 * that has a codec is not executed again: its recorded output is decoded and
 * passed on. A step without one is executed again, which is correct for pure
 * work and wrong for anything with a side effect. Tandem does not guess which
 * kind a step is, so supplying a codec is how you say "this one already
 * happened, do not do it twice".
 *
 * @param <T> the value being encoded
 */
public interface Codec<T> {

    String encode(T value);

    T decode(String text);

    static Codec<String> ofString() {
        return new Codec<>() {
            @Override
            public String encode(String value) {
                return value;
            }

            @Override
            public String decode(String text) {
                return text;
            }
        };
    }

    static Codec<Integer> ofInt() {
        return new Codec<>() {
            @Override
            public String encode(Integer value) {
                return String.valueOf(value);
            }

            @Override
            public Integer decode(String text) {
                return Integer.valueOf(text.trim());
            }
        };
    }

    static Codec<Long> ofLong() {
        return new Codec<>() {
            @Override
            public String encode(Long value) {
                return String.valueOf(value);
            }

            @Override
            public Long decode(String text) {
                return Long.valueOf(text.trim());
            }
        };
    }

    static Codec<Double> ofDouble() {
        return new Codec<>() {
            @Override
            public String encode(Double value) {
                return String.valueOf(value);
            }

            @Override
            public Double decode(String text) {
                return Double.valueOf(text.trim());
            }
        };
    }

    static Codec<Boolean> ofBoolean() {
        return new Codec<>() {
            @Override
            public String encode(Boolean value) {
                return String.valueOf(value);
            }

            @Override
            public Boolean decode(String text) {
                return Boolean.valueOf(text.trim());
            }
        };
    }
}
