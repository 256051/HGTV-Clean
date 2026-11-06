package com.hgtv.clean;

/** Synthetic recovery checks never use a registered device or make a network request. */
public final class ModelDeviceStateTest {
    /** Checks range arithmetic, stable reuse and registration changes. */
    public static void main(String[] args) {
        require(ModelDeviceState.candidate(0).equals("100000000000000"));
        require(ModelDeviceState.candidate(-1).equals("999999999999999"));
        for (long number : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -900000000000000L, 900000000000000L})
            require(ModelDeviceState.candidate(number).matches("[1-9][0-9]{14}"));
        ModelDeviceState state = new ModelDeviceState();
        String registered = "1234567890123456789";
        require(state.current(registered).equals(registered));
        state.reject(registered);
        String recovered = state.current(registered);
        require(!recovered.equals(registered) && recovered.matches("[1-9][0-9]{14}"));
        require(state.current(registered).equals(recovered));
        state.reject(registered); require(!state.current(registered).equals(recovered));
        require(state.current("2234567890123456789").equals("2234567890123456789"));
        System.out.println("Model recovery checks passed; registered identity remains separate");
    }
    private static void require(boolean value) { if (!value) throw new AssertionError("model recovery"); }
}
