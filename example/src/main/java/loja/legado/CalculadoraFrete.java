package loja.legado;

import speclok.Spec;
import speclok.AC;

/**
 * Shipping calculation. The spec was just added (this was legacy code), contracting the fix for
 * the negative-shipping bug - proposed by the scout from a vague ticket.
 */
@Spec(
	id = "T-312",
	objective = "Calculated shipping must never be negative: the subtotal-proportional discount must not push the fee below zero, even on large orders",
	allowedFiles = {
		"src/main/java/loja/legado/CalculadoraFrete.java",
		"src/test/java/loja/legado/CalculadoraFreteSpecTest.java"
	},
	symbols = {"CalculadoraFrete.calcularCentavos"},
	outOfScope = {
		"change TARIFA_POR_KM",
		"change the 1%-of-subtotal discount rule",
		"touch Carrinho or the Frete.calcular path (T-200)"
	}
)
public class CalculadoraFrete {

	private static final int TARIFA_POR_KM = 50; // cents

	@AC(id = "AC1", given = "a large order where the 1% discount exceeds the distance fee (distanciaKm=10, subtotalCentavos=60000)",
		when = "calcularCentavos(subtotalCentavos, distanciaKm) is called",
		then = "returns a shipping fee >= 0, never negative")
	@AC(id = "AC2", given = "a small order where the fee exceeds the discount (distanciaKm=10, subtotalCentavos=1000)",
		when = "calcularCentavos(subtotalCentavos, distanciaKm) is called",
		then = "returns the normal base - discount fee (490 cents), preserving the behavior")
	public int calcularCentavos(int subtotalCentavos, int distanciaKm) {
		int base = distanciaKm * TARIFA_POR_KM;
		int desconto = subtotalCentavos / 100; // 1% of the subtotal becomes a shipping discount
		return Math.max(base - desconto, 0); // floor at 0 - shipping never negative
	}
}
