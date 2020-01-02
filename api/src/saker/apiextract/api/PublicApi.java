/*
 * Copyright (C) 2020 Bence Sipka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package saker.apiextract.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation specifying that the annotated element is considered to be part of the public API of the software.
 * <p>
 * If an element is annotated with this annotation, then the API extract processor will generate API stub class bytecode
 * for the element. It will also include the members of the annotated element based on the {@link #includeMembers()}
 * field and the processor configuration.
 * <p>
 * The other types that are part of the public API of the annotated element will also be automatically included in the
 * public API unless explicitly excluded. This means that if the annotated class inherits from another type, then the
 * superinterfaces and superclasses are considered to be public API as well. For methods the return and parameter types
 * will be included. For fields the field type is included.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PACKAGE,
		ElementType.ANNOTATION_TYPE })
public @interface PublicApi {
	/**
	 * Specifies whether or not the members of public API elements should be included.
	 * <p>
	 * If an element is implicitly included as the public API, but not annotated with {@link PublicApi}, then this field
	 * determines if the enclosed members should be automatically included as well.
	 * <p>
	 * The default is to include members. Can be overridden if the field is set, or the
	 * <code>saker.apiextract.include_members_default</code> is set for the processor.
	 * 
	 * @return The include members setting for the given element.
	 */
	public DefaultableBoolean includeMembers() default DefaultableBoolean.DEFAULT;

	/**
	 * Specifies whether or not to put the constant initialization values of <code>static final</code> fields of
	 * classes.
	 * <p>
	 * If set to <code>true</code>, the constant values are not included in the generated file for the given field,
	 * therefore the constants won't be inlined when compiling against the API class files.
	 * 
	 * @return <code>true</code> to not put the constant value of the field in the generated API class files.
	 */
	public DefaultableBoolean unconstantize() default DefaultableBoolean.DEFAULT;
}
