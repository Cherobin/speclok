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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

/**
 * Spec-lint as a compile error. A standard annotation processor that reads the {@code @Spec}/
 * {@code @AC} annotations during compilation and makes {@code javac} FAIL when a spec is
 * incomplete - the deterministic "harness decides" turned into the build itself.
 * <p>
 * Checks (mirror of {@code .sdd/validate/spec_lint.py}): a {@code @Spec} must have a non-empty
 * {@code objective} and {@code allowedFiles}; it must carry at least one {@code @AC}; every
 * {@code @AC} needs a well-formed id ({@code AC<n>}) and non-empty {@code dado/quando/entao}.
 * {@code outOfScope} missing is a warning. {@code @Spec}/{@code @AC} are {@code @Retention(SOURCE)}
 * but annotation processors still see them during compilation - so this works with plain javac.
 */
@SupportedAnnotationTypes({"speclok.Spec", "speclok.AC", "speclok.ACs"})
@SupportedOptions({"speclok.maxFileLines", "speclok.severity"})
public class SpecLintProcessor extends AbstractProcessor {
	// AC<n>, optionally with a descriptive suffix (AC1, AC1_OverflowGuard) - agents often reuse the
	// test-method name as the id, and that should not break a build.
	private static final Pattern AC_ID = Pattern.compile("AC\\d+(_\\w+)?");
	private Trees trees;   // Compiler Tree API (javac) - for file invariants; fail-open if absent

	@Override public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	@Override public synchronized void init(ProcessingEnvironment env) {
		super.init(env);
		try { trees = Trees.instance(env); } catch (Throwable t) { trees = null; }
	}

	@Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		Messager msg = processingEnv.getMessager();
		for (Element e : roundEnv.getElementsAnnotatedWith(Spec.class)) {
			Spec spec = e.getAnnotation(Spec.class);
			if (spec == null) continue;
			String id = spec.id().isEmpty() ? "?" : spec.id();

			Diagnostic.Kind sev = severity();                  // incomplete-spec severity (error by default)

			if (spec.objective().trim().isEmpty())
				lint(msg, sev, e, id, "objective is empty");
			if (spec.allowedFiles().length == 0)
				lint(msg, sev, e, id, "allowedFiles is empty - the task has no scope");
			if (spec.outOfScope().length == 0)
				warn(msg, e, id, "outOfScope is empty - explicitly delimit what NOT to do");

			int max = maxFileLines();                          // size invariant (gardener)
			if (max > 0) {
				int lines = lineCount(e);
				if (lines > max)
					warn(msg, e, id, "file has " + lines + " lines (> " + max + ") - break it into smaller units");
			}

			List<Holder> acs = new ArrayList<Holder>();
			collect(e, acs);                                   // @AC on the type itself
			for (Element m : e.getEnclosedElements())
				if (m.getKind() == ElementKind.METHOD) collect(m, acs);   // and on the methods

			if (acs.isEmpty())
				lint(msg, sev, e, id, "no @AC - a spec needs at least one criterion");

			Set<String> seenIds = new HashSet<String>();
			for (Holder h : acs) {
				AC ac = h.ac;
				String acid = ac.id();
				String where = id + "/" + (acid.isEmpty() ? "?" : acid);
				if (acid.isEmpty() || !AC_ID.matcher(acid).matches())
					lint(msg, sev, h.el, where, "invalid @AC id (expected AC<n>, e.g. AC1)");
				else if (!seenIds.add(acid))
					lint(msg, sev, h.el, where, "duplicate @AC id in the same spec");
				if (ac.given().trim().isEmpty())   lint(msg, sev, h.el, where, "'given' is empty");
				if (ac.when().trim().isEmpty()) lint(msg, sev, h.el, where, "'when' is empty");
				if (ac.then().trim().isEmpty())  lint(msg, sev, h.el, where, "'then' is empty");
			}
		}
		return false;   // doesn't "claim" the annotations - lets other annotation processors run
	}

	private void collect(Element el, List<Holder> out) {
		for (AC ac : el.getAnnotationsByType(AC.class))        // unwraps the @Repeatable(ACs)
			out.add(new Holder(ac, el));
	}

	/** Severity for an incomplete {@code @Spec}: {@code error} (default) breaks the build; {@code warn}
	 * only guides. Set it as a javac annotation-processor option {@code -Aspeclok.severity=warn}
	 * (via {@code compilerArgs} in Maven/Gradle, or the processor options in IntelliJ). For AI-agent and
	 * headless/CI flows, {@code warn} avoids the write-reject-rewrite "gate fight" - the agent gets the
	 * same guidance without a broken build on every in-progress spec. (Reading it from a config file
	 * is a production follow-up.) */
	private Diagnostic.Kind severity() {
		String v = processingEnv.getOptions().get("speclok.severity");
		return (v != null && v.trim().equalsIgnoreCase("warn")) ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR;
	}

	private void lint(Messager msg, Diagnostic.Kind kind, Element el, String where, String what) {
		msg.printMessage(kind, "spec-lint [" + where + "]: " + what, el);
	}

	private void warn(Messager msg, Element el, String where, String what) {
		msg.printMessage(Diagnostic.Kind.WARNING, "spec-lint [" + where + "]: " + what, el);
	}

	/** Per-file line ceiling, from {@code -Aspeclok.maxFileLines=N} (0/absent = disabled).
	 * In the production speclok.jar the same value can come from a config file. */
	private int maxFileLines() {
		String v = processingEnv.getOptions().get("speclok.maxFileLines");
		if (v == null) return 0;
		try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ex) { return 0; }
	}

	/** Counts the lines of the element's source file via the Compiler Tree API. -1 if unavailable. */
	private int lineCount(Element e) {
		if (trees == null) return -1;
		try {
			TreePath p = trees.getPath(e);
			if (p == null) return -1;
			CompilationUnitTree cu = p.getCompilationUnit();
			CharSequence c = cu.getSourceFile().getCharContent(true);
			int lines = c.length() == 0 ? 0 : 1;
			for (int i = 0; i < c.length(); i++) if (c.charAt(i) == '\n') lines++;
			return lines;
		} catch (Throwable t) {
			return -1;
		}
	}

	private static final class Holder {
		final AC ac; final Element el;
		Holder(AC ac, Element el) { this.ac = ac; this.el = el; }
	}
}
