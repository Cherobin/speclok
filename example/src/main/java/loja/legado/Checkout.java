package loja.legado;

/** Closes the order (legacy, no spec). Just orchestrates cart + customer. */
public class Checkout {

	public String resumo(Carrinho carrinho, int distanciaKm) {
		int total = carrinho.totalCentavos(distanciaKm);
		return "Total to pay: $ " + (total / 100.0);
	}
}
