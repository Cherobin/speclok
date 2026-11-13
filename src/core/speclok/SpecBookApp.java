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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;


/**
 * Generates the <em>spec book</em> (governing constitution + every task's spec) by reading the
 * {@code @Spec}/{@code @AC} annotations straight from the source tree. The annotation is the single
 * source of truth; this document is <b>derived</b>, never hand-written - you edit the annotation,
 * and the spec book regenerates from it.
 * <p>
 * Runs as a speclok app: {@code java -jar speclok.jar specbook <src-root> [-o out.md]}. Deterministic,
 * no LLM. The parser mirrors {@code .sdd/sdd_annotations.py} but stays dependency-free (only the JDK).
 */
public class SpecBookApp {
	public String getAppName() {
		return "specbook";
	}

	public String getAppDescription() {
		return "Generates the spec book (constitution + specs) from @Spec/@AC annotations in a source tree.";
	}

	public int runApp(List<String> args) throws Exception {
		List<Path> roots = new ArrayList<Path>();
		Path out = null;
		for (int i = 0; i < args.size(); i++) {
			String a = args.get(i);
			if ("-o".equals(a) && i + 1 < args.size()) out = Paths.get(args.get(++i));
			else roots.add(Paths.get(a));
		}
		if (roots.isEmpty()) roots.add(Paths.get("."));

		List<Spec> specs = new ArrayList<Spec>();
		for (Path root : roots) specs.addAll(scan(root));
		specs.sort(Comparator.comparing(s -> s.id));

		String md = render(specs);
		if (out != null) {
			Files.write(out, md.getBytes(StandardCharsets.UTF_8));
			System.out.println("written: " + out + "  (" + md.length() + " chars, derived from @Spec/@AC)");
		} else {
			System.out.print(md);
		}
		return 0;
	}

	// ---- scan ----------------------------------------------------------------

