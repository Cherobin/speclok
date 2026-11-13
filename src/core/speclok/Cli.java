/*
 * Copyright (C) 2026 Ricardo Cherobin.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package speclok;

import java.util.Arrays;
import java.util.List;

/**
 * Standalone entry point: {@code java -jar speclok.jar <command> [args...]}. Dispatches to the three
 * sub-apps - {@code sdd} (the harness), {@code specbook} (the spec book) and {@code specctx} (the agent
 * briefing). Dependency-free: only the JDK. The {@code @Spec}/{@code @AC} annotations and the compile
 * -time {@link SpecLintProcessor} ship in the same jar and need nothing on the command line.
 */
public final class Cli {
	static final String VERSION = "0.1.0";

	public static void main(String[] args) throws Exception {
		if (args.length == 0) { usage(); System.exit(2); return; }
		String cmd = args[0];
		List<String> rest = Arrays.asList(args).subList(1, args.length);
		switch (cmd) {
			case "-h": case "--help": usage(); System.exit(0); return;
			case "-v": case "--version": case "version":
				System.out.println("speclok " + VERSION); System.exit(0); return;
			case "sdd":      System.exit(new SddApp().runApp(rest)); return;
			case "specbook": System.exit(new SpecBookApp().runApp(rest)); return;
			case "specctx":  System.exit(new SpecContextApp().runApp(rest)); return;
			default:
				System.err.println("speclok: unknown command '" + cmd + "'\n");
				usage(); System.exit(2);
		}
	}

	private static void usage() {
		System.err.println("speclok " + VERSION + " - spec-as-annotation for Java");
		System.err.println("usage: java -jar speclok.jar <command> [args]\n");
		System.err.println("  sdd <subcommand>   harness: list, briefing, speclint, verify, scaffold, hooks, mcp, init");
		System.err.println("  specbook <root>    generate the spec book from @Spec/@AC");
		System.err.println("  specctx <task>     print the agent briefing for a task id");
		System.err.println("  --version          print the version");
	}

	private Cli() {}
}
