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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser and serializer - enough for the SDD harness: the
 * MCP wire protocol (JSON-RPC over stdio), the config file, telemetry records and Claude Code
 * hook events. No external jars; the whole harness ships inside speclok.
 * <p>
 * Values map to: {@link Map}{@code <String,Object>} (object), {@link List}{@code <Object>}
 * (array), {@link String}, {@link Long}/{@link Double} (number), {@link Boolean}, and {@code null}.
 */
final class SddJson {
	private SddJson() {}

	// ---- parse ---------------------------------------------------------------

	static Object parse(String text) {
		P p = new P(text);
		p.ws();
		Object v = p.value();
		p.ws();
		return v;
	}

	/** Convenience: parse and cast to object, or an empty map on anything else. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> parseObject(String text) {
		try {
			Object v = parse(text);
			if (v instanceof Map) return (Map<String, Object>) v;
		} catch (RuntimeException ignore) {}
		return new LinkedHashMap<String, Object>();
	}

	private static final class P {
		final String s; int i, n;
		P(String s) { this.s = s; this.n = s.length(); }

		void ws() { while (i < n && Character.isWhitespace(s.charAt(i))) i++; }

		RuntimeException err(String m) { return new IllegalArgumentException("JSON@" + i + ": " + m); }

		Object value() {
			if (i >= n) throw err("unexpected end");
			char c = s.charAt(i);
			switch (c) {
				case '{': return object();
				case '[': return array();
				case '"': return string();
				case 't': lit("true"); return Boolean.TRUE;
				case 'f': lit("false"); return Boolean.FALSE;
				case 'n': lit("null"); return null;
				default: return number();
			}
		}

		void lit(String w) {
			if (!s.regionMatches(i, w, 0, w.length())) throw err("expected " + w);
			i += w.length();
		}

		Map<String, Object> object() {
			Map<String, Object> m = new LinkedHashMap<String, Object>();
			i++; ws();
			if (i < n && s.charAt(i) == '}') { i++; return m; }
			while (true) {
				ws();
				if (i >= n || s.charAt(i) != '"') throw err("expected key");
				String k = string();
				ws();
				if (i >= n || s.charAt(i) != ':') throw err("expected ':'");
				i++; ws();
				m.put(k, value());
				ws();
				if (i >= n) throw err("unterminated object");
				char c = s.charAt(i++);
				if (c == '}') return m;
				if (c != ',') throw err("expected ',' or '}'");
			}
		}

		List<Object> array() {
			List<Object> a = new ArrayList<Object>();
			i++; ws();
			if (i < n && s.charAt(i) == ']') { i++; return a; }
			while (true) {
				ws();
				a.add(value());
				ws();
				if (i >= n) throw err("unterminated array");
				char c = s.charAt(i++);
				if (c == ']') return a;
				if (c != ',') throw err("expected ',' or ']'");
			}
		}

		String string() {
			StringBuilder b = new StringBuilder();
			i++; // opening quote
			while (i < n) {
				char c = s.charAt(i++);
				if (c == '"') return b.toString();
				if (c == '\\') {
					if (i >= n) break;
					char e = s.charAt(i++);
					switch (e) {
						case '"': b.append('"'); break;
						case '\\': b.append('\\'); break;
						case '/': b.append('/'); break;
						case 'b': b.append('\b'); break;
						case 'f': b.append('\f'); break;
						case 'n': b.append('\n'); break;
						case 'r': b.append('\r'); break;
						case 't': b.append('\t'); break;
						case 'u':
							if (i + 4 > n) throw err("bad \\u");
							b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
							i += 4; break;
						default: throw err("bad escape \\" + e);
					}
				} else b.append(c);
			}
			throw err("unterminated string");
		}

		Object number() {
			int start = i;
			boolean dbl = false;
			while (i < n) {
				char c = s.charAt(i);
				if (c == '-' || c == '+' || (c >= '0' && c <= '9')) { i++; }
				else if (c == '.' || c == 'e' || c == 'E') { dbl = true; i++; }
				else break;
			}
			String num = s.substring(start, i);
			if (num.isEmpty()) throw err("invalid value");
			if (dbl) return Double.valueOf(num);
			try { return Long.valueOf(num); }
			catch (NumberFormatException ex) { return Double.valueOf(num); }
		}
	}

	// ---- serialize -----------------------------------------------------------

	static String write(Object v) {
		StringBuilder b = new StringBuilder();
		writeTo(b, v);
		return b.toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeTo(StringBuilder b, Object v) {
		if (v == null) { b.append("null"); return; }
		if (v instanceof String) { quote(b, (String) v); return; }
		if (v instanceof Boolean || v instanceof Integer || v instanceof Long) { b.append(v.toString()); return; }
		if (v instanceof Double || v instanceof Float) {
			double d = ((Number) v).doubleValue();
			if (d == Math.rint(d) && !Double.isInfinite(d)) b.append(Long.toString((long) d));
			else b.append(Double.toString(d));
			return;
		}
		if (v instanceof Map) {
			b.append('{');
			boolean first = true;
			for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
				if (!first) b.append(',');
				first = false;
				quote(b, e.getKey());
				b.append(':');
				writeTo(b, e.getValue());
			}
			b.append('}');
			return;
		}
		if (v instanceof Iterable) {
			b.append('[');
			boolean first = true;
			for (Object o : (Iterable<Object>) v) {
				if (!first) b.append(',');
				first = false;
				writeTo(b, o);
			}
			b.append(']');
			return;
		}
		quote(b, v.toString());
	}

	private static void quote(StringBuilder b, String s) {
		b.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': b.append("\\\""); break;
				case '\\': b.append("\\\\"); break;
				case '\n': b.append("\\n"); break;
				case '\r': b.append("\\r"); break;
				case '\t': b.append("\\t"); break;
				case '\b': b.append("\\b"); break;
				case '\f': b.append("\\f"); break;
				default:
					if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
			}
		}
		b.append('"');
	}

	// ---- typed accessors (null-safe) ----------------------------------------

	@SuppressWarnings("unchecked")
	static Map<String, Object> obj(Object v) {
		return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
	}

	@SuppressWarnings("unchecked")
	static List<Object> arr(Object v) {
		return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
	}

	static String str(Object v) { return v == null ? "" : v.toString(); }

	static long lng(Object v, long dflt) {
		if (v instanceof Number) return ((Number) v).longValue();
		try { return Long.parseLong(str(v).trim()); } catch (RuntimeException e) { return dflt; }
	}

	static List<String> strList(Object v) {
		List<String> out = new ArrayList<String>();
		if (v instanceof List) for (Object o : (List<?>) v) out.add(str(o));
		else if (v != null && !str(v).isEmpty()) out.add(str(v));
		return out;
	}

	/** Builds an ordered map inline: {@code map("a", 1, "b", 2)}. */
	static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put(str(kv[i]), kv[i + 1]);
		return m;
	}
}
