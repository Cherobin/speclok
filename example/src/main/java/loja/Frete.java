package loja;

import speclok.Spec;
import speclok.AC;

/**
 * Shipping-fee calculation. The spec sits on the method, as an annotation - it is the single
 * source of truth for the contract; the docs, the briefing and the gates are all derived from it.
 */
@Spec(
	id = "T-200",
	objective = "Shipping must never be negative, and is free above the free-shipping threshold",
	allowedFiles = {"src/main/java/loja/Frete.java", "src/test/java/loja/FreteSpecTest.java"},
	symbols = {"Frete.calcular"},
	outOfScope = {"change the price table", "change the free-shipping threshold"}
)
public class Frete {

	/** Above this subtotal, shipping is free. */
	public static final int FRETE_GRATIS_ACIMA_DE = 200;

	@AC(id = "AC1", given = "a subtotal of 50 (below the free-shipping threshold)",
		when = "calcular(50) is called",
		then = "returns a shipping fee >= 0, never negative")
	@AC(id = "AC2", given = "a subtotal of 250 (above the free-shipping threshold)",
		when = "calcular(250) is called",
		then = "returns 0 (free shipping)")
	public int calcular(int subtotal) {
		if (subtotal >= FRETE_GRATIS_ACIMA_DE) return 0;
		int frete = 20 - subtotal / 10;
		return Math.max(frete, 0);
	}
}
