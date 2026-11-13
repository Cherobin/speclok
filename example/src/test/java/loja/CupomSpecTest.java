package loja;

/** Acceptance tests for T-201 (dependency-free - runs on the harness's internal gate). */
public class CupomSpecTest {

	/** AC1 · a 10-unit coupon on a total of 100 returns 90 */
	static void AC1_appliesDiscount() {
		int total = new Cupom(10).aplicar(100);
		if (total != 90) throw new AssertionError("expected 90, got " + total);
	}

	/** AC2 · a 50-unit coupon on a total of 5 returns 0, never negative */
	static void AC2_neverNegative() {
		int total = new Cupom(50).aplicar(5);
		if (total != 0) throw new AssertionError("expected 0 (never negative), got " + total);
	}

	public static void main(String[] args) throws Exception {
		String target = args.length > 0 ? args[0] : "all";
		int failed = 0;
		for (java.lang.reflect.Method m : CupomSpecTest.class.getDeclaredMethods()) {
			if (!m.getName().startsWith("AC")) continue;
			if (!"all".equals(target) && !m.getName().equals(target) && !m.getName().startsWith(target + "_")) continue;
			try { m.invoke(null); System.out.println("PASS " + m.getName()); }
			catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t;
				System.out.println("FAIL " + m.getName() + " :: " + c.getMessage()); failed++; }
		}
		System.exit(failed == 0 ? 0 : 1);
	}
}
