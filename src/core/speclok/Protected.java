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
 * Marks a class or method as a <em>protected area</em> - code that needs elevated care (compliance,
 * security, audit, credentials, business-critical, data integrity). The scope guard reads this when an
 * agent is about to edit the file and enforces a policy by {@link #tier()}:
 * <ul>
 *   <li>{@code "RESTRICTED"} - allowed, but the guard surfaces a checkpoint (state what and why, get
 *       human review). The default when the tier is omitted.</li>
 *   <li>{@code "FORBIDDEN"} - the guard blocks the edit; it must not be changed without human sign-off.</li>
 * </ul>
 * This is the code-enforced twin of a "protected-areas" catalog: the policy lives on the symbol it
 * governs, as {@code @Retention(SOURCE)}, so it never reaches the bytecode.
 * <p>
 * Example:
 * <pre>&#64;Protected(tier = "FORBIDDEN", reason = "PCI: never touch credential validation without security sign-off")
 * public boolean verifyCredentials(String user, String secret) { ... }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface Protected {
	/**
	 * The protection tier: {@code "RESTRICTED"} (checkpoint, default) or {@code "FORBIDDEN"} (block).
	 *
	 * @return the tier.
	 */
	String tier() default "RESTRICTED";

	/**
	 * Why this area is protected - shown to the agent at the checkpoint.
	 *
	 * @return the reason, or empty.
	 */
	String reason() default "";
}
