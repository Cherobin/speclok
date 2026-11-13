package loja.legado;

import java.util.ArrayList;
import java.util.List;

/** Shopping cart (legacy, no spec). */
public class Carrinho {

	private final List<Integer> itensCentavos = new ArrayList<Integer>();

	public void adicionar(int precoCentavos) {
		itensCentavos.add(precoCentavos);
	}

	public int subtotalCentavos() {
		int t = 0;
		for (int p : itensCentavos) t += p;
		return t;
	}

	/** Cart total = subtotal + shipping (uses the legacy calculator). */
	public int totalCentavos(int distanciaKm) {
		int subtotal = subtotalCentavos();
		int frete = new CalculadoraFrete().calcularCentavos(subtotal, distanciaKm);
		return subtotal + frete;
	}
}
