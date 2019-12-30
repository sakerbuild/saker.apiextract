package saker.apiextract.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify that the given element should <b>not</b> be part of the public API.
 * <p>
 * No API bytecode stub will be generated for the given element.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PACKAGE,
		ElementType.ANNOTATION_TYPE })
public @interface ExcludeApi {
}