	private List<Spec> scan(Path root) throws IOException {
		List<Spec> found = new ArrayList<Spec>();
		if (!Files.exists(root)) return found;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				if (!p.toString().endsWith(".java")) continue;
				String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
				if (!text.contains("@Spec")) continue;
				Spec s = manifestFromText(text, root.relativize(p).toString().replace('\\', '/'));
				if (s != null) found.add(s);
			}
		}
		return found;
	}

	// ---- parser (@Spec/@AC -> Spec), self-contained --------------------------

	static final class Spec {
		String id = "", objective = "", specRef = "", source = "";
		List<String> allowedFiles = new ArrayList<String>(), symbols = new ArrayList<String>(), outOfScope = new ArrayList<String>();
		List<String[]> acs = new ArrayList<String[]>(); // [id, given, when, then]
	}

	private static final class PV {
		final Object v; final int i;
		PV(Object v, int i) { this.v = v; this.i = i; }
	}

	private static final Pattern MEMBER = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*");

	static int matchParen(String t, int open) {
		int depth = 0, i = open, n = t.length();
		boolean inStr = false;
		while (i < n) {
			char c = t.charAt(i);
			if (inStr) {
				if (c == '\\') { i += 2; continue; }
				if (c == '"') inStr = false;
			} else if (c == '"') inStr = true;
			else if (c == '(') depth++;
			else if (c == ')') { if (--depth == 0) return i; }
			i++;
		}
		return -1;
	}

	static PV readString(String t, int i) {
		i++; // skip opening quote
		StringBuilder sb = new StringBuilder();
		int n = t.length();
		while (i < n) {
			char c = t.charAt(i);
			if (c == '\\') {
				char nx = i + 1 < n ? t.charAt(i + 1) : '\0';
				if (nx == 'n') sb.append('\n');
				else if (nx == 't') sb.append('\t');
				else sb.append(nx);
				i += 2; continue;
			}
			if (c == '"') return new PV(sb.toString(), i + 1);
			sb.append(c); i++;
		}
		return new PV(sb.toString(), i);
	}

	static PV parseValue(String b, int i) {
		int n = b.length();
		while (i < n && " \t\r\n".indexOf(b.charAt(i)) >= 0) i++;
		if (i < n && b.charAt(i) == '"') return readString(b, i);
		if (i < n && b.charAt(i) == '{') {
			List<String> items = new ArrayList<String>(); i++;
			while (i < n) {
				while (i < n && " \t\r\n,".indexOf(b.charAt(i)) >= 0) i++;
				if (i < n && b.charAt(i) == '}') return new PV(items, i + 1);
				if (i < n && b.charAt(i) == '"') { PV pv = readString(b, i); items.add((String) pv.v); i = pv.i; }
				else i++;
			}
			return new PV(items, i);
		}
		int j = i;
		while (j < n && b.charAt(j) != ',') j++;
		return new PV(b.substring(i, j).trim(), j);
	}

	static Map<String, Object> parseMembers(String body) {
		Map<String, Object> out = new LinkedHashMap<String, Object>();
		int i = 0, n = body.length();
		Matcher m = MEMBER.matcher(body);
		while (i < n) {
			m.region(i, n);
			if (m.lookingAt()) {
				String key = m.group(1);
				PV pv = parseValue(body, m.end());
				out.put(key, pv.v);
				i = pv.i;
			} else i++;
		}
		return out;
	}

	static List<String> findAnnotations(String text, String name) {
		List<String> out = new ArrayList<String>();
		Matcher m = Pattern.compile("@" + Pattern.quote(name) + "\\s*\\(").matcher(text);
		while (m.find()) {
			int open = m.end() - 1, close = matchParen(text, open);
			if (close != -1) out.add(text.substring(open + 1, close));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<String> asList(Object v) {
		if (v instanceof List) return (List<String>) v;
		List<String> l = new ArrayList<String>();
		if (v != null && !((String) v).isEmpty()) l.add((String) v);
		return l;
	}

	private static String str(Object v) { return v == null ? "" : v.toString(); }

	static Spec manifestFromText(String text, String source) {
		List<String> specs = findAnnotations(text, "Spec");
		if (specs.isEmpty()) return null;
		Map<String, Object> s = parseMembers(specs.get(0));
		Spec sp = new Spec();
		sp.id = str(s.get("id"));
		if (sp.id.isEmpty()) return null;
		sp.objective = str(s.get("objective"));
		sp.specRef = str(s.get("specRef"));
		sp.allowedFiles = asList(s.get("allowedFiles"));
		sp.symbols = asList(s.get("symbols"));
		sp.outOfScope = asList(s.get("outOfScope"));
		sp.source = source;
		for (String body : findAnnotations(text, "AC")) {
			Map<String, Object> ac = parseMembers(body);
			if (ac.get("id") != null)
				sp.acs.add(new String[] {str(ac.get("id")), str(ac.get("given")), str(ac.get("when")), str(ac.get("then"))});
		}
		return sp;
	}

	// ---- render --------------------------------------------------------------

	static final String[][] CONSTITUTION = {
		{"The model proposes, the harness decides", "Anything that can be verified by code leaves the prompt and becomes an executable rule."},
		{"The spec lives in the code", "Each contract is a @Spec/@AC annotation on the symbol it governs - not a loose file."},
		{"Verification is deterministic", "spec-lint, traceability and invariants are a pure function of (code, annotation); no LLM."},
		{"Scope is law", "The agent only touches allowedFiles; everything else is blocked before it happens."},
		{"Done is when the criteria pass", "No @AC without a test; the acceptance gate won't release the stop while any of them fails."},
	};

	private static String cell(String s) { return s.replace("|", "\\|"); }

	static String render(List<Spec> specs) {
		StringBuilder b = new StringBuilder();
		b.append("# Spec Book - generated from the `@Spec`/`@AC` annotations\n\n");
		b.append("> **Derived, not hand-written.** The annotation in the code is the single source of truth; this document is\n");
		b.append("> generated by speclok (`java -jar speclok.jar specbook`). Edit the annotation, not this file.\n\n");
		b.append("## Constitution - governing principles\n\n");
		b.append("> Inherited by every spec. Immutable unless explicitly decided otherwise.\n\n");
		for (int i = 0; i < CONSTITUTION.length; i++)
			b.append(i + 1).append(". **").append(CONSTITUTION[i][0]).append(".** ").append(CONSTITUTION[i][1]).append("\n");
		b.append("\n## Spec index\n\n");
		for (Spec s : specs) {
			StringBuilder ids = new StringBuilder();
			for (String[] ac : s.acs) { if (ids.length() > 0) ids.append(", "); ids.append(ac[0]); }
			b.append("- **").append(s.id).append("** - ").append(s.objective).append("  (").append(ids).append(")\n");
		}
		b.append("\n## The ").append(specs.size()).append(" specs\n\n");
		for (Spec s : specs) {
			b.append("### ").append(s.id).append(" - ").append(s.objective).append("\n\n");
			if (!s.source.isEmpty()) b.append("- **Source** (@Spec): `").append(s.source).append("`\n");
			if (!s.symbols.isEmpty()) b.append("- **Symbols**: ").append(joinCode(s.symbols)).append("\n");
			if (!s.allowedFiles.isEmpty()) b.append("- **Scope**: ").append(joinCode(s.allowedFiles)).append("\n");
			if (!s.outOfScope.isEmpty()) b.append("- **Out of scope**: ").append(String.join(", ", s.outOfScope)).append("\n");
			if (!s.acs.isEmpty()) {
				b.append("\n| AC | Given | When | Then |\n|----|-------|------|------|\n");
				for (String[] ac : s.acs)
					b.append("| **").append(ac[0]).append("** | ").append(cell(ac[1])).append(" | ")
					 .append(cell(ac[2])).append(" | ").append(cell(ac[3])).append(" |\n");
			}
			b.append("\n");
		}
		b.append("---\n").append("*").append(specs.size()).append(" specs derived from the annotations. ")
		 .append("Regenerate with `java -jar speclok.jar specbook`.*\n");
		return b.toString();
	}

	private static String joinCode(List<String> xs) {
		StringBuilder b = new StringBuilder();
		for (String x : xs) { if (b.length() > 0) b.append(", "); b.append('`').append(x).append('`'); }
		return b.toString();
	}

	/** Allows running standalone for testing: {@code java speclok.SpecBookApp <root> [-o out.md]}. */
	public static void main(String[] args) throws Exception {
		System.exit(new SpecBookApp().runApp(Arrays.asList(args)));
	}
}
