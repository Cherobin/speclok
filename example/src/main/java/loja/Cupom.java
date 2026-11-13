package loja;

import speclok.AC;
import speclok.Spec;

/** Coupon discount on the order total. */
@Spec(
	id = "T-201",
	objective = "A coupon discount must never make the order total negative",
	allowedFiles = {"src/main/java/loja/Cupom.java", "src/test/java/loja/CupomSpecTest.java"},
	symbols = {"Cupom.aplicar"},
	outOfScope = {"change the coupon eligibility rules"}
)
public class Cupom {

	private final int descontoReais;

	public Cupom(int descontoReais) {
		this.descontoReais = descontoReais;
	}

	@AC(id = "AC1", given = "a total of 100 and a 10-unit coupon",
		when = "aplicar(100) is called",
		then = "returns 90")
	@AC(id = "AC2", given = "a total of 5 and a 50-unit coupon",
		when = "aplicar(5) is called",
		then = "returns 0, never negative")
	public int aplicar(int total) {
		return Math.max(total - descontoReais, 0);
	}
}
