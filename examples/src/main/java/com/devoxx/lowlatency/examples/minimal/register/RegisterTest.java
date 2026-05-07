package com.devoxx.lowlatency.examples.minimal.register;

// Slide companion: "What C2 actually does" / "JIT 101 — three tiers, not one"
// Run with: -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly
// (needs hsdis on the library path). Look for `sum` living in a register
// across the full loop: the JIT promotes the field to a register on entry,
// runs the accumulation in registers only, and writes back at the end.
public class RegisterTest {

    private int sum;

    public void calculateSum(int n) {
        for (int i = 0; i < n; i++) {
            sum += i;
        }
    }

    public static void main(String[] args) {
        new RegisterTest().calculateSum(100_000_000);
    }
}
