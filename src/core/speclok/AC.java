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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A single acceptance criterion, in given/when/then form, attached to the method (or type) it
 * describes. The acceptance-gate hook will not let the agent stop until every {@code @AC} has a
 * passing test; by convention the test method name starts with the criterion id, e.g.
 * {@code AC1_returnsValueWhenNotNull}.
 * <p>
 * {@link RetentionPolicy#SOURCE} only, like the rest of speclok - the criterion drives the
 * harness at build time and never reaches the bytecode. Repeatable: stack several on one element.
 * <p>
 * Example usage:
 * <pre>&#64;AC(id = "AC2",
 *     given = "a null value",
 *     when = "checkNotNull(null, msg) is called",
 *     then = "throws NullPointerException with the message msg")
 * public static &lt;T&gt; T checkNotNull(T value, String message) { ... }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
@Repeatable(ACs.class)
public @interface AC {
	/**
	 * The criterion id ({@code "AC1"}, {@code "AC2"}, ...). The acceptance test method name must
	 * start with {@code <id>_} so the gate can map criterion to test.
	 *
	 * @return the criterion id.
	 */
	String id();

	/**
	 * The precondition - the state of the world before the behavior ("given").
	 *
	 * @return the given clause.
	 */
	String given();

	/**
	 * The action under test ("when").
	 *
	 * @return the when clause.
	 */
	String when();

	/**
	 * The expected, observable outcome ("then").
	 *
	 * @return the then clause.
	 */
	String then();
}
