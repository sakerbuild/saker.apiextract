/*
 * Copyright (C) 2020 Bence Sipka
 *
 * This program is free software: you can redistribute it and/or modify 
 * it under the terms of the GNU General Public License as published by 
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package saker.apiextract.processor;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.AnnotatedConstruct;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileManager.Location;
import javax.tools.StandardLocation;

import saker.apiextract.api.DefaultableBoolean;
import saker.apiextract.api.ExcludeApi;
import saker.apiextract.api.PublicApi;
import saker.build.thirdparty.org.objectweb.asm.AnnotationVisitor;
import saker.build.thirdparty.org.objectweb.asm.ClassVisitor;
import saker.build.thirdparty.org.objectweb.asm.ClassWriter;
import saker.build.thirdparty.org.objectweb.asm.FieldVisitor;
import saker.build.thirdparty.org.objectweb.asm.MethodVisitor;
import saker.build.thirdparty.org.objectweb.asm.Opcodes;
import saker.build.thirdparty.org.objectweb.asm.Type;
import saker.build.thirdparty.org.objectweb.asm.signature.SignatureVisitor;
import saker.build.thirdparty.org.objectweb.asm.signature.SignatureWriter;

public class ApiExtractProcessor implements Processor {
	public static final String OPTION_BASE_PACKAGES = "saker.apiextract.base_packages";
	public static final String OPTION_EXCLUDE_PACKAGES = "saker.apiextract.exclude_packages";
	public static final String OPTION_WARN_DOC = "saker.apiextract.warn_doc";
	public static final String OPTION_WARN_DOC_BASE_PACKAGES = "saker.apiextract.warn_doc_base_packages";
	public static final String OPTION_INCLUDE_MEMBERS_DEFAULT = "saker.apiextract.include_members_default";

	private static final String EXCLUDEAPI_CLASSNAME = ExcludeApi.class.getName();
	private static final String PUBLICAPI_CLASSNAME = PublicApi.class.getName();

	private static final Element[] EMPTY_ELEMENT_ARRAY = new Element[0];

	private Filer filer;
	private Messager messager;
	private Elements elements;
	private Types types;

	private TypeElement publicApiType;
	private TypeElement excludeApiType;

	private Map<Element, InclusionState> publicAnnotatedElements = new HashMap<>();
	private Set<Element> excludedAnnotatedElements = new HashSet<>();

	private Set<String> basePackageNames = new TreeSet<>();
	private Set<String> excludePackageNames = new TreeSet<>();
	private Set<String> docWarnBasePackageNames = new TreeSet<>();
	private boolean warnNoDocumentation = false;

	private boolean defaultIncludeMembers = true;

	@Override
	public Set<String> getSupportedOptions() {
		Set<String> result = new TreeSet<>();
		result.add(OPTION_BASE_PACKAGES);
		result.add(OPTION_EXCLUDE_PACKAGES);
		result.add(OPTION_WARN_DOC);
		result.add(OPTION_WARN_DOC_BASE_PACKAGES);
		result.add(OPTION_INCLUDE_MEMBERS_DEFAULT);
		return result;
	}

	@Override
	public Set<String> getSupportedAnnotationTypes() {
		Set<String> result = new TreeSet<>();
		result.add(PUBLICAPI_CLASSNAME);
		result.add(EXCLUDEAPI_CLASSNAME);
		return result;
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latest();
	}

	@Override
	public void init(ProcessingEnvironment processingEnv) {
		filer = processingEnv.getFiler();
		messager = processingEnv.getMessager();
		elements = processingEnv.getElementUtils();
		types = processingEnv.getTypeUtils();

		Map<String, String> procoptions = processingEnv.getOptions();
		String basepackagesopt = procoptions.get(OPTION_BASE_PACKAGES);
		if (basepackagesopt == null) {
			messager.printMessage(Diagnostic.Kind.ERROR, "Option " + OPTION_BASE_PACKAGES + " is missing.");
		} else {
			for (String s : basepackagesopt.split("[ ,]+")) {
				if (!s.isEmpty()) {
					basePackageNames.add(s);
				}
			}
		}
		String excludepackagesopt = procoptions.get(OPTION_EXCLUDE_PACKAGES);
		if (excludepackagesopt != null) {
			for (String s : excludepackagesopt.split("[ ,]+")) {
				if (!s.isEmpty()) {
					excludePackageNames.add(s);
				}
			}
		}
		String warndocoption = procoptions.get(OPTION_WARN_DOC);
		String docwarnpackages = procoptions.get(OPTION_WARN_DOC_BASE_PACKAGES);
		warnNoDocumentation = Boolean.parseBoolean(warndocoption);
		String incmemdefprop = procoptions.get(OPTION_INCLUDE_MEMBERS_DEFAULT);
		if (incmemdefprop != null) {
			defaultIncludeMembers = Boolean.parseBoolean(incmemdefprop);
		}
		if (warnNoDocumentation) {
			if (docwarnpackages == null) {
				docWarnBasePackageNames.addAll(basePackageNames);
			} else {
				for (String s : docwarnpackages.split("[ ,]+")) {
					if (!s.isEmpty()) {
						docWarnBasePackageNames.add(s);
					}
				}
			}
		}
	}

	private static boolean isPublicOrProtected(Element elem) {
		Set<Modifier> modifiers = elem.getModifiers();
		if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)) {
			return true;
		}
		return false;
	}

	private static boolean isStaticFinal(Element elem) {
		Set<Modifier> modifiers = elem.getModifiers();
		return modifiers.contains(Modifier.STATIC) && modifiers.contains(Modifier.FINAL);
	}

	private static boolean isPublic(Element elem) {
		Set<Modifier> modifiers = elem.getModifiers();
		if (modifiers.contains(Modifier.PUBLIC)) {
			return true;
		}
		return false;
	}

	private boolean isInBasePackages(Element elem) {
		return isInPackages(elem, basePackageNames) && !isInPackages(elem, excludePackageNames);
	}

	private boolean isInBasePackages(QualifiedNameable qn) {
		return isInPackages(qn, basePackageNames) && !isInPackages(qn, excludePackageNames);
	}

	private static boolean isInPackages(Element elem, Set<String> basepackagenames) {
		while (elem != null) {
			switch (elem.getKind()) {
				case ANNOTATION_TYPE:
				case CLASS:
				case ENUM:
				case INTERFACE:
				case PACKAGE: {
					return isInPackages((QualifiedNameable) elem, basepackagenames);
				}
				default: {
					elem = elem.getEnclosingElement();
					break;
				}
			}
		}
		return false;
	}

	private static boolean isInPackages(QualifiedNameable qn, Set<String> basepackagenames) {
		String pname = qn.toString();
		for (String bp : basepackagenames) {
			if (pname.equals(bp)) {
				return true;
			}
			if (pname.length() > bp.length() && pname.charAt(bp.length()) == '.' && pname.startsWith(bp)) {
				return true;
			}
		}
		return false;
	}

	private Element getExcludedEnclosingElement(Element elem) {
		for (Element p = elem; (p = p.getEnclosingElement()) != null;) {
			if (excludedAnnotatedElements.contains(p)) {
				return p;
			}
		}
		return null;
	}

	private Element getPublicEnclosingElement(Element elem) {
		for (Element p = elem; (p = p.getEnclosingElement()) != null;) {
			if (publicAnnotatedElements.containsKey(p)) {
				return p;
			}
		}
		return null;
	}

	private Element getClosestPublicEnclosingElement(Element elem) {
		for (Element p = elem; p != null; p = p.getEnclosingElement()) {
			if (publicAnnotatedElements.containsKey(p)) {
				return p;
			}
		}
		return null;
	}

	private boolean hasExcludedEnclosingElement(Element pubelem) {
		return getExcludedEnclosingElement(pubelem) != null;
	}

	private static boolean hasOnlyPrivateOrPackagePrivateConstructors(TypeElement elem) {
		for (Element ee : elem.getEnclosedElements()) {
			if (ee.getKind() != ElementKind.CONSTRUCTOR) {
				continue;
			}
			if (isPublicOrProtected(ee)) {
				return false;
			}
		}
		return true;
	}

	private final class MethodAnnotationDefaultValueAnnotationValueVisitor
			implements AnnotationValueVisitor<Void, AnnotationVisitor> {
		private String name;

		public MethodAnnotationDefaultValueAnnotationValueVisitor(String name) {
			this.name = name;
		}

		@Override
		public Void visit(AnnotationValue av, AnnotationVisitor p) {
			return av.accept(this, p);
		}

		@Override
		public Void visit(AnnotationValue av) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Void visitBoolean(boolean b, AnnotationVisitor p) {
			p.visit(name, b);
			return null;
		}

		@Override
		public Void visitByte(byte b, AnnotationVisitor p) {
			p.visit(name, b);
			return null;
		}

		@Override
		public Void visitChar(char c, AnnotationVisitor p) {
			p.visit(name, c);
			return null;
		}

		@Override
		public Void visitDouble(double d, AnnotationVisitor p) {
			p.visit(name, d);
			return null;
		}

		@Override
		public Void visitFloat(float f, AnnotationVisitor p) {
			p.visit(name, f);
			return null;
		}

		@Override
		public Void visitInt(int i, AnnotationVisitor p) {
			p.visit(name, i);
			return null;
		}

		@Override
		public Void visitLong(long i, AnnotationVisitor p) {
			p.visit(name, i);
			return null;
		}

		@Override
		public Void visitShort(short s, AnnotationVisitor p) {
			p.visit(name, s);
			return null;
		}

		@Override
		public Void visitString(String s, AnnotationVisitor p) {
			p.visit(name, s);
			return null;
		}

		@Override
		public Void visitType(TypeMirror t, AnnotationVisitor p) {
			p.visit(name, Type.getObjectType(getInternalName(t)));
			return null;
		}

		@Override
		public Void visitEnumConstant(VariableElement c, AnnotationVisitor p) {
			TypeMirror ctype = c.asType();
			Name csimplename = c.getSimpleName();
			p.visitEnum(name, getDescriptor(ctype), csimplename.toString());
			return null;
		}

		@Override
		public Void visitAnnotation(AnnotationMirror a, AnnotationVisitor p) {
			AnnotationVisitor subvisitor = p.visitAnnotation(name, getDescriptor(a.getAnnotationType()));
			if (subvisitor != null) {
				for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : a.getElementValues()
						.entrySet()) {
					visitAnnotationVisitor(entry.getKey(), subvisitor, entry.getValue());
				}
				subvisitor.visitEnd();
			}
			return null;
		}

		@Override
		public Void visitArray(List<? extends AnnotationValue> vals, AnnotationVisitor p) {
			AnnotationVisitor elemvisitor = p.visitArray(name);
			if (elemvisitor != null) {
				for (AnnotationValue av : vals) {
					visitAnnotationVisitor(null, elemvisitor, av);
				}
				elemvisitor.visitEnd();
			}
			return null;
		}

		@Override
		public Void visitUnknown(AnnotationValue av, AnnotationVisitor p) {
			throw new UnsupportedOperationException("Unknown annotation value: " + av);
		}
	}

	private static class InclusionState {
		protected final Set<Modifier> memberInclusionModifiers;

		protected boolean enclosingChecked;
		protected boolean membersAdded;

		protected boolean kindChecked;

		protected Collection<Element> dependentElements;

		public InclusionState(Element elem, Collection<? extends Element> dependentElements) {
			this.dependentElements = new HashSet<>(dependentElements);
			Set<Modifier> inclusionmodifiers;
			switch (elem.getKind()) {
				case ANNOTATION_TYPE: {
					inclusionmodifiers = MODIFIERS_PUBLIC;
					break;
				}
				case ENUM: {
					inclusionmodifiers = MODIFIERS_PUBLIC;
					break;
				}
				case INTERFACE: {
					inclusionmodifiers = MODIFIERS_PUBLIC;
					break;
				}
				case CLASS: {
					//TODO do not auto-include static methods from inherited classes
					if (elem.getModifiers().contains(Modifier.FINAL) || !isPublicOrProtected(elem)) {
						//no need to include protected members if the element is final, or private or package private
						inclusionmodifiers = MODIFIERS_PUBLIC;
					} else {
						if (hasOnlyPrivateOrPackagePrivateConstructors((TypeElement) elem)) {
							//the class doesn't have externally visible constructor, therefore not subclassable.
							//do not auto include protected elements
							inclusionmodifiers = MODIFIERS_PUBLIC;
						} else {
							inclusionmodifiers = MODIFIERS_PUBLIC_PROTECTED;
						}
					}
					break;
				}
				case PACKAGE: {
					inclusionmodifiers = MODIFIERS_PUBLIC;
					break;
				}
				case CONSTRUCTOR:
				case ENUM_CONSTANT:
				case EXCEPTION_PARAMETER:
				case FIELD:
				case INSTANCE_INIT:
				case LOCAL_VARIABLE:
				case METHOD:
				case OTHER:
				case PARAMETER:
				case RESOURCE_VARIABLE:
				case STATIC_INIT:
				case TYPE_PARAMETER:
				default: {
					inclusionmodifiers = Collections.emptySet();
					break;
				}
			}
			this.memberInclusionModifiers = inclusionmodifiers;
		}

		public InclusionState(Element elem) {
			this(elem, Collections.singletonList(elem));
		}

		public boolean shouldIncludeMember(Element elem) {
			Set<Modifier> elemmods = elem.getModifiers();
			for (Modifier mim : memberInclusionModifiers) {
				if (elemmods.contains(mim)) {
					return true;
				}
			}
			return false;
		}
	}

	private static final Set<Modifier> MODIFIERS_PUBLIC = Collections.singleton(Modifier.PUBLIC);
	private static final Set<Modifier> MODIFIERS_PUBLIC_PROTECTED = EnumSet.of(Modifier.PUBLIC, Modifier.PROTECTED);

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (publicApiType == null) {
			publicApiType = elements.getTypeElement(PUBLICAPI_CLASSNAME);
			excludeApiType = elements.getTypeElement(EXCLUDEAPI_CLASSNAME);
		}
		{
			Set<? extends Element> publicelems = roundEnv.getElementsAnnotatedWith(publicApiType);
			Set<? extends Element> excludeelems = roundEnv.getElementsAnnotatedWith(excludeApiType);
			excludedAnnotatedElements.addAll(excludeelems);

			for (Element pubelem : publicelems) {
				if (!isInBasePackages(pubelem)) {
					messager.printMessage(Diagnostic.Kind.WARNING, "Element is not in base packages, not tracked.",
							pubelem);
					continue;
				}
				ElementKind elemkind = pubelem.getKind();
				switch (elemkind) {
					case ANNOTATION_TYPE:
					case CLASS:
					case ENUM:
					case INTERFACE:
					case PACKAGE: {
						QualifiedNameable qn = (QualifiedNameable) pubelem;
						if (!isInBasePackages(qn)) {
							continue;
						}
						break;
					}

					default: {
						break;
					}
				}
				publicAnnotatedElements.put(pubelem, new InclusionState(pubelem));
				if (excludeelems.contains(pubelem)) {
					messager.printMessage(Diagnostic.Kind.ERROR,
							"Conflicting annotations with " + EXCLUDEAPI_CLASSNAME + " and " + PUBLICAPI_CLASSNAME,
							pubelem);
				}
			}
		}
		if (roundEnv.processingOver()) {
			publicAnnotatedElements = Collections.unmodifiableMap(publicAnnotatedElements);
			excludedAnnotatedElements = Collections.unmodifiableSet(excludedAnnotatedElements);
			for (Element pubelem : publicAnnotatedElements.keySet()) {
				Element excenclosing = getExcludedEnclosingElement(pubelem);
				if (excenclosing != null) {
					messager.printMessage(Diagnostic.Kind.ERROR,
							"Public API element has an excluded enclosing source entity: " + excenclosing, pubelem);
				}
			}
			if (roundEnv.errorRaised()) {
				return false;
			}
			Map<Element, InclusionState> allpublicelements = new HashMap<>(publicAnnotatedElements);
			for (Entry<Element, InclusionState> entry : publicAnnotatedElements.entrySet()) {
				LinkedList<Element> depstack = new LinkedList<>();
				depstack.add(entry.getKey());
				addRelatedElements(entry.getKey(), entry.getValue(), allpublicelements, depstack);
			}

			allpublicelements.keySet().removeAll(excludedAnnotatedElements);
			if (roundEnv.errorRaised()) {
				return false;
			}
			warnMissingDocumentations(allpublicelements);
			generate(allpublicelements);
		}
		return false;
	}

	@Override
	public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
			ExecutableElement member, String userText) {
		return Collections.emptyList();
	}

	private static String constructUndocumentedMessage(Element elem) {
		ElementKind ek = elem.getKind();
		switch (ek) {
			case PACKAGE:
				return "Undocumented public API package: " + ((QualifiedNameable) elem).getQualifiedName();
			case ANNOTATION_TYPE:
				return "Undocumented public API annotation type: " + ((QualifiedNameable) elem).getQualifiedName();
			case CLASS:
				return "Undocumented public API class: " + ((QualifiedNameable) elem).getQualifiedName();
			case ENUM:
				return "Undocumented public API enum: " + ((QualifiedNameable) elem).getQualifiedName();
			case INTERFACE:
				return "Undocumented public API interface: " + ((QualifiedNameable) elem).getQualifiedName();

			case CONSTRUCTOR: {
				Element enclosing = elem.getEnclosingElement();
				return "Undocumented public API constructor: " + ((QualifiedNameable) enclosing).getQualifiedName()
						+ "." + enclosing.getSimpleName();
			}
			case METHOD:
				return "Undocumented public API method: "
						+ ((QualifiedNameable) elem.getEnclosingElement()).getQualifiedName() + "."
						+ elem.getSimpleName();

			case ENUM_CONSTANT:
				return "Undocumented public API enum constant: "
						+ ((QualifiedNameable) elem.getEnclosingElement()).getQualifiedName() + "."
						+ elem.getSimpleName();
			case FIELD:
				return "Undocumented public API field: "
						+ ((QualifiedNameable) elem.getEnclosingElement()).getQualifiedName() + "."
						+ elem.getSimpleName();
			default: {
				throw new IllegalArgumentException(ek.toString());
			}
		}
	}

	private void warnMissingDocumentations(Map<Element, InclusionState> allpublicelements) {
		if (warnNoDocumentation) {
			for (Element pubelem : allpublicelements.keySet()) {
				if (!isInPackages(pubelem, docWarnBasePackageNames)) {
					//do not warn for no doc warn packages
					continue;
				}
				if (elements.isDeprecated(pubelem)) {
					//do warn missing doc for deprecated elements
					continue;
				}
				ElementKind ek = pubelem.getKind();
				if (ek == ElementKind.FIELD) {
					if (pubelem.getSimpleName().contentEquals("serialVersionUID") && isStaticFinal(pubelem)
							&& pubelem.asType().getKind() == TypeKind.LONG) {
						//ignore serial version id
						continue;
					}
				}
				if (isTypeElementKind(ek) || ek.isField() || ek == ElementKind.PACKAGE) {
					if (elements.getDocComment(pubelem) == null) {
						messager.printMessage(Diagnostic.Kind.WARNING, constructUndocumentedMessage(pubelem), pubelem);
					}
				} else if (isExecutableElementKind(ek)) {
					if (ek == ElementKind.CONSTRUCTOR) {
						if (pubelem.getEnclosingElement().getKind() == ElementKind.ENUM) {
							//do not warn for non existent documentation for enumeration constructors
							continue;
						}
					} else {
						if (pubelem.getEnclosingElement().getKind() == ElementKind.ENUM) {
							//valueOf and values should not be warned
							switch (pubelem.getSimpleName().toString()) {
								case "values": {
									ExecutableElement ee = (ExecutableElement) pubelem;
									if (ee.getParameters().isEmpty()) {
										//do not warn for implicitly declared values()
										continue;
									}
									break;
								}
								case "valueOf": {
									ExecutableElement ee = (ExecutableElement) pubelem;
									List<? extends VariableElement> params = ee.getParameters();
									if (params.size() == 1) {
										VariableElement p = params.get(0);
										TypeMirror ptype = p.asType();
										if (ptype.getKind() == TypeKind.DECLARED
												&& ((TypeElement) ((DeclaredType) ptype).asElement()).getQualifiedName()
														.contentEquals("java.lang.String")) {
											//do not warn for implicitly declared valueOf(String)
											continue;
										}
									}
									break;
								}
								default: {
									break;
								}
							}

						}
					}
					if (pubelem.getAnnotation(Override.class) == null) {
						//TODO take overriding into account
						if (elements.getDocComment(pubelem) == null) {
							messager.printMessage(Diagnostic.Kind.WARNING, constructUndocumentedMessage(pubelem),
									pubelem);
						}
					}
				}
			}
		}
	}

	private static TypeElement getTypeElementFromMirror(TypeMirror tm) {
		if (tm == null) {
			return null;
		}
		if (tm.getKind() != TypeKind.DECLARED) {
			return null;
		}
		return (TypeElement) ((DeclaredType) tm).asElement();
	}

	private void generate(Map<Element, InclusionState> allpublicelements) {
		Location outloc = StandardLocation.locationFor("API_OUTPUT");
		for (Entry<Element, InclusionState> entry : allpublicelements.entrySet()) {
			Element pubelem = entry.getKey();
			ElementKind kind = pubelem.getKind();
			switch (kind) {
				case ANNOTATION_TYPE:
				case CLASS:
				case INTERFACE:
				case ENUM: {
					TypeElement type = (TypeElement) pubelem;
					String binaryname = elements.getBinaryName(type).toString();
					int packidx = binaryname.lastIndexOf('.');
					try {
						FileObject res = filer.createResource(outloc,
								packidx < 0 ? "" : binaryname.substring(0, packidx),
								binaryname.substring(packidx + 1) + ".class",
								entry.getValue().dependentElements.toArray(EMPTY_ELEMENT_ARRAY));
						ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
						String internalname = binaryname.replace('.', '/');
						String supercinternalname = getSuperClassInternalName(type);
						String cgenericsignature = getGenericSignature(type);
						String[] itfs = getInterfaceInternalNames(type);
						//XXX set appropriate version code when default methods, static interface methods, etc... are used
						int version = Opcodes.V1_8;
						cw.visit(version, getClassModifierAccessOpcode(type), internalname, cgenericsignature,
								supercinternalname, itfs);

						visitClassAnnotations(pubelem, cw);

						writeInnerClassAttributes(cw, type);
						writeInnerClassAttributes(cw, getTypeElementFromMirror(type.getSuperclass()));
						for (TypeMirror itf : type.getInterfaces()) {
							writeInnerClassAttributes(cw, getTypeElementFromMirror(itf));
						}
						//TODO should we visit inner class information about occurring types (e.g. field type, method return, argument types)

						for (Element enclosed : type.getEnclosedElements()) {
							if (!allpublicelements.containsKey(enclosed)) {
								continue;
							}
							ElementKind ek = enclosed.getKind();
							switch (ek) {
								case INTERFACE:
								case CLASS:
								case ENUM:
								case ANNOTATION_TYPE: {
									TypeElement te = (TypeElement) enclosed;
									int access = getInnerClassModifierAccessOpcode(te);
									cw.visitInnerClass(getInternalName(te), internalname, te.getSimpleName().toString(),
											access);
									break;
								}
								case CONSTRUCTOR:
								case METHOD: {
									ExecutableElement ee = (ExecutableElement) enclosed;
									String mname = ek == ElementKind.CONSTRUCTOR ? "<init>"
											: ee.getSimpleName().toString();
									String[] exceptions;
									List<? extends TypeMirror> throwns = ee.getThrownTypes();
									if (throwns.isEmpty()) {
										exceptions = null;
									} else {
										exceptions = new String[throwns.size()];
										for (int i = 0; i < exceptions.length; i++) {
											TypeMirror throwntm = throwns.get(i);
											exceptions[i] = getInternalName(throwntm);
										}
									}
									List<TypeElement> implicitparameters = getImplicitInnerClassConstructorParameters(
											ee);
									MethodVisitor mw = cw.visitMethod(getMethodModifierAccessOpcode(type, ee), mname,
											getDescriptor(ee, implicitparameters),
											getGenericSignature(ee, implicitparameters), exceptions);
									for (VariableElement pe : ee.getParameters()) {
										mw.visitParameter(pe.getSimpleName().toString(),
												getParameterModifierAccessOpcode(type, ee, pe));
									}
									visitMethodAnnotations(ee, mw);

									AnnotationValue defval = ee.getDefaultValue();
									if (defval != null) {
										visitMethodAnnotationDefaultValue(ee, mw, defval);
									}
									Set<Modifier> emods = ee.getModifiers();
									if (!emods.contains(Modifier.ABSTRACT)) {
										mw.visitCode();
										mw.visitTypeInsn(Opcodes.NEW,
												Type.getInternalName(UnsupportedOperationException.class));
										mw.visitInsn(Opcodes.DUP);
										mw.visitLdcInsn("API only.");
										mw.visitMethodInsn(Opcodes.INVOKESPECIAL,
												Type.getInternalName(UnsupportedOperationException.class), "<init>",
												"(Ljava/lang/String;)V", false);
										mw.visitInsn(Opcodes.ATHROW);
										mw.visitMaxs(0, 0);
									}
									mw.visitEnd();
									break;
								}
								case ENUM_CONSTANT: {
									VariableElement ve = (VariableElement) enclosed;
									FieldVisitor fw = cw.visitField(getFieldModifierAccessOpcode(type, ve),
											ve.getSimpleName().toString(), "L" + internalname + ";", null, null);
									visitFieldAnnotations(ve, fw);
									fw.visitEnd();
									break;
								}
								case FIELD: {
									VariableElement ve = (VariableElement) enclosed;
									String fieldsignature = getGenericSignature(ve);
									Object fieldvalue = ve.getConstantValue();

									int modifiers = getFieldModifierAccessOpcode(type, ve);
									if (fieldvalue != null && ((modifiers
											& (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC
													| Opcodes.ACC_FINAL))) {
										//check if we need to unfinalize the constant
										PublicApi pubapi = ve.getAnnotation(PublicApi.class);
										if (pubapi != null) {
											DefaultableBoolean unconst = pubapi.unconstantize();
											if (unconst == DefaultableBoolean.TRUE) {
												//just don't set the constant value, it can remain final
												fieldvalue = null;
											}
										}
									}
									FieldVisitor fw = cw.visitField(modifiers, ve.getSimpleName().toString(),
											getDescriptor(ve.asType()), fieldsignature,
											toConstantValueWithType(fieldvalue, ve.asType()));
									visitFieldAnnotations(ve, fw);
									fw.visitEnd();
									break;
								}
								default: {
									break;
								}
							}
						}

						cw.visitEnd();
						byte[] cbytes = cw.toByteArray();
						try (OutputStream os = res.openOutputStream()) {
							os.write(cbytes);
						}
					} catch (IOException e) {
						throw new UncheckedIOException("Failed to write: " + binaryname, e);
					}
					break;
				}
				default: {
					break;
				}
			}
		}
	}

	private static RetentionPolicy getAnnotationRetentionPolicy(TypeElement annotationelement) {
		Retention retention = annotationelement.getAnnotation(Retention.class);
		RetentionPolicy retpolicy = retention == null ? RetentionPolicy.CLASS : retention.value();
		return retpolicy;
	}

	private void visitClassAnnotations(AnnotatedConstruct pubelem, ClassVisitor visitor) {
		for (AnnotationMirror am : pubelem.getAnnotationMirrors()) {
			DeclaredType amtype = am.getAnnotationType();
			if (amtype == null) {
				continue;
			}
			TypeElement amelem = (TypeElement) amtype.asElement();
			RetentionPolicy retpolicy = getAnnotationRetentionPolicy(amelem);
			if (retpolicy == RetentionPolicy.SOURCE) {
				continue;
			}
			boolean visible = retpolicy == RetentionPolicy.RUNTIME;
			AnnotationVisitor av = visitor.visitAnnotation(getDescriptor(amelem.asType()), visible);
			if (av != null) {
				for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am.getElementValues()
						.entrySet()) {
					visitAnnotationVisitor(entry.getKey(), av, entry.getValue());
				}
				av.visitEnd();
			}
		}
	}

	private void visitFieldAnnotations(AnnotatedConstruct pubelem, FieldVisitor visitor) {
		for (AnnotationMirror am : pubelem.getAnnotationMirrors()) {
			DeclaredType amtype = am.getAnnotationType();
			if (amtype == null) {
				continue;
			}
			TypeElement amelem = (TypeElement) amtype.asElement();
			RetentionPolicy retpolicy = getAnnotationRetentionPolicy(amelem);
			if (retpolicy == RetentionPolicy.SOURCE) {
				continue;
			}
			boolean visible = retpolicy == RetentionPolicy.RUNTIME;
			AnnotationVisitor av = visitor.visitAnnotation(getDescriptor(amelem.asType()), visible);
			if (av != null) {
				for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am.getElementValues()
						.entrySet()) {
					visitAnnotationVisitor(entry.getKey(), av, entry.getValue());
				}
				av.visitEnd();
			}
		}
	}

	private void visitMethodAnnotations(AnnotatedConstruct pubelem, MethodVisitor visitor) {
		for (AnnotationMirror am : pubelem.getAnnotationMirrors()) {
			DeclaredType amtype = am.getAnnotationType();
			if (amtype == null) {
				continue;
			}
			TypeElement amelem = (TypeElement) amtype.asElement();
			RetentionPolicy retpolicy = getAnnotationRetentionPolicy(amelem);
			if (retpolicy == RetentionPolicy.SOURCE) {
				continue;
			}
			boolean visible = retpolicy == RetentionPolicy.RUNTIME;
			AnnotationVisitor av = visitor.visitAnnotation(getDescriptor(amelem.asType()), visible);
			if (av != null) {
				for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am.getElementValues()
						.entrySet()) {
					visitAnnotationVisitor(entry.getKey(), av, entry.getValue());
				}
				av.visitEnd();
			}
		}
	}

	private void visitMethodAnnotationDefaultValue(ExecutableElement ee, MethodVisitor mw, AnnotationValue defval) {
		AnnotationVisitor defvisitor = mw.visitAnnotationDefault();
		new MethodAnnotationDefaultValueAnnotationValueVisitor(null).visit(defval, defvisitor);
		defvisitor.visitEnd();
	}

	private void visitAnnotationVisitor(ExecutableElement ee, AnnotationVisitor av, AnnotationValue value) {
		new MethodAnnotationDefaultValueAnnotationValueVisitor(ee == null ? null : ee.getSimpleName().toString())
				.visit(value, av);
	}

	private String getSuperClassInternalName(TypeElement type) {
		ElementKind typekind = type.getKind();
		if (typekind == ElementKind.INTERFACE || typekind == ElementKind.ANNOTATION_TYPE) {
			return "java/lang/Object";
		}
		TypeMirror supertm = type.getSuperclass();
		String supercinternalname = supertm.getKind() == TypeKind.NONE ? null : getDeclaredTypeInternalName(supertm);
		return supercinternalname;
	}

	private static Object toConstantValueWithType(Object val, TypeMirror type) {
		if (val == null) {
			return null;
		}
		switch (type.getKind()) {
			case BYTE: {
				if (val instanceof Number) {
					return ((Number) val).byteValue();
				}
				break;
			}
			case SHORT: {
				if (val instanceof Number) {
					return ((Number) val).shortValue();
				}
				break;
			}
			case INT: {
				if (val instanceof Number) {
					return ((Number) val).intValue();
				}
				break;
			}
			case LONG: {
				if (val instanceof Number) {
					return ((Number) val).longValue();
				}
				break;
			}
			case FLOAT: {
				if (val instanceof Number) {
					return ((Number) val).floatValue();
				}
				break;
			}
			case DOUBLE: {
				if (val instanceof Number) {
					return ((Number) val).doubleValue();
				}
				break;
			}
			case BOOLEAN: {
				break;
			}
			case CHAR: {
				if (val instanceof Number) {
					return (char) ((Number) val).shortValue();
				}
				break;
			}
			default: {
				break;
			}
		}
		return val;
	}

	private static boolean isTypeElementKind(ElementKind e) {
		return e.isClass() || e.isInterface();
	}

	private static boolean isExecutableElementKind(ElementKind e) {
		return e == ElementKind.CONSTRUCTOR || e == ElementKind.METHOD;
	}

	private boolean shouldIncludeMembers(PublicApi pubannot) {
		DefaultableBoolean im = pubannot.includeMembers();
		return im == DefaultableBoolean.TRUE || (im == DefaultableBoolean.DEFAULT && defaultIncludeMembers);
	}

	private void addRelatedElements(Element e, Map<Element, InclusionState> states,
			LinkedList<Element> dependentstack) {
		if (excludedAnnotatedElements.contains(e)) {
			return;
		}
		if (!isInBasePackages(e)) {
			return;
		}
		dependentstack.addLast(e);
		addRelatedElements(e, states.compute(e, getInclusionStateRemappingFunction(dependentstack)), states,
				dependentstack);
		dependentstack.removeLast();
	}

	private static BiFunction<? super Element, ? super InclusionState, ? extends InclusionState> getInclusionStateRemappingFunction(
			LinkedList<Element> dependentstack) {
		BiFunction<? super Element, ? super InclusionState, ? extends InclusionState> remappingFunction = (k, v) -> {
			if (v == null) {
				return new InclusionState(k, dependentstack);
			}
			v.dependentElements.addAll(dependentstack);
			return v;
		};
		return remappingFunction;
	}

	private void addRelatedElements(Element e, InclusionState incstate, Map<Element, InclusionState> states,
			LinkedList<Element> dependentstack) {
		if (!incstate.enclosingChecked) {
			incstate.enclosingChecked = true;
			Element enclosing = e.getEnclosingElement();
			if (enclosing != null && isTypeElementKind(enclosing.getKind())) {
				if (!excludedAnnotatedElements.contains(enclosing)) {
					states.compute(enclosing, getInclusionStateRemappingFunction(dependentstack));
				}
			}
		}
		switch (e.getKind()) {
			case PACKAGE: {
				PackageElement pe = (PackageElement) e;
				if (!incstate.membersAdded) {
					PublicApi pubannot = pe.getAnnotation(PublicApi.class);
					incstate.membersAdded = true;
					if (pubannot != null && shouldIncludeMembers(pubannot)) {
						for (Element encelem : pe.getEnclosedElements()) {
							if (incstate.shouldIncludeMember(encelem)) {
								addRelatedElements(encelem, states, dependentstack);
							}
						}
					}
				}
				break;
			}

			case ANNOTATION_TYPE:
			case CLASS:
			case INTERFACE:
			case ENUM: {
				TypeElement te = (TypeElement) e;
				if (!incstate.kindChecked) {
					incstate.kindChecked = true;
					addRelatedElements(te.getSuperclass(), states, dependentstack);
					for (TypeMirror itf : te.getInterfaces()) {
						addRelatedElements(itf, states, dependentstack);
					}
					for (TypeParameterElement tpe : te.getTypeParameters()) {
						addRelatedElements(tpe, states, dependentstack);
					}
				}
				if (!incstate.membersAdded) {
					incstate.membersAdded = true;

					Boolean shouldincludemembers = null;
					Element closestpub = getClosestPublicEnclosingElement(te);
					if (closestpub != null) {
						PublicApi pubannot = closestpub.getAnnotation(PublicApi.class);
						shouldincludemembers = shouldIncludeMembers(pubannot);
					}
					if (shouldincludemembers == Boolean.TRUE) {
						//include the types as well
						for (Element encelem : te.getEnclosedElements()) {
							if (incstate.shouldIncludeMember(encelem)) {
								addRelatedElements(encelem, states, dependentstack);
							}
						}
					} else if (shouldincludemembers == null) {
						//member inclusion was not specified, do not auto-include the types
						for (Element encelem : te.getEnclosedElements()) {
							if (isTypeElementKind(encelem.getKind())) {
								continue;
							}
							if (incstate.shouldIncludeMember(encelem)) {
								addRelatedElements(encelem, states, dependentstack);
							}
						}
					}
					//else do not include the members at all
				}
				break;
			}
			case CONSTRUCTOR:
			case METHOD: {
				ExecutableElement ee = (ExecutableElement) e;
				if (!incstate.kindChecked) {
					incstate.kindChecked = true;
					for (VariableElement ve : ee.getParameters()) {
						addRelatedElements(ve, states, dependentstack);
					}
					addRelatedElements(ee.getReturnType(), states, dependentstack);
					for (TypeMirror tt : ee.getThrownTypes()) {
						addRelatedElements(tt, states, dependentstack);
					}
					for (TypeParameterElement tpe : ee.getTypeParameters()) {
						addRelatedElements(tpe, states, dependentstack);
					}
				}
				break;
			}
			case ENUM_CONSTANT:
			case FIELD:
			case PARAMETER: {
				VariableElement ve = (VariableElement) e;
				if (!incstate.kindChecked) {
					incstate.kindChecked = true;
					addRelatedElements(ve.asType(), states, dependentstack);
				}
				break;
			}
			case TYPE_PARAMETER: {
				TypeParameterElement tpe = (TypeParameterElement) e;
				if (!incstate.kindChecked) {
					incstate.kindChecked = true;
					for (TypeMirror b : tpe.getBounds()) {
						addRelatedElements(b, states, dependentstack);
					}
				}
				break;
			}
			case EXCEPTION_PARAMETER:
			case INSTANCE_INIT:
			case LOCAL_VARIABLE:
			case OTHER:
			case RESOURCE_VARIABLE:
			case STATIC_INIT: {
				//non-API elements
				break;
			}
			default: {
				break;
			}
		}
	}

	private void addRelatedElements(TypeMirror tm, Map<Element, InclusionState> states,
			LinkedList<Element> dependentstack) {
		if (tm == null) {
			return;
		}
		TypeKind tmkind = tm.getKind();
		switch (tmkind) {
			case ARRAY: {
				ArrayType at = (ArrayType) tm;
				addRelatedElements(at.getComponentType(), states, dependentstack);
				break;
			}
			case DECLARED: {
				DeclaredType dt = (DeclaredType) tm;
				TypeElement elem = (TypeElement) dt.asElement();
				addRelatedElements(elem, states, dependentstack);
				addRelatedElements(dt.getEnclosingType(), states, dependentstack);
				for (TypeMirror ta : dt.getTypeArguments()) {
					addRelatedElements(ta, states, dependentstack);
				}
				break;
			}
			case INTERSECTION: {
				IntersectionType it = (IntersectionType) tm;
				for (TypeMirror itm : it.getBounds()) {
					addRelatedElements(itm, states, dependentstack);
				}
				break;
			}
			case TYPEVAR: {
				TypeVariable tv = (TypeVariable) tm;
				TypeParameterElement elem = (TypeParameterElement) tv.asElement();
				addRelatedElements(elem, states, dependentstack);
				break;
			}
			case UNION: {
				UnionType ut = (UnionType) tm;
				for (TypeMirror alt : ut.getAlternatives()) {
					addRelatedElements(alt, states, dependentstack);
				}
				break;
			}
			case WILDCARD: {
				WildcardType wt = (WildcardType) tm;
				addRelatedElements(wt.getExtendsBound(), states, dependentstack);
				addRelatedElements(wt.getSuperBound(), states, dependentstack);
				break;
			}
			case EXECUTABLE: {
				throw new IllegalArgumentException(tmkind.toString());
			}

			case ERROR:
			case PACKAGE:
			case NONE:
			case NULL:
			case OTHER:
			case VOID:
			case BOOLEAN:
			case BYTE:
			case CHAR:
			case DOUBLE:
			case FLOAT:
			case INT:
			case LONG:
			case SHORT:
			default: {
				break;
			}
		}
	}

	private static List<TypeElement> getImplicitInnerClassConstructorParameters(ExecutableElement ee) {
		if (ee.getKind() != ElementKind.CONSTRUCTOR) {
			return Collections.emptyList();
		}
		TypeElement type = (TypeElement) ee.getEnclosingElement();
		if (type.getKind() != ElementKind.CLASS) {
			//only add enclosing ref for class constructors
			return Collections.emptyList();
		}

		Element enclosingelement = type.getEnclosingElement();
		if (enclosingelement == null || enclosingelement.getKind() != ElementKind.CLASS
				|| type.getModifiers().contains(Modifier.STATIC)) {
			return Collections.emptyList();
		}
		return Collections.singletonList((TypeElement) enclosingelement);
	}

	private String getGenericSignature(VariableElement ve) {
		if (!isGeneric(ve)) {
			return null;
		}
		return createSignature(ve.asType());
	}

	private String createSignature(TypeMirror type) {
		SignatureWriter writer = new SignatureWriter();
		appendSignature(type, writer);
		return writer.toString();
	}

	private void appendSignature(TypeMirror type, SignatureVisitor writer) {
		TypeKind kind = type.getKind();
		switch (kind) {
			case ARRAY: {
				ArrayType at = (ArrayType) type;
				writer = writer.visitArrayType();
				appendSignature(at.getComponentType(), writer);
				return;
			}
			case DECLARED: {
				DeclaredType dt = (DeclaredType) type;
				appendDeclaredTypeSignature(writer, dt);
				return;
			}
			case TYPEVAR: {
				TypeVariable tv = (TypeVariable) type;
				writer.visitTypeVariable(tv.asElement().getSimpleName().toString());
				return;
			}
			case WILDCARD: {
				WildcardType wt = (WildcardType) type;
				appendWildcardSignature(writer, wt);
				return;
			}
			case BOOLEAN: {
				writer.visitBaseType('Z');
				return;
			}
			case CHAR: {
				writer.visitBaseType('C');
				return;
			}
			case BYTE: {
				writer.visitBaseType('B');
				return;
			}
			case SHORT: {
				writer.visitBaseType('S');
				return;
			}
			case INT: {
				writer.visitBaseType('I');
				return;
			}
			case LONG: {
				writer.visitBaseType('J');
				return;
			}
			case FLOAT: {
				writer.visitBaseType('F');
				return;
			}
			case DOUBLE: {
				writer.visitBaseType('D');
				return;
			}
			case VOID: {
				writer.visitBaseType('V');
				return;
			}

			case INTERSECTION:
			case UNION:

			case PACKAGE:
			case ERROR:
			case NONE:
			case NULL:
			case OTHER:
			case EXECUTABLE:
			default: {
				throw new IllegalArgumentException(kind.toString());
			}
		}
	}

	private void appendWildcardSignature(SignatureVisitor writer, WildcardType wt) {
		TypeMirror eb = wt.getExtendsBound();
		TypeMirror sb = wt.getSuperBound();
		if (eb != null) {
			writer.visitTypeArgument('+');
			appendSignature(eb, writer);
		} else if (sb != null) {
			writer.visitTypeArgument('-');
			appendSignature(sb, writer);
		} else {
			//unbounded wildcard
			writer.visitTypeArgument();
		}
	}

	private void appendFormalParameter(SignatureVisitor writer, TypeParameterElement tpelem) {
		String formalname = tpelem.getSimpleName().toString();
		List<? extends TypeMirror> formalbounds = tpelem.getBounds();
		writer.visitFormalTypeParameter(formalname);

		Iterator<? extends TypeMirror> it = formalbounds.iterator();
		if (it.hasNext()) {
			//only add the first bound as the class bound if it is an interface
			{
				TypeMirror firstbound = it.next();
				TypeKind firstkind = firstbound.getKind();
				SignatureVisitor firstwriter;
				if ((firstkind == TypeKind.DECLARED && ((DeclaredType) firstbound).asElement().getKind().isClass())
						|| firstkind == TypeKind.TYPEVAR) {
					firstwriter = writer.visitClassBound();
				} else {
					firstwriter = writer.visitInterfaceBound();
				}
				appendSignature(firstbound, firstwriter);
			}
			while (it.hasNext()) {
				TypeMirror bound = it.next();
				SignatureVisitor itfwriter = writer.visitInterfaceBound();
				appendSignature(bound, itfwriter);
			}
		}
	}

	private void appendDeclaredTypeSignatureImpl(SignatureVisitor writer, DeclaredType dt, boolean callend) {
		TypeMirror enctype = dt.getEnclosingType();
		TypeElement dtelem = (TypeElement) dt.asElement();
		String internalname = getInternalName(dtelem);
		if (enctype.getKind() != TypeKind.NONE) {
			appendDeclaredTypeSignatureImpl(writer, (DeclaredType) enctype, false);
			writer.visitInnerClassType(dtelem.getSimpleName().toString());
		} else {
			writer.visitClassType(internalname);
		}
		List<? extends TypeMirror> targs = dt.getTypeArguments();
		if (!targs.isEmpty()) {
			for (TypeMirror tatm : targs) {
				if (tatm.getKind() == TypeKind.WILDCARD) {
					WildcardType wt = (WildcardType) tatm;
					appendWildcardSignature(writer, wt);
				} else {
					writer.visitTypeArgument('=');
					appendSignature(tatm, writer);
				}
			}
		}
		if (callend) {
			writer.visitEnd();
		}
	}

	private void appendDeclaredTypeSignature(SignatureVisitor writer, DeclaredType dt) {
		appendDeclaredTypeSignatureImpl(writer, dt, true);
	}

	private String getGenericSignature(TypeElement type) {
		if (!isGeneric(type)) {
			return null;
		}
		SignatureWriter writer = new SignatureWriter();
		for (TypeParameterElement tpe : type.getTypeParameters()) {
			appendFormalParameter(writer, tpe);
		}
		SignatureVisitor scwriter = writer.visitSuperclass();
		TypeMirror superc = type.getSuperclass();
		if (superc.getKind() == TypeKind.NONE) {
			scwriter.visitClassType("java/lang/Object");
			scwriter.visitEnd();
		} else {
			appendSignature(superc, scwriter);
		}
		for (TypeMirror itf : type.getInterfaces()) {
			SignatureVisitor itfwriter = writer.visitInterface();
			appendSignature(itf, itfwriter);
		}
		return writer.toString();
	}

	private String getGenericSignature(ExecutableElement ee, List<TypeElement> implicitparameters) {
		if (!isGeneric(ee)) {
			return null;
		}
		SignatureWriter writer = new SignatureWriter();
		for (TypeParameterElement tpe : ee.getTypeParameters()) {
			appendFormalParameter(writer, tpe);
		}
		SignatureVisitor paramswriter = writer.visitParameterType();
		for (TypeElement impp : implicitparameters) {
			appendSignature(impp.asType(), paramswriter);
		}
		for (VariableElement pve : ee.getParameters()) {
			appendSignature(pve.asType(), paramswriter);
		}
		SignatureVisitor returnwriter = writer.visitReturnType();
		appendSignature(ee.getReturnType(), returnwriter);
		for (TypeMirror throwtm : ee.getThrownTypes()) {
			if (throwtm.getKind() != TypeKind.TYPEVAR) {
				continue;
			}
			SignatureVisitor throwwriter = writer.visitExceptionType();
			appendSignature(throwtm, throwwriter);
		}
		return writer.toString();
	}

	private String[] getInterfaceInternalNames(TypeElement type) {
		List<? extends TypeMirror> interfaces = type.getInterfaces();
		if (interfaces.isEmpty()) {
			return null;
		}
		String[] itfs = new String[interfaces.size()];
		for (int i = 0; i < itfs.length; i++) {
			TypeMirror itftm = interfaces.get(i);
			itfs[i] = getDeclaredTypeInternalName(itftm);
		}
		return itfs;
	}

	private String getDeclaredTypeInternalName(TypeMirror itftm) {
		DeclaredType dt = (DeclaredType) itftm;
		TypeElement e = (TypeElement) dt.asElement();
		String intname = getInternalName(e);
		return intname;
	}

	private static boolean isGeneric(VariableElement ve) {
		TypeMirror t = ve.asType();
		return isGeneric(t);
	}

	private static boolean isGeneric(TypeMirror t) {
		TypeKind kind = t.getKind();
		switch (kind) {
			case TYPEVAR: {
				return true;
			}
			case DECLARED: {
				if (!((DeclaredType) t).getTypeArguments().isEmpty()) {
					return true;
				}
				return false;
			}
			case ARRAY: {
				return isGeneric(((ArrayType) t).getComponentType());
			}
			case BOOLEAN:
			case BYTE:
			case CHAR:
			case DOUBLE:
			case FLOAT:
			case INT:
			case LONG:
			case VOID:
			case SHORT: {
				return false;
			}
			case INTERSECTION:
			case UNION:
			case WILDCARD: {
				return true;
			}
			default: {
				throw new IllegalArgumentException(kind.toString());
			}
		}
	}

	private static boolean isGeneric(ExecutableElement ee) {
		if (!ee.getTypeParameters().isEmpty()) {
			return true;
		}
		for (VariableElement pelem : ee.getParameters()) {
			TypeMirror ptm = pelem.asType();
			if (isGeneric(ptm)) {
				return true;
			}
		}
		TypeMirror rt = ee.getReturnType();
		if (isGeneric(rt)) {
			return true;
		}
		for (TypeMirror tt : ee.getThrownTypes()) {
			if (isGeneric(tt)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isGeneric(TypeElement ee) {
		if (!ee.getTypeParameters().isEmpty()) {
			return true;
		}
		for (TypeMirror itf : ee.getInterfaces()) {
			if (isGeneric(itf)) {
				return true;
			}
		}
		TypeMirror sc = ee.getSuperclass();
		if (sc.getKind() == TypeKind.DECLARED) {
			if (isGeneric(sc)) {
				return true;
			}
		}
		return false;
	}

	private String getDescriptor(ExecutableElement ee, List<TypeElement> implicitparameters) {
		List<? extends VariableElement> params = ee.getParameters();
		StringBuilder sb = new StringBuilder();
		sb.append('(');
		for (TypeElement impp : implicitparameters) {
			sb.append(getDescriptor(impp.asType()));
		}
		for (VariableElement ptm : params) {
			sb.append(getDescriptor(ptm.asType()));
		}
		sb.append(')');
		sb.append(getDescriptor(ee.getReturnType()));
		return sb.toString();
	}

	private String getDescriptor(TypeMirror tm) {
		TypeKind kind = tm.getKind();
		switch (kind) {
			case ARRAY: {
				return "[" + getDescriptor(((ArrayType) tm).getComponentType());
			}
			case BOOLEAN: {
				return "Z";
			}
			case BYTE: {
				return "B";
			}
			case CHAR: {
				return "C";
			}
			case DOUBLE: {
				return "D";
			}
			case FLOAT: {
				return "F";
			}
			case INT: {
				return "I";
			}
			case LONG: {
				return "J";
			}
			case SHORT: {
				return "S";
			}
			case DECLARED: {
				TypeElement elem = (TypeElement) ((DeclaredType) tm).asElement();
				return "L" + getInternalName(elem) + ";";
			}
			case VOID: {
				return "V";
			}
			case EXECUTABLE: {
				ExecutableType et = (ExecutableType) tm;
				List<? extends TypeMirror> params = et.getParameterTypes();
				StringBuilder sb = new StringBuilder();
				sb.append('(');
				sb.append(')');
				for (TypeMirror ptm : params) {
					sb.append(getDescriptor(ptm));
				}
				sb.append(getDescriptor(et.getReturnType()));
				return sb.toString();
			}
			case TYPEVAR: {
				TypeVariable tv = (TypeVariable) tm;
				TypeParameterElement elem = (TypeParameterElement) tv.asElement();
				List<? extends TypeMirror> bounds = elem.getBounds();
				if (bounds.isEmpty()) {
					return "Ljava/lang/Object;";
				}
				return getDescriptor(bounds.get(0));
			}
			case ERROR:
			case INTERSECTION:
			case UNION:
			case WILDCARD:
			default: {
				throw new IllegalArgumentException(kind.toString());
			}
		}
	}

	private String getInternalName(TypeMirror tm) {
		TypeKind kind = tm.getKind();
		switch (kind) {
			case DECLARED: {
				TypeElement elem = (TypeElement) ((DeclaredType) tm).asElement();
				return getInternalName(elem);
			}
			case TYPEVAR: {
				TypeVariable tv = (TypeVariable) tm;
				return getInternalName(tv.getUpperBound());
			}
			case INTERSECTION: {
				IntersectionType is = (IntersectionType) tm;
				List<? extends TypeMirror> bounds = is.getBounds();
				if (bounds == null || bounds.isEmpty()) {
					return null;
				}
				return getInternalName(bounds.get(0));
			}
			default: {
				throw new IllegalArgumentException(kind.toString());
			}
		}
	}

	private String getInternalName(TypeElement type) {
		return elements.getBinaryName(type).toString().replace('.', '/');
	}

	private void writeInnerClassAttributes(ClassWriter cw, TypeElement type) {
		if (type == null) {
			return;
		}
		if (type.getNestingKind() == NestingKind.MEMBER) {
			TypeElement enctype = (TypeElement) type.getEnclosingElement();
			writeInnerClassAttributes(cw, enctype);

			int access = getInnerClassModifierAccessOpcode(type);
			cw.visitInnerClass(getInternalName(type), getInternalName(enctype), type.getSimpleName().toString(),
					access);
		}
	}

	private int getInnerClassModifierAccessOpcode(TypeElement e) {
		int access = 0;
		Set<Modifier> mods = e.getModifiers();
		if (mods.contains(Modifier.STATIC)) {
			access |= Opcodes.ACC_STATIC;
		}
		if (mods.contains(Modifier.PUBLIC)) {
			access |= Opcodes.ACC_PUBLIC;
		}
		if (mods.contains(Modifier.PRIVATE)) {
			access |= Opcodes.ACC_PRIVATE;
		}
		if (mods.contains(Modifier.PROTECTED)) {
			access |= Opcodes.ACC_PROTECTED;
		}
		if (mods.contains(Modifier.ABSTRACT)) {
			access |= Opcodes.ACC_ABSTRACT;
		}
		if (mods.contains(Modifier.FINAL)) {
			access |= Opcodes.ACC_FINAL;
		}
		if (elements.isDeprecated(e)) {
			access |= Opcodes.ACC_DEPRECATED;
		}
		ElementKind kind = e.getKind();
		if (kind == ElementKind.ENUM || kind == ElementKind.ENUM_CONSTANT) {
			access |= Opcodes.ACC_ENUM;
		}
		if (kind == ElementKind.ANNOTATION_TYPE) {
			access |= Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE;
		}
		if (kind == ElementKind.INTERFACE) {
			access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		}
		return access;
	}

	private int getClassModifierAccessOpcode(TypeElement e) {
		int access = 0;
		Set<Modifier> mods = e.getModifiers();
		if (mods.contains(Modifier.PUBLIC)) {
			access |= Opcodes.ACC_PUBLIC;
		}
		if (mods.contains(Modifier.ABSTRACT)) {
			access |= Opcodes.ACC_ABSTRACT;
		}
		if (mods.contains(Modifier.FINAL)) {
			access |= Opcodes.ACC_FINAL;
		}
		if (elements.isDeprecated(e)) {
			access |= Opcodes.ACC_DEPRECATED;
		}
		ElementKind kind = e.getKind();
		if (kind == ElementKind.ENUM) {
			access |= Opcodes.ACC_ENUM;
		}
		if (kind == ElementKind.ANNOTATION_TYPE) {
			access |= Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE;
		}
		if (kind == ElementKind.INTERFACE) {
			access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		}
		return access;
	}

	private int getFieldModifierAccessOpcode(TypeElement type, VariableElement ve) {
		int access = 0;
		Set<Modifier> mods = ve.getModifiers();
		if (mods.contains(Modifier.STATIC)) {
			access |= Opcodes.ACC_STATIC;
		}
		if (mods.contains(Modifier.PUBLIC)) {
			access |= Opcodes.ACC_PUBLIC;
		}
		if (mods.contains(Modifier.PRIVATE)) {
			access |= Opcodes.ACC_PRIVATE;
		}
		if (mods.contains(Modifier.PROTECTED)) {
			access |= Opcodes.ACC_PROTECTED;
		}
		if (mods.contains(Modifier.FINAL)) {
			access |= Opcodes.ACC_FINAL;
		}
		if (elements.isDeprecated(ve)) {
			access |= Opcodes.ACC_DEPRECATED;
		}
		if (mods.contains(Modifier.VOLATILE)) {
			access |= Opcodes.ACC_VOLATILE;
		}
		if (mods.contains(Modifier.TRANSIENT)) {
			access |= Opcodes.ACC_TRANSIENT;
		}
		if (ve.getKind() == ElementKind.ENUM_CONSTANT) {
			access |= Opcodes.ACC_ENUM;
		}
		return access;
	}

	private int getParameterModifierAccessOpcode(TypeElement type, ExecutableElement ee, VariableElement pe) {
		int access = 0;
		Set<Modifier> mods = pe.getModifiers();
		if (mods.contains(Modifier.FINAL)) {
			access |= Opcodes.ACC_FINAL;
		}
		if (elements.isDeprecated(pe)) {
			access |= Opcodes.ACC_DEPRECATED;
		}
		return access;
	}

	private int getMethodModifierAccessOpcode(TypeElement type, ExecutableElement e) {
		int access = 0;
		Set<Modifier> mods = e.getModifiers();
		if (mods.contains(Modifier.STATIC)) {
			access |= Opcodes.ACC_STATIC;
		}
		if (mods.contains(Modifier.PUBLIC)) {
			access |= Opcodes.ACC_PUBLIC;
		}
		if (mods.contains(Modifier.PRIVATE)) {
			access |= Opcodes.ACC_PRIVATE;
		}
		if (mods.contains(Modifier.PROTECTED)) {
			access |= Opcodes.ACC_PROTECTED;
		}
		if (mods.contains(Modifier.STRICTFP)) {
			access |= Opcodes.ACC_STRICT;
		} else if (type.getModifiers().contains(Modifier.STRICTFP)) {
			access |= Opcodes.ACC_STRICT;
		}
		if (mods.contains(Modifier.ABSTRACT)) {
			access |= Opcodes.ACC_ABSTRACT;
		}
		if (mods.contains(Modifier.FINAL)) {
			access |= Opcodes.ACC_FINAL;
		}
		if (elements.isDeprecated(e)) {
			access |= Opcodes.ACC_DEPRECATED;
		}
		if (mods.contains(Modifier.SYNCHRONIZED)) {
			access |= Opcodes.ACC_SYNCHRONIZED;
		}
		if (mods.contains(Modifier.VOLATILE)) {
			access |= Opcodes.ACC_VOLATILE;
		}
		//NATIVE is intentionally left out.
		if (e.isVarArgs()) {
			access |= Opcodes.ACC_VARARGS;
		}
		return access;
	}

}
