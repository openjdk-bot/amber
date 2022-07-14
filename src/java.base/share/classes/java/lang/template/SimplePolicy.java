/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.template;

import java.util.Arrays;
import java.util.Objects;

import jdk.internal.javac.PreviewFeature;

/**
 * This interface simplifies declaration of {@link TemplatePolicy TemplatePolicys}
 * that do not throw checked exceptions. For example:
 * {@snippet :
 * SimplePolicy<String> concatPolicy = ts -> {
 *             StringBuilder sb = new StringBuilder();
 *             Iterator<String> fragmentsIter = ts.fragments().iterator();
 *             for (Object value : ts.values()) {
 *                 sb.append(fragmentsIter.next());
 *                 sb.append(value);
 *             }
 *             sb.append(fragmentsIter.next());
 *            return sb.toString();
 *         });
 * }
 *
 * @param <R>  Policy's apply result type.
 *
 * @since 20
 */
@PreviewFeature(feature=PreviewFeature.Feature.STRING_TEMPLATES)
@FunctionalInterface
public interface SimplePolicy<R> extends TemplatePolicy<R, RuntimeException> {
	/**
	 * Chain template policies to produce a new policy that applies the supplied
	 * policies from right to left. The {@code head} policy is a {@link SimplePolicy}
	 * The {@code tail} policies must return type {@link TemplatedString}.
	 *
	 * @param head  last {@link SimplePolicy} to be applied, return type {@code R}
	 * @param tail  first policies to apply, return type {@code TemplatedString}
	 *
	 * @return a new {@link SimplePolicy} that applies the supplied policies
	 *         from right to left
	 *
	 * @param <R> return type of the head policy and resulting policy
	 *
	 * @throws NullPointerException if any of the arguments is null.
	 */
	@SuppressWarnings("varargs")
	@SafeVarargs
	public static <R> SimplePolicy<R>
	chain(SimplePolicy<R> head,
		  TemplatePolicy<TemplatedString, RuntimeException>... tail) {
		Objects.requireNonNull(head, "head must not be null");
		Objects.requireNonNull(tail, "tail must not be null");

		if (tail.length == 0) {
			return head;
		}

		TemplatePolicy<TemplatedString, RuntimeException> last =
				TemplatePolicy.chain(tail[0], Arrays.copyOfRange(tail, 1, tail.length));

		return ts -> head.apply(last.apply(ts));
	}
}
