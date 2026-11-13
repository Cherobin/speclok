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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the SDD <em>spec</em> for the annotated type: the intent and the scope that the
 * harness enforces while a task is open. The spec lives here, next to the code it governs,
 * instead of in a separate {@code .md}/{@code .yaml} file - just as {@code @Getter}
 * replaces a hand-written getter.
 * <p>
 * Like every speclok annotation this is {@link RetentionPolicy#SOURCE} only: it never reaches
 * the bytecode. The SDD harness reads it at build time - it parses {@code @Spec} and
 * {@link AC} and generates the task manifest in memory, then the scope-guard, build-check and
 * acceptance-gate hooks run against that manifest.
 * <p>
 * Example usage:
 * <pre>&#64;Spec(
 *     id = "T-100",
 *     objective = "Shipping must never be negative",
 *     allowedFiles = {"src/main/java/shop/Shipping.java"},
 *     symbols = {"Shipping.calculate"},
 *     outOfScope = {"change the price table", "change the public signature"})
 * public class speclok { ... }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Spec {
	/**
	 * The task id this spec is tracked under (e.g. {@code "T-100"}). The harness keys the
	 * generated manifest by this value; {@code .sdd/current-task} points at it to arm the task.
	 *
	 * @return the task id.
	 */
	String id();

	/**
	 * One sentence stating what this change must achieve - the "what and why".
	 *
	 * @return the objective of the task.
	 */
	String objective();

	/**
	 * Glob patterns, relative to the repo root, of the files the implementer may edit. The
	 * scope-guard hook blocks any edit outside this set before it happens.
	 *
	 * @return the allowed-files globs; empty means "unscoped" (the guard warns instead of blocking).
	 */
	String[] allowedFiles() default {};

	/**
	 * Qualified symbols ({@code Type.method}) the task touches. The context-pack resolver turns
	 * these into {@code file:line} anchors so the agent opens the exact spot without searching.
	 *
	 * @return the symbols in scope.
	 */
	String[] symbols() default {};

	/**
	 * Things explicitly NOT part of this task - the guardrails that keep the change small.
	 *
	 * @return the out-of-scope items.
	 */
	String[] outOfScope() default {};

	/**
	 * Optional pointer to a longer prose spec (e.g. {@code "docs/specs/null.md#contrato"}) for
	 * when the annotation is not the whole story.
	 *
	 * @return the spec reference, or empty if the annotation is self-contained.
	 */
	String specRef() default "";
}
