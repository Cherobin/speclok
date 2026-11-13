package loja;

/** Acceptance tests for T-200 (dependency-free - runs on the harness's internal gate, so it works
 * on any machine with no Maven/JUnit). Convention: each method name starts with the @AC id, so the
 * acceptance gate and trace map criterion -> test. Point testCommand at `mvn test` to use JUnit instead. */
public class FreteSpecTest {

	/** AC1 · shipping never negative */
	static void AC1_neverNegative() {
		if (new Frete().calcular(50) < 0) throw new AssertionError("shipping can never be negative");
	}

	/** AC2 · free above the threshold */
	static void AC2_freeAboveThreshold() {
		int fee = new Frete().calcular(250);
		if (fee != 0) throw new AssertionError("expected 0 (free) above the threshold, got " + fee);
	}

	public static void main(String[] args) throws Exception {
		String target = args.length > 0 ? args[0] : "all";
		int failed = 0;
		for (java.lang.reflect.Method m : FreteSpecTest.class.getDeclaredMethods()) {
			if (!m.getName().startsWith("AC")) continue;
			if (!"all".equals(target) && !m.getName().equals(target) && !m.getName().startsWith(target + "_")) continue;
			try { m.invoke(null); System.out.println("PASS " + m.getName()); }
			catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t;
				System.out.println("FAIL " + m.getName() + " :: " + c.getMessage()); failed++; }
		}
		System.exit(failed == 0 ? 0 : 1);
	}
}
