package loja.legado;

/** Customer (legacy, no spec). Noise: nothing to do with shipping. */
public class Cliente {

	private final String nome;
	private final int cep;

	public Cliente(String nome, int cep) {
		this.nome = nome;
		this.cep = cep;
	}

	public String getNome() { return nome; }
	public int getCep() { return cep; }
}
