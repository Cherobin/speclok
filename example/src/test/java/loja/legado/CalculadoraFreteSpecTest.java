package loja.legado;

/** Acceptance tests for T-312 (no JUnit - runs on the harness's internal gate):
 * {@code java loja.legado.CalculadoraFreteSpecTest <AC-id>}. Exit 0 = pass, 1 = fail. */
public class CalculadoraFreteSpecTest {

	/** AC1 · shipping never negative (large order) */
	static void AC1_neverNegative() {
		int frete = new CalculadoraFrete().calcularCentavos(60000, 10);
		if (frete < 0) throw new AssertionError("shipping went NEGATIVE: " + frete);
	}

	/** AC2 · normal fee preserved (small order) */
	static void AC2_normalFeeOnSmallOrder() {
		int frete = new CalculadoraFrete().calcularCentavos(1000, 10);
		if (frete != 490) throw new AssertionError("expected 490, got " + frete);
	}

	public static void main(String[] args) throws Exception {
		String target = args.length > 0 ? args[0] : "all";
		int failed = 0;
		for (java.lang.reflect.Method m : CalculadoraFreteSpecTest.class.getDeclaredMethods()) {
			if (!m.getName().startsWith("AC")) continue;
			if (!"all".equals(target) && !m.getName().equals(target) && !m.getName().startsWith(target + "_")) continue;
			try { m.invoke(null); System.out.println("PASS " + m.getName()); }
			catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t;
				System.out.println("FAIL " + m.getName() + " :: " + c.getMessage()); failed++; }
		}
		System.exit(failed == 0 ? 0 : 1);
	}
}
