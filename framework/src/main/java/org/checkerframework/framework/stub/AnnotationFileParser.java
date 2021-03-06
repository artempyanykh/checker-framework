package org.checkerframework.framework.stub;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.Problem;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.StubUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.WildcardType;
import java.io.InputStream;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.signature.qual.CanonicalName;
import org.checkerframework.checker.signature.qual.DotSeparatedIdentifiers;
import org.checkerframework.checker.signature.qual.FullyQualifiedName;
import org.checkerframework.framework.qual.FromStubFile;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.type.AnnotatedTypeReplacer;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.Pair;

// From an implementation perspective, this class represents a single annotation file (a stub file),
// notably its annotated types and its declaration annotations.  From a client perspective, it has
// static methods as described below in the Javadoc.
/**
 * This class has two static methods. Each method parses an annotation file and adds annotations to
 * two maps passed as arguments.
 *
 * <p>The main entry point is {@link AnnotationFileParser#parse(String, InputStream,
 * AnnotatedTypeFactory, ProcessingEnvironment, StubAnnotations)}, which side-effects its last two
 * arguments. It operates in two steps. First, it calls the Stub Parser to parse a stub file. Then,
 * it walks the Stub Parser's AST to create/collect types and declaration annotations.
 *
 * <p>The other entry point is {@link #parseJdkFileAsStub}.
 */
public class AnnotationFileParser {

    /**
     * Whether to print warnings about types/members that were not found. The warning states that a
     * class/field in the file is not found on the user's real classpath. Since the file may contain
     * packages that are not on the classpath, this can be OK, so default to false.
     */
    private final boolean warnIfNotFound;

    /**
     * Whether to ignore missing classes even when warnIfNotFound is set to true. This allows the
     * files to contain classes not in the classpath (even if another class in the classpath has the
     * same package), but still warn if members of the class (methods, fields) are missing. This
     * option does nothing unless warnIfNotFound is also set.
     */
    private final boolean warnIfNotFoundIgnoresClasses;

    /** Whether to print warnings about stub files that overwrite annotations from bytecode. */
    private final boolean warnIfStubOverwritesBytecode;

    /**
     * Whether to print warnings about stub files that are redundant with annotations from bytecode.
     */
    private final boolean warnIfStubRedundantWithBytecode;

    /** Whether to print verbose debugging messages. */
    private final boolean debugAnnotationFileParser;

    /** The name of the file being processed; used only for diagnostic messages. */
    private final String filename;

    /**
     * The AST of the parsed file that this class is processing. May be null if there was a problem
     * parsing the file. (TODO: Should the Checker Framework just halt in that case?)
     */
    // Not final in order to accommodate a default value.
    private StubUnit stubUnit;

    private final ProcessingEnvironment processingEnv;
    private final AnnotatedTypeFactory atypeFactory;
    private final Elements elements;

    /**
     * The set of annotations found in the file. Keys are both fully-qualified and simple names.
     * There are two entries for each annotation: the annotation's simple name and its
     * fully-qualified name.
     *
     * <p>The map is populated from import statements and also by {@link #getAnnotation(
     * AnnotationExpr, Map)} for annotations that are used fully-qualified.
     *
     * @see #getAllAnnotations
     */
    private Map<String, TypeElement> allAnnotations;

    /**
     * A list of the fully-qualified names of enum constants and static fields with constant values
     * that have been imported.
     */
    private final List<@FullyQualifiedName String> importedConstants = new ArrayList<>();

    /** A map of imported fully-qualified type names to type elements. */
    private final Map<String, TypeElement> importedTypes = new HashMap<>();

    /** The annotation {@code @FromStubFile}. */
    private final AnnotationMirror fromStubFileAnno;

    /**
     * List of AnnotatedTypeMirrors for class or method type parameters that are in scope of the
     * elements currently parsed.
     */
    private final List<AnnotatedTypeVariable> typeParameters = new ArrayList<>();

    /**
     * The annotations on the declared package of the complation unit being processed. Contains null
     * if not processing a compilation unit or if the file has no declared package.
     */
    @Nullable List<AnnotationExpr> packageAnnos;

    // The following variables are stored in the AnnotationFileParser because otherwise they would
    // need to be
    // passed through everywhere, which would be verbose.

    /**
     * The name of the type that is currently being parsed. After processing a package declaration
     * but before processing a type declaration, the type part of this may be null.
     *
     * <p>It is used both for resolving symbols and for error messages.
     */
    private FqName typeBeingParsed;

    /** The line separator. */
    private static final String LINE_SEPARATOR = System.lineSeparator().intern();

    /** Whether or not the file is a stub file that's part of the JDK. */
    private final boolean isJdkAsStub;

    /**
     * The result of calling AnnotationFileParser.parse: the annotated types and declaration
     * annotations from the file.
     */
    public static class StubAnnotations {

        /** Map from element to its type as declared in the stub file. */
        public final Map<Element, AnnotatedTypeMirror> atypes = new HashMap<>();

        /**
         * Map from a name (actually declaration element string) to the set of declaration
         * annotations on it, as written in the stub file.
         *
         * <p>Map keys cannot be Element, because a different Element appears in the stub files than
         * in the real files. So, map keys are the verbose element name, as returned by
         * ElementUtils.getQualifiedName.
         */
        public final Map<String, Set<AnnotationMirror>> declAnnos = new HashMap<>();
    }

    /**
     * Create a new AnnotationFileParser object, which will parse and extract annotations from the
     * given file.
     *
     * @param filename name of annotation file, used only for diagnostic messages
     * @param atypeFactory AnnotatedTypeFactory to use
     * @param processingEnv ProcessingEnvironment to use
     * @param isJdkAsStub whether or not this is a stub file that's part of the JDK
     */
    private AnnotationFileParser(
            String filename,
            AnnotatedTypeFactory atypeFactory,
            ProcessingEnvironment processingEnv,
            boolean isJdkAsStub) {
        this.filename = filename;
        this.atypeFactory = atypeFactory;
        this.processingEnv = processingEnv;
        this.elements = processingEnv.getElementUtils();

        // TODO: This should use SourceChecker.getOptions() to allow
        // setting these flags per checker.
        Map<String, String> options = processingEnv.getOptions();
        this.warnIfNotFound = options.containsKey("stubWarnIfNotFound");
        this.warnIfNotFoundIgnoresClasses = options.containsKey("stubWarnIfNotFoundIgnoresClasses");
        this.warnIfStubOverwritesBytecode = options.containsKey("stubWarnIfOverwritesBytecode");
        this.warnIfStubRedundantWithBytecode =
                options.containsKey("stubWarnIfRedundantWithBytecode")
                        && atypeFactory.shouldWarnIfStubRedundantWithBytecode();
        this.debugAnnotationFileParser = options.containsKey("stubDebug");

        this.fromStubFileAnno = AnnotationBuilder.fromClass(elements, FromStubFile.class);

        this.isJdkAsStub = isJdkAsStub;
    }

    /**
     * All annotations defined in the package (but not those nested within classes in the package).
     * Keys are both fully-qualified and simple names.
     *
     * @param packageElement a package
     * @return a map from annotation name to TypeElement
     */
    private Map<String, TypeElement> annosInPackage(PackageElement packageElement) {
        return createNameToAnnotationMap(
                ElementFilter.typesIn(packageElement.getEnclosedElements()));
    }

    /**
     * All annotations declared (directly) within a class. Keys are both fully-qualified and simple
     * names.
     *
     * @param typeElement a type
     * @return a map from annotation name to TypeElement
     */
    private Map<String, TypeElement> annosInType(TypeElement typeElement) {
        return createNameToAnnotationMap(ElementFilter.typesIn(typeElement.getEnclosedElements()));
    }

    /**
     * All annotations declared within any of the given elements.
     *
     * @param typeElements the elements whose annotations to retrieve
     * @return a map from annotation names (both fully-qualified and simple names) to TypeElement
     */
    private Map<String, TypeElement> createNameToAnnotationMap(List<TypeElement> typeElements) {
        Map<String, TypeElement> result = new HashMap<>();
        for (TypeElement typeElm : typeElements) {
            if (typeElm.getKind() == ElementKind.ANNOTATION_TYPE) {
                putIfAbsent(result, typeElm.getSimpleName().toString(), typeElm);
                putIfAbsent(result, typeElm.getQualifiedName().toString(), typeElm);
            }
        }
        return result;
    }

    /**
     * Get all members of a Type that are importable in an annotation file. Currently these are
     * values of enums, or compile time constants.
     *
     * @param typeElement the type whose members to return
     * @return a list of fully-qualified member names
     */
    private static List<@FullyQualifiedName String> getImportableMembers(TypeElement typeElement) {
        List<@FullyQualifiedName String> result = new ArrayList<>();
        List<VariableElement> memberElements =
                ElementFilter.fieldsIn(typeElement.getEnclosedElements());
        for (VariableElement varElement : memberElements) {
            if (varElement.getConstantValue() != null
                    || varElement.getKind() == ElementKind.ENUM_CONSTANT) {
                @SuppressWarnings("signature") // string concatenation
                @FullyQualifiedName String fqName =
                        typeElement.getQualifiedName().toString()
                                + "."
                                + varElement.getSimpleName().toString();
                result.add(fqName);
            }
        }
        return result;
    }

    /**
     * Returns all annotations imported by the annotation file, as a value for {@link
     * #allAnnotations}. Note that this also modifies {@link #importedConstants} and {@link
     * #importedTypes}.
     *
     * <p>This method misses annotations that are not imported. The {@link #getAnnotation} method
     * compensates for this deficiency by adding any fully-qualified annotation that it encounters.
     *
     * @return a map from names to TypeElement, for all annotations imported by the annotation file.
     *     Two entries for each annotation: one for the simple name and another for the
     *     fully-qualified name, with the same value.
     * @see #allAnnotations
     */
    private Map<String, TypeElement> getAllAnnotations() {
        Map<String, TypeElement> result = new HashMap<>();

        // TODO: The size can be greater than 1, but this ignores all but the first element.
        assert !stubUnit.getCompilationUnits().isEmpty();
        CompilationUnit cu = stubUnit.getCompilationUnits().get(0);

        if (cu.getImports() == null) {
            return result;
        }

        for (ImportDeclaration importDecl : cu.getImports()) {
            try {
                if (importDecl.isAsterisk()) {
                    @SuppressWarnings("signature" // https://tinyurl.com/cfissue/3094:
                    // com.github.javaparser.ast.expr.Name inherits toString,
                    // so there can be no annotation for it
                    )
                    @DotSeparatedIdentifiers String imported = importDecl.getName().toString();
                    if (importDecl.isStatic()) {
                        // Wildcard import of members of a type (class or interface)
                        TypeElement element = getTypeElement(imported, "Imported type not found");
                        if (element != null) {
                            // Find nested annotations
                            // Find compile time constant fields, or values of an enum
                            putAllNew(result, annosInType(element));
                            importedConstants.addAll(getImportableMembers(element));
                            addEnclosingTypesToImportedTypes(element);
                        }

                    } else {
                        // Wildcard import of members of a package
                        PackageElement element = findPackage(imported);
                        if (element != null) {
                            putAllNew(result, annosInPackage(element));
                            addEnclosingTypesToImportedTypes(element);
                        }
                    }
                } else {
                    // A single (non-wildcard) import.
                    @SuppressWarnings("signature" // importDecl is non-wildcard, so its name is
                    // @FullyQualifiedName
                    )
                    @FullyQualifiedName String imported = importDecl.getNameAsString();

                    final TypeElement importType = elements.getTypeElement(imported);
                    if (importType == null && !importDecl.isStatic()) {
                        // Class or nested class (according to JSL), but we can't resolve

                        stubWarnNotFound("Imported type not found: " + imported);
                    } else if (importType == null) {
                        // static import of field or method.

                        Pair<@FullyQualifiedName String, String> typeParts =
                                AnnotationFileUtil.partitionQualifiedName(imported);
                        String type = typeParts.first;
                        String fieldName = typeParts.second;
                        TypeElement enclType =
                                getTypeElement(
                                        type,
                                        String.format(
                                                "Enclosing type of static field %s not found",
                                                fieldName));

                        if (enclType != null) {
                            // Don't use findFieldElement(enclType, fieldName), because we don't
                            // want a warning, imported might be a method.
                            for (VariableElement field :
                                    ElementUtils.getAllFieldsIn(enclType, elements)) {
                                // field.getSimpleName() is a CharSequence, not a String
                                if (fieldName.equals(field.getSimpleName().toString())) {
                                    importedConstants.add(imported);
                                }
                            }
                        }

                    } else if (importType.getKind() == ElementKind.ANNOTATION_TYPE) {
                        // Single annotation or nested annotation
                        TypeElement annoElt = elements.getTypeElement(imported);
                        if (annoElt != null) {
                            putIfAbsent(result, annoElt.getSimpleName().toString(), annoElt);
                            importedTypes.put(annoElt.getSimpleName().toString(), annoElt);
                        } else {
                            stubWarnNotFound("Could not load import: " + imported);
                        }
                    } else {
                        // Class or nested class
                        // TODO: Is this needed?
                        importedConstants.add(imported);
                        TypeElement element = getTypeElement(imported, "Imported type not found");
                        importedTypes.put(element.getSimpleName().toString(), element);
                    }
                }
            } catch (AssertionError error) {
                stubWarnNotFound(error.toString());
            }
        }
        return result;
    }

    // If a member is imported, then consider every containing class to also be imported.
    private void addEnclosingTypesToImportedTypes(Element element) {
        for (Element enclosedEle : element.getEnclosedElements()) {
            if (enclosedEle.getKind().isClass()) {
                importedTypes.put(
                        enclosedEle.getSimpleName().toString(), (TypeElement) enclosedEle);
            }
        }
    }

    /**
     * The main entry point. Parse a stub file and side-effects the last argument.
     *
     * @param filename name of stub file, used only for diagnostic messages
     * @param inputStream of stub file to parse
     * @param atypeFactory AnnotatedTypeFactory to use
     * @param processingEnv ProcessingEnvironment to use
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    public static void parse(
            String filename,
            InputStream inputStream,
            AnnotatedTypeFactory atypeFactory,
            ProcessingEnvironment processingEnv,
            StubAnnotations stubAnnos) {
        parse(filename, inputStream, atypeFactory, processingEnv, stubAnnos, false);
    }

    /**
     * Parse a stub file that is a part of the annotated JDK and side-effects the last two
     * arguments.
     *
     * @param filename name of stub file, used only for diagnostic messages
     * @param inputStream of stub file to parse
     * @param atypeFactory AnnotatedTypeFactory to use
     * @param processingEnv ProcessingEnvironment to use
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    public static void parseJdkFileAsStub(
            String filename,
            InputStream inputStream,
            AnnotatedTypeFactory atypeFactory,
            ProcessingEnvironment processingEnv,
            StubAnnotations stubAnnos) {
        parse(filename, inputStream, atypeFactory, processingEnv, stubAnnos, true);
    }

    /**
     * Parse a stub file and adds annotations to {@code stubAnnos}.
     *
     * @param filename name of stub file, used only for diagnostic messages
     * @param inputStream of stub file to parse
     * @param atypeFactory AnnotatedTypeFactory to use
     * @param processingEnv ProcessingEnvironment to use
     * @param stubAnnos annotations from the stub file; side-effected by this method
     * @param isJdkAsStub whether or not the stub file is a part of the annotated JDK
     */
    private static void parse(
            String filename,
            InputStream inputStream,
            AnnotatedTypeFactory atypeFactory,
            ProcessingEnvironment processingEnv,
            StubAnnotations stubAnnos,
            boolean isJdkAsStub) {
        AnnotationFileParser afp =
                new AnnotationFileParser(filename, atypeFactory, processingEnv, isJdkAsStub);
        try {
            afp.parseStubUnit(inputStream);
            afp.process(stubAnnos);
        } catch (ParseProblemException e) {
            StringJoiner message = new StringJoiner(LINE_SEPARATOR);
            message.add(
                    e.getProblems().size() + " problems while parsing stub file " + filename + ":");
            // Manually build up the message, to get verbose location information.
            for (Problem p : e.getProblems()) {
                message.add(p.getVerboseMessage());
            }
            afp.warn(message.toString());
        }
    }

    /**
     * Delegate to the Stub Parser to parse the annotation file to an AST, and save it in {@link
     * #stubUnit}. Also modifies other fields of this.
     *
     * <p>Subsequently, all work uses the AST.
     *
     * @param inputStream the stream from which to read an annotation file
     */
    private void parseStubUnit(InputStream inputStream) {
        if (debugAnnotationFileParser) {
            stubDebug(String.format("parsing stub file %s", filename));
        }
        stubUnit = StaticJavaParser.parseStubUnit(inputStream);

        // getAllAnnotations() also modifies importedConstants and importedTypes. This should
        // be refactored to be nicer.
        allAnnotations = getAllAnnotations();
        if (allAnnotations.isEmpty()) {
            // This issues a warning if the stub file contains no import statements.  That is
            // incorrect if the stub file contains fully-qualified annotations.
            stubWarnNotFound(
                    String.format(
                            "No supported annotations found! Does stub file %s import them?",
                            filename));
        }
        // Annotations in java.lang might be used without an import statement, so add them in case.
        allAnnotations.putAll(annosInPackage(findPackage("java.lang")));
    }

    /**
     * Process {@link #stubUnit}, which is the AST produced by {@link #parseStubUnit}.
     *
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void process(StubAnnotations stubAnnos) {
        processStubUnit(this.stubUnit, stubAnnos);
    }

    /**
     * Process the given StubUnit.
     *
     * @param su the StubUnit to process
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processStubUnit(StubUnit su, StubAnnotations stubAnnos) {
        for (CompilationUnit cu : su.getCompilationUnits()) {
            processCompilationUnit(cu, stubAnnos);
        }
    }

    /**
     * Process the given CompilationUnit.
     *
     * @param cu the CompilationUnit to process
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processCompilationUnit(CompilationUnit cu, StubAnnotations stubAnnos) {

        if (!cu.getPackageDeclaration().isPresent()) {
            packageAnnos = null;
            typeBeingParsed = new FqName(null, null);
        } else {
            PackageDeclaration pDecl = cu.getPackageDeclaration().get();
            packageAnnos = pDecl.getAnnotations();
            processPackage(pDecl, stubAnnos);
        }
        if (cu.getTypes() != null) {
            for (TypeDeclaration<?> typeDeclaration : cu.getTypes()) {
                processTypeDecl(typeDeclaration, null, stubAnnos);
            }
        }
    }

    /**
     * Process the given package declaration
     *
     * @param packDecl the package declaration to process
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processPackage(PackageDeclaration packDecl, StubAnnotations stubAnnos) {
        assert (packDecl != null);
        String packageName = packDecl.getNameAsString();
        typeBeingParsed = new FqName(packageName, null);
        Element elem = elements.getPackageElement(packageName);
        // If the element lookup fails, it's because we have an annotation for a
        // package that isn't on the classpath, which is fine.
        if (elem != null) {
            recordDeclAnnotation(elem, packDecl.getAnnotations(), stubAnnos);
        }
        // TODO: Handle atypes???
    }

    /**
     * Process a type declaration.
     *
     * @param typeDecl the type declaration to process
     * @param outertypeName the name of the containing class, when processing a nested class;
     *     otherwise null
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processTypeDecl(
            TypeDeclaration<?> typeDecl, String outertypeName, StubAnnotations stubAnnos) {
        assert typeBeingParsed != null;
        if (isJdkAsStub && typeDecl.getModifiers().contains(Modifier.privateModifier())) {
            // Don't process private classes of the JDK.  They can't be referenced outside of the
            // JDK and might refer to types that are not accessible.
            return;
        }
        String innerName =
                (outertypeName == null ? "" : outertypeName + ".") + typeDecl.getNameAsString();
        typeBeingParsed = new FqName(typeBeingParsed.packageName, innerName);
        @SuppressWarnings(
                "signature") // FqName.toString : @FullyQualifiedName; and @CanonicalName because
        // this is its declaration
        @CanonicalName String fqTypeName = typeBeingParsed.toString();
        TypeElement typeElt = elements.getTypeElement(fqTypeName);
        if (typeElt == null) {
            if (debugAnnotationFileParser
                    || (!hasNoAnnotationFileParserWarning(typeDecl.getAnnotations())
                            && !hasNoAnnotationFileParserWarning(packageAnnos)
                            && !warnIfNotFoundIgnoresClasses)) {
                stubWarnNotFound("Type not found: " + fqTypeName);
            }
            return;
        }

        List<AnnotatedTypeVariable> typeDeclTypeParameters = null;
        if (typeElt.getKind() == ElementKind.ENUM) {
            if (!(typeDecl instanceof EnumDeclaration)) {
                warn(
                        innerName
                                + " is an enum, but stub file declared it as "
                                + typeDecl.toString().split("\\R", 2)[0]
                                + "...");
                return;
            }
            typeDeclTypeParameters = processEnum((EnumDeclaration) typeDecl, typeElt, stubAnnos);
            typeParameters.addAll(typeDeclTypeParameters);
        } else if (typeElt.getKind() == ElementKind.ANNOTATION_TYPE) {
            if (!(typeDecl instanceof AnnotationDeclaration)) {
                warn(
                        innerName
                                + " is an annotation, but stub file declared it as "
                                + typeDecl.toString().split("\\R", 2)[0]
                                + "...");
                return;
            }
            stubWarnNotFound("Skipping annotation type: " + fqTypeName);
        } else if (typeDecl instanceof ClassOrInterfaceDeclaration) {
            if (!(typeDecl instanceof ClassOrInterfaceDeclaration)) {
                warn(
                        innerName
                                + " is a class or interface, but stub file declared it as "
                                + typeDecl.toString().split("\\R", 2)[0]
                                + "...");
                return;
            }
            typeDeclTypeParameters =
                    processType((ClassOrInterfaceDeclaration) typeDecl, typeElt, stubAnnos);
            typeParameters.addAll(typeDeclTypeParameters);
        } // else it's an EmptyTypeDeclaration.  TODO:  An EmptyTypeDeclaration can have
        // annotations, right?

        // `elementsToDecl` is for members of a single type.  It does not contain any stub
        // declaration that does not match some member of the element.
        Map<Element, BodyDeclaration<?>> elementsToDecl = getMembers(typeDecl, typeElt);
        // This loop converts each JavaParser declaration into an AnnotatedTypeMirror.
        for (Map.Entry<Element, BodyDeclaration<?>> entry : elementsToDecl.entrySet()) {
            final Element elt = entry.getKey();
            final BodyDeclaration<?> decl = entry.getValue();
            switch (elt.getKind()) {
                case FIELD:
                    processField((FieldDeclaration) decl, (VariableElement) elt, stubAnnos);
                    break;
                case ENUM_CONSTANT:
                    processEnumConstant(
                            (EnumConstantDeclaration) decl, (VariableElement) elt, stubAnnos);
                    break;
                case CONSTRUCTOR:
                case METHOD:
                    processCallableDeclaration(
                            (CallableDeclaration<?>) decl, (ExecutableElement) elt, stubAnnos);
                    break;
                case CLASS:
                case INTERFACE:
                    processTypeDecl((ClassOrInterfaceDeclaration) decl, innerName, stubAnnos);
                    break;
                case ENUM:
                    processTypeDecl((EnumDeclaration) decl, innerName, stubAnnos);
                    break;
                default:
                    /* do nothing */
                    stubWarnNotFound("AnnotationFileParser ignoring: " + elt);
                    break;
            }
        }
        if (typeDeclTypeParameters != null) {
            typeParameters.removeAll(typeDeclTypeParameters);
        }
    }

    /**
     * Returns true if the argument contains {@code @NoAnnotationFileParserWarning}.
     *
     * @param aexprs collection of annotation expressions
     * @return true if {@code aexprs} contains {@code @NoAnnotationFileParserWarning}
     */
    private boolean hasNoAnnotationFileParserWarning(Iterable<AnnotationExpr> aexprs) {
        if (aexprs == null) {
            return false;
        }
        for (AnnotationExpr anno : aexprs) {
            if (anno.getNameAsString().equals("NoAnnotationFileParserWarning")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Processes the type's declaration but not any of its members. Returns the type's type
     * parameter declarations.
     *
     * @param decl a type declaration
     * @param elt the type's element
     * @param stubAnnos annotations from the stub file; side-effected by this method
     * @return the type's type parameter declarations
     */
    private List<AnnotatedTypeVariable> processType(
            ClassOrInterfaceDeclaration decl, TypeElement elt, StubAnnotations stubAnnos) {

        recordDeclAnnotation(elt, decl.getAnnotations(), stubAnnos);
        AnnotatedDeclaredType type = atypeFactory.fromElement(elt);
        annotate(type, decl.getAnnotations());

        final List<? extends AnnotatedTypeMirror> typeArguments = type.getTypeArguments();
        final List<TypeParameter> typeParameters = decl.getTypeParameters();

        // It can be the case that args=[] and params=null, so don't crash in that case.
        // if ((typeParameters == null) != (typeArguments == null)) {
        //     throw new Error(String.format("parseType (%s, %s): inconsistent nullness for args and
        // params%n  args = %s%n  params = %s%n", decl, elt, typeArguments, typeParameters));
        // }

        if (debugAnnotationFileParser) {
            int numParams = (typeParameters == null ? 0 : typeParameters.size());
            int numArgs = (typeArguments == null ? 0 : typeArguments.size());
            if (numParams != numArgs) {
                stubDebug(
                        String.format(
                                "parseType:  mismatched sizes for typeParameters=%s (size %d) and typeArguments=%s (size %d); decl=%s; elt=%s (%s); type=%s (%s); typeBeingParsed=%s",
                                typeParameters,
                                numParams,
                                typeArguments,
                                numArgs,
                                decl.toString().replace(LINE_SEPARATOR, " "),
                                elt.toString().replace(LINE_SEPARATOR, " "),
                                elt.getClass(),
                                type,
                                type.getClass(),
                                typeBeingParsed));
                stubDebug("Proceeding despite mismatched sizes");
            }
        }

        annotateTypeParameters(decl, elt, stubAnnos, typeArguments, typeParameters);
        annotateSupertypes(decl, type);
        putMerge(stubAnnos.atypes, elt, type);
        List<AnnotatedTypeVariable> typeVariables = new ArrayList<>();
        for (AnnotatedTypeMirror typeV : type.getTypeArguments()) {
            if (typeV.getKind() != TypeKind.TYPEVAR) {
                warn(
                        "expected an AnnotatedTypeVariable but found type kind "
                                + typeV.getKind()
                                + ": "
                                + typeV);
            } else {
                typeVariables.add((AnnotatedTypeVariable) typeV);
            }
        }
        return typeVariables;
    }

    /**
     * Returns an enum's type parameter declarations.
     *
     * @param decl enum declaration
     * @param elt element representing enum
     * @param stubAnnos annotations from the stub file; side-effected by this method
     * @return the enum's type parameter declarations
     */
    private List<AnnotatedTypeVariable> processEnum(
            EnumDeclaration decl, TypeElement elt, StubAnnotations stubAnnos) {

        recordDeclAnnotation(elt, decl.getAnnotations(), stubAnnos);
        AnnotatedDeclaredType type = atypeFactory.fromElement(elt);
        annotate(type, decl.getAnnotations());

        putMerge(stubAnnos.atypes, elt, type);
        List<AnnotatedTypeVariable> typeVariables = new ArrayList<>();
        for (AnnotatedTypeMirror typeV : type.getTypeArguments()) {
            if (typeV.getKind() != TypeKind.TYPEVAR) {
                warn(
                        "expected an AnnotatedTypeVariable but found type kind "
                                + typeV.getKind()
                                + ": "
                                + typeV);
            } else {
                typeVariables.add((AnnotatedTypeVariable) typeV);
            }
        }
        return typeVariables;
    }

    private void annotateSupertypes(
            ClassOrInterfaceDeclaration typeDecl, AnnotatedDeclaredType type) {
        if (typeDecl.getExtendedTypes() != null) {
            for (ClassOrInterfaceType supertype : typeDecl.getExtendedTypes()) {
                AnnotatedDeclaredType annotatedSupertype =
                        findAnnotatedType(supertype, type.directSuperTypes());
                if (annotatedSupertype == null) {
                    warn(
                            "stub file does not match bytecode: "
                                    + "could not find superclass "
                                    + supertype
                                    + " from type "
                                    + type);
                } else {
                    annotate(annotatedSupertype, supertype, null);
                }
            }
        }
        if (typeDecl.getImplementedTypes() != null) {
            for (ClassOrInterfaceType supertype : typeDecl.getImplementedTypes()) {
                AnnotatedDeclaredType annotatedSupertype =
                        findAnnotatedType(supertype, type.directSuperTypes());
                if (annotatedSupertype == null) {
                    warn(
                            "stub file does not match bytecode: "
                                    + "could not find superinterface "
                                    + supertype
                                    + " from type "
                                    + type);
                } else {
                    annotate(annotatedSupertype, supertype, null);
                }
            }
        }
    }

    /**
     * Adds type and declaration annotations from {@code decl}.
     *
     * @param decl a method or constructor declaration
     * @param elt the method or constructor's element
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processCallableDeclaration(
            CallableDeclaration<?> decl, ExecutableElement elt, StubAnnotations stubAnnos) {
        // Declaration annotations
        recordDeclAnnotation(elt, decl.getAnnotations(), stubAnnos);
        if (decl.isMethodDeclaration()) {
            // AnnotationFileParser parses all annotations in type annotation position as type
            // annotations
            recordDeclAnnotation(
                    elt, ((MethodDeclaration) decl).getType().getAnnotations(), stubAnnos);
        }
        recordDeclAnnotationFromAnnotationFile(elt, stubAnnos);

        AnnotatedExecutableType methodType = atypeFactory.fromElement(elt);
        AnnotatedExecutableType origMethodType =
                warnIfStubRedundantWithBytecode ? methodType.deepCopy() : null;

        // Type Parameters
        annotateTypeParameters(
                decl, elt, stubAnnos, methodType.getTypeVariables(), decl.getTypeParameters());
        typeParameters.addAll(methodType.getTypeVariables());

        // Return type, from declaration annotations on the method or constructor
        if (decl.isMethodDeclaration()) {
            annotate(
                    methodType.getReturnType(),
                    ((MethodDeclaration) decl).getType(),
                    decl.getAnnotations());
        } else {
            assert decl.isConstructorDeclaration();
            annotate(methodType.getReturnType(), decl.getAnnotations());
        }

        // Parameters
        processParameters(decl, elt, methodType, stubAnnos);

        // Receiver
        if (decl.getReceiverParameter().isPresent()) {
            if (methodType.getReceiverType() == null) {
                if (decl.isConstructorDeclaration()) {
                    warn(
                            "parseParameter: constructor %s of a top-level class cannot have receiver annotations %s",
                            methodType, decl.getReceiverParameter().get().getAnnotations());
                } else {
                    warn(
                            "parseParameter: static method %s cannot have receiver annotations %s",
                            methodType, decl.getReceiverParameter().get().getAnnotations());
                }
            } else {
                // Add declaration annotations.
                annotate(
                        methodType.getReceiverType(),
                        decl.getReceiverParameter().get().getAnnotations());
                // Add type annotations.
                annotate(
                        methodType.getReceiverType(),
                        decl.getReceiverParameter().get().getType(),
                        decl.getReceiverParameter().get().getAnnotations());
            }
        }

        if (warnIfStubRedundantWithBytecode
                && methodType.toString().equals(origMethodType.toString())
                && !isJdkAsStub) {
            warn(
                    String.format(
                            "in file %s at line %s: redundant stub file specification for: %s",
                            filename.substring(filename.lastIndexOf('/') + 1),
                            decl.getBegin().get().line,
                            ElementUtils.getQualifiedName(elt)));
        }

        // Store the type.
        putMerge(stubAnnos.atypes, elt, methodType);
        typeParameters.removeAll(methodType.getTypeVariables());
    }

    /**
     * Adds declaration and type annotations to the parameters of {@code methodType}, which is
     * either a method or constructor.
     *
     * @param method a Method or Constructor declaration
     * @param elt ExecutableElement of {@code method}
     * @param methodType annotated type of {@code method}
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processParameters(
            CallableDeclaration<?> method,
            ExecutableElement elt,
            AnnotatedExecutableType methodType,
            StubAnnotations stubAnnos) {
        List<Parameter> params = method.getParameters();
        List<? extends VariableElement> paramElts = elt.getParameters();
        List<? extends AnnotatedTypeMirror> paramTypes = methodType.getParameterTypes();

        for (int i = 0; i < methodType.getParameterTypes().size(); ++i) {
            VariableElement paramElt = paramElts.get(i);
            AnnotatedTypeMirror paramType = paramTypes.get(i);
            Parameter param = params.get(i);

            recordDeclAnnotation(paramElt, param.getAnnotations(), stubAnnos);
            recordDeclAnnotation(paramElt, param.getType().getAnnotations(), stubAnnos);

            if (param.isVarArgs()) {
                assert paramType.getKind() == TypeKind.ARRAY;
                // The "type" of param is actually the component type of the vararg.
                // For example, in "Object..." the type would be "Object".
                annotate(
                        ((AnnotatedArrayType) paramType).getComponentType(),
                        param.getType(),
                        param.getAnnotations());
                // The "VarArgsAnnotations" are those just before "...".
                annotate(paramType, param.getVarArgsAnnotations());
            } else {
                annotate(paramType, param.getType(), param.getAnnotations());
                putMerge(stubAnnos.atypes, paramElt, paramType);
            }
        }
    }

    /**
     * Clear (remove) existing annotations on the type.
     *
     * <p>Stub files override annotations read from .class files. Using {@code replaceAnnotation}
     * usually achieves this; however, for annotations on type variables, it is sometimes necessary
     * to remove an existing annotation, leaving no annotation on the type variable. This method
     * does so.
     *
     * @param atype the type to modify
     * @param typeDef the type from the stub file, used only for diagnostic messages
     */
    @SuppressWarnings("unused") // for disabled warning message
    private void clearAnnotations(AnnotatedTypeMirror atype, Type typeDef) {
        Set<AnnotationMirror> annos = atype.getAnnotations();
        // TODO: This should check whether the annotation file is @AnnotatedFor the current type
        // system.
        // @AnnotatedFor isn't integrated in stub files yet.
        if (annos != null && !annos.isEmpty()) {
            // TODO: only produce output if the removed annotation isn't the top and default
            // annotation in the type hierarchy.  See https://tinyurl.com/cfissue/2759 .
            /*
            if (false) {
                stubWarnOverwritesBytecode(
                        String.format(
                                "in file %s at line %s removed existing annotations on type: %s",
                                filename.substring(filename.lastIndexOf('/') + 1),
                                typeDef.getBegin().get().line,
                                atype.toString(true)));
            }
            */
            // Clear existing annotations, which only makes a difference for
            // type variables, but doesn't hurt in other cases.
            atype.clearAnnotations();
        }
    }

    /**
     * Add the annotations from {@code type} to {@code atype}. Type annotations that parsed as
     * declaration annotations (i.e., type annotations in {@code declAnnos}) are applied to the
     * innermost component type.
     *
     * @param atype annotated type to which to add annotations
     * @param type parsed type
     * @param declAnnos annotations stored on the declaration of the variable with this type or null
     */
    private void annotateAsArray(
            AnnotatedArrayType atype,
            ReferenceType type,
            @Nullable NodeList<AnnotationExpr> declAnnos) {
        annotateInnermostComponentType(atype, declAnnos);
        Type typeDef = type;
        AnnotatedTypeMirror currentAtype = atype;
        while (typeDef.isArrayType()) {
            if (currentAtype.getKind() != TypeKind.ARRAY) {
                warn("Mismatched array lengths; atype: " + atype + "%n  type: " + type);
                return;
            }

            // handle generic type
            clearAnnotations(currentAtype, typeDef);

            List<AnnotationExpr> annotations = typeDef.getAnnotations();
            if (annotations != null) {
                annotate(currentAtype, annotations);
            }
            typeDef = ((com.github.javaparser.ast.type.ArrayType) typeDef).getComponentType();
            currentAtype = ((AnnotatedArrayType) currentAtype).getComponentType();
        }
        if (currentAtype.getKind() == TypeKind.ARRAY) {
            warn("Mismatched array lengths; atype: " + atype + "%n  type: " + type);
        }
    }

    private ClassOrInterfaceType unwrapDeclaredType(Type type) {
        if (type instanceof ClassOrInterfaceType) {
            return (ClassOrInterfaceType) type;
        } else if (type instanceof ReferenceType && type.getArrayLevel() == 0) {
            return unwrapDeclaredType(type.getElementType());
        } else {
            return null;
        }
    }

    /**
     * Add to formal parameter {@code atype}:
     *
     * <ol>
     *   <li>the annotations from {@code typeDef}, and
     *   <li>any type annotations that parsed as declaration annotations (i.e., type annotations in
     *       {@code declAnnos}).
     * </ol>
     *
     * @param atype annotated type to which to add annotations
     * @param typeDef parsed type
     * @param declAnnos annotations stored on the declaration of the variable with this type, or
     *     null
     */
    private void annotate(
            AnnotatedTypeMirror atype, Type typeDef, @Nullable NodeList<AnnotationExpr> declAnnos) {
        if (atype.getKind() == TypeKind.ARRAY) {
            if (typeDef instanceof ReferenceType) {
                annotateAsArray((AnnotatedArrayType) atype, (ReferenceType) typeDef, declAnnos);
            } else {
                warn("expected ReferenceType but found: " + typeDef);
            }
            return;
        }

        clearAnnotations(atype, typeDef);

        // Primary annotations for the type of a variable declaration are not stored in typeDef, but
        // rather as declaration annotations (passed as declAnnos to this method).  But, if typeDef
        // is not the type of a variable, then the primary annotations are stored in typeDef.
        NodeList<AnnotationExpr> primaryAnnotations;
        if (typeDef.getAnnotations().isEmpty() && declAnnos != null) {
            primaryAnnotations = declAnnos;
        } else {
            primaryAnnotations = typeDef.getAnnotations();
        }

        if (atype.getKind() != TypeKind.WILDCARD) {
            // The primary annotation on a wildcard applies to the super or extends bound and
            // are added below.
            annotate(atype, primaryAnnotations);
        }
        switch (atype.getKind()) {
            case DECLARED:
                ClassOrInterfaceType declType = unwrapDeclaredType(typeDef);
                if (declType == null) {
                    break;
                }
                AnnotatedDeclaredType adeclType = (AnnotatedDeclaredType) atype;
                if (declType.getTypeArguments().isPresent()
                        && !declType.getTypeArguments().get().isEmpty()
                        && !adeclType.getTypeArguments().isEmpty()) {
                    if (declType.getTypeArguments().get().size()
                            != adeclType.getTypeArguments().size()) {
                        warn(
                                String.format(
                                        "Mismatch in type argument size between %s (%d) and %s (%d)",
                                        declType,
                                        declType.getTypeArguments().get().size(),
                                        adeclType,
                                        adeclType.getTypeArguments().size()));
                        break;
                    }
                    for (int i = 0; i < declType.getTypeArguments().get().size(); ++i) {
                        annotate(
                                adeclType.getTypeArguments().get(i),
                                declType.getTypeArguments().get().get(i),
                                null);
                    }
                }
                break;
            case WILDCARD:
                AnnotatedWildcardType wildcardType = (AnnotatedWildcardType) atype;
                // Ensure that the file also has a wildcard type, report an error otherwise
                if (!typeDef.isWildcardType()) {
                    // We throw an error here, as otherwise we are just getting a generic cast error
                    // on the very next line.
                    warn(
                            "Wildcard type <"
                                    + atype
                                    + "> does not match type in stubs file"
                                    + filename
                                    + ": <"
                                    + typeDef
                                    + ">"
                                    + " while parsing "
                                    + typeBeingParsed);
                    return;
                }
                WildcardType wildcardDef = (WildcardType) typeDef;
                if (wildcardDef.getExtendedType().isPresent()) {
                    annotate(
                            wildcardType.getExtendsBound(),
                            wildcardDef.getExtendedType().get(),
                            null);
                    annotate(wildcardType.getSuperBound(), primaryAnnotations);
                } else if (wildcardDef.getSuperType().isPresent()) {
                    annotate(wildcardType.getSuperBound(), wildcardDef.getSuperType().get(), null);
                    annotate(wildcardType.getExtendsBound(), primaryAnnotations);
                } else {
                    annotate(atype, primaryAnnotations);
                }
                break;
            case TYPEVAR:
                // Add annotations from the declaration of the TypeVariable
                AnnotatedTypeVariable typeVarUse = (AnnotatedTypeVariable) atype;
                Types typeUtils = processingEnv.getTypeUtils();
                for (AnnotatedTypeVariable typePar : typeParameters) {
                    if (typeUtils.isSameType(
                            typePar.getUnderlyingType(), atype.getUnderlyingType())) {
                        AnnotatedTypeReplacer.replace(
                                typePar.getUpperBound(), typeVarUse.getUpperBound());
                        AnnotatedTypeReplacer.replace(
                                typePar.getLowerBound(), typeVarUse.getLowerBound());
                    }
                }
                break;
            default:
                // No additional annotations to add.
        }
    }

    /**
     * Process the field declaration in decl, and attach any type qualifiers to the type of elt in
     * {@code stubAnnos}.
     *
     * @param decl the declaration in the annotation file
     * @param elt the element representing that same declaration
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processField(
            FieldDeclaration decl, VariableElement elt, StubAnnotations stubAnnos) {
        if (isJdkAsStub && decl.getModifiers().contains(Modifier.privateModifier())) {
            // Don't process private fields of the JDK.  They can't be referenced outside of the JDK
            // and might refer to types that are not accessible.
            return;
        }
        recordDeclAnnotationFromAnnotationFile(elt, stubAnnos);
        recordDeclAnnotation(elt, decl.getAnnotations(), stubAnnos);
        // AnnotationFileParser parses all annotations in type annotation position as type
        // annotations
        recordDeclAnnotation(elt, decl.getElementType().getAnnotations(), stubAnnos);
        AnnotatedTypeMirror fieldType = atypeFactory.fromElement(elt);

        VariableDeclarator fieldVarDecl = null;
        String eltName = elt.getSimpleName().toString();
        for (VariableDeclarator var : decl.getVariables()) {
            if (var.getName().toString().equals(eltName)) {
                fieldVarDecl = var;
                break;
            }
        }
        assert fieldVarDecl != null;
        annotate(fieldType, fieldVarDecl.getType(), decl.getAnnotations());
        putMerge(stubAnnos.atypes, elt, fieldType);
    }

    /**
     * Adds the annotations present on the declaration of an enum constant to the ATM of that
     * constant.
     *
     * @param decl the enum constant, in Javaparser AST form (the source of annotations)
     * @param elt the enum constant declaration, as an element (the destination for annotations)
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void processEnumConstant(
            EnumConstantDeclaration decl, VariableElement elt, StubAnnotations stubAnnos) {
        recordDeclAnnotationFromAnnotationFile(elt, stubAnnos);
        recordDeclAnnotation(elt, decl.getAnnotations(), stubAnnos);
        AnnotatedTypeMirror enumConstType = atypeFactory.fromElement(elt);
        annotate(enumConstType, decl.getAnnotations());
        putMerge(stubAnnos.atypes, elt, enumConstType);
    }

    /**
     * Returns the innermost component type of {@code type}.
     *
     * @param type array type
     * @return the innermost component type of {@code type}
     */
    private AnnotatedTypeMirror innermostComponentType(AnnotatedArrayType type) {
        AnnotatedTypeMirror componentType = type;
        while (componentType.getKind() == TypeKind.ARRAY) {
            componentType = ((AnnotatedArrayType) componentType).getComponentType();
        }
        return componentType;
    }

    /**
     * Adds {@code annotations} to the innermost component type of {@code type}.
     *
     * @param type array type
     * @param annotations annotations to add
     */
    private void annotateInnermostComponentType(
            AnnotatedArrayType type, List<AnnotationExpr> annotations) {
        annotate(innermostComponentType(type), annotations);
    }

    /**
     * Annotate the type with the given type annotations, removing any existing annotations from the
     * same qualifier hierarchies.
     */
    private void annotate(AnnotatedTypeMirror type, List<AnnotationExpr> annotations) {
        if (annotations == null) {
            return;
        }
        for (AnnotationExpr annotation : annotations) {
            AnnotationMirror annoMirror = getAnnotation(annotation, allAnnotations);
            if (annoMirror != null) {
                type.replaceAnnotation(annoMirror);
            } else {
                stubWarnNotFound("Unknown annotation: " + annotation);
            }
        }
    }

    /**
     * Adds to {@code stubAnnos} all the annotations in {@code annotations} that are applicable to
     * {@code elt}'s location. For example, if an annotation is a type annotation but {@code elt} is
     * a field declaration, the type annotation will be ignored.
     *
     * @param elt the element to be annotated
     * @param annotations set of annotations that may be applicable to elt
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void recordDeclAnnotation(
            Element elt, List<AnnotationExpr> annotations, StubAnnotations stubAnnos) {
        if (annotations == null) {
            return;
        }
        Set<AnnotationMirror> annos = AnnotationUtils.createAnnotationSet();
        for (AnnotationExpr annotation : annotations) {
            AnnotationMirror annoMirror = getAnnotation(annotation, allAnnotations);
            if (annoMirror != null) {
                // The @Target annotation on `annotation`/`annoMirror`
                Target target =
                        annoMirror.getAnnotationType().asElement().getAnnotation(Target.class);
                // Only add the declaration annotation if the annotation applies to the element.
                if (AnnotationUtils.getElementKindsForTarget(target).contains(elt.getKind())) {
                    // `annoMirror` is applicable to `elt`
                    annos.add(annoMirror);
                }
            }
        }
        String eltName = ElementUtils.getQualifiedName(elt);
        putOrAddToMap(stubAnnos.declAnnos, eltName, annos);
    }

    /**
     * Adds the declaration annotation {@code @FromStubFile} to the given element, unless we are
     * parsing the JDK as a stub file.
     *
     * @param elt an element to be annotated as {@code @FromStubFile}
     * @param stubAnnos annotations from the stub file; side-effected by this method
     */
    private void recordDeclAnnotationFromAnnotationFile(Element elt, StubAnnotations stubAnnos) {
        if (isJdkAsStub) {
            return;
        }
        putOrAddToMap(
                stubAnnos.declAnnos,
                ElementUtils.getQualifiedName(elt),
                Collections.singleton(fromStubFileAnno));
    }

    private void annotateTypeParameters(
            BodyDeclaration<?> decl, // for debugging
            Object elt, // for debugging; TypeElement or ExecutableElement
            StubAnnotations stubAnnos,
            List<? extends AnnotatedTypeMirror> typeArguments,
            List<TypeParameter> typeParameters) {
        if (typeParameters == null) {
            return;
        }

        if (typeParameters.size() != typeArguments.size()) {
            String msg =
                    String.format(
                            "annotateTypeParameters: mismatched sizes:  typeParameters (size %d)=%s;  typeArguments (size %d)=%s;  decl=%s;  elt=%s (%s).",
                            typeParameters.size(),
                            typeParameters,
                            typeArguments.size(),
                            typeArguments,
                            decl.toString().replace(LINE_SEPARATOR, " "),
                            elt.toString().replace(LINE_SEPARATOR, " "),
                            elt.getClass());
            if (!debugAnnotationFileParser) {
                msg = msg + "; for more details, run with -AstubDebug";
            }
            warn(msg);
            return;
        }
        for (int i = 0; i < typeParameters.size(); ++i) {
            TypeParameter param = typeParameters.get(i);
            AnnotatedTypeVariable paramType = (AnnotatedTypeVariable) typeArguments.get(i);

            if (param.getTypeBound() == null || param.getTypeBound().isEmpty()) {
                // No bound so annotations are both lower and upper bounds
                annotate(paramType, param.getAnnotations());
            } else if (param.getTypeBound() != null && !param.getTypeBound().isEmpty()) {
                annotate(paramType.getLowerBound(), param.getAnnotations());
                annotate(paramType.getUpperBound(), param.getTypeBound().get(0), null);
                if (param.getTypeBound().size() > 1) {
                    // TODO: add support for intersection types
                    stubWarnNotFound("Annotations on intersection types are not yet supported");
                }
            }
            putMerge(stubAnnos.atypes, paramType.getUnderlyingType().asElement(), paramType);
        }
    }

    /**
     * For each member of the JavaParser type declaration {@code typeDecl}:
     *
     * <ul>
     *   <li>If {@code typeElt} contains a member element for it, returns a mapping from the member
     *       element to it.
     *   <li>Otherwise, does nothing.
     * </ul>
     *
     * @param typeDecl a JavaParser type declaration
     * @param typeElt the element for {@code typeDecl}
     * @return a mapping from elements to their declaration in a stub file
     */
    private Map<Element, BodyDeclaration<?>> getMembers(
            TypeDeclaration<?> typeDecl, TypeElement typeElt) {
        assert (typeElt.getSimpleName().contentEquals(typeDecl.getNameAsString())
                        || typeDecl.getNameAsString().endsWith("$" + typeElt.getSimpleName()))
                : String.format("%s  %s", typeElt.getSimpleName(), typeDecl.getName());

        Map<Element, BodyDeclaration<?>> result = new LinkedHashMap<>();
        for (BodyDeclaration<?> member : typeDecl.getMembers()) {
            putNewElement(result, typeElt, member, typeDecl.getNameAsString());
        }
        // For an enum type declaration, also add the enum constants
        if (typeDecl instanceof EnumDeclaration) {
            EnumDeclaration enumDecl = (EnumDeclaration) typeDecl;
            // getEntries() gives the list of enum constant declarations
            for (BodyDeclaration<?> member : enumDecl.getEntries()) {
                putNewElement(result, typeElt, member, typeDecl.getNameAsString());
            }
        }
        return result;
    }

    // Used only by getMembers
    /**
     * If {@code typeElt} contains an element for {@code member}, adds to {@code elementsToDecl} a
     * mapping from member's element to member. Does nothing if a mapping already exists.
     *
     * <p>Does nothing if it cannot find member's element.
     *
     * @param elementsToDecl the mapping that is side-effected by this method
     * @param typeElt the class in which {@code member} is declared
     * @param member the stub file declaration of a method
     * @param typeDeclName used only for debugging
     */
    private void putNewElement(
            Map<Element, BodyDeclaration<?>> elementsToDecl,
            TypeElement typeElt,
            BodyDeclaration<?> member,
            String typeDeclName) {
        if (member instanceof MethodDeclaration) {
            Element elt = findElement(typeElt, (MethodDeclaration) member);
            if (elt != null) {
                putIfAbsent(elementsToDecl, elt, member);
            }
        } else if (member instanceof ConstructorDeclaration) {
            Element elt = findElement(typeElt, (ConstructorDeclaration) member);
            if (elt != null) {
                putIfAbsent(elementsToDecl, elt, member);
            }
        } else if (member instanceof FieldDeclaration) {
            FieldDeclaration fieldDecl = (FieldDeclaration) member;
            for (VariableDeclarator var : fieldDecl.getVariables()) {
                Element varelt = findElement(typeElt, var);
                if (varelt != null) {
                    putIfAbsent(elementsToDecl, varelt, fieldDecl);
                }
            }
        } else if (member instanceof EnumConstantDeclaration) {
            Element elt = findElement(typeElt, (EnumConstantDeclaration) member);
            if (elt != null) {
                putIfAbsent(elementsToDecl, elt, member);
            }
        } else if (member instanceof ClassOrInterfaceDeclaration) {
            Element elt = findElement(typeElt, (ClassOrInterfaceDeclaration) member);
            if (elt != null) {
                putIfAbsent(elementsToDecl, elt, member);
            }
        } else if (member instanceof EnumDeclaration) {
            Element elt = findElement(typeElt, (EnumDeclaration) member);
            if (elt != null) {
                putIfAbsent(elementsToDecl, elt, member);
            }
        } else {
            stubDebug(
                    String.format(
                            "Ignoring element of type %s in %s", member.getClass(), typeDeclName));
        }
    }

    /**
     * Return the annotated type corresponding to {@code type}, or null if none exists. More
     * specifically, returns the element of {@code types} whose name matches {@code type}.
     *
     * @param type the type to search for
     * @param types the list of AnnotatedDeclaredTypes to search in
     * @return the annotated type in {@code types} corresponding to {@code type}, or null if none
     *     exists
     */
    private @Nullable AnnotatedDeclaredType findAnnotatedType(
            ClassOrInterfaceType type, List<AnnotatedDeclaredType> types) {
        String typeString = type.getNameAsString();
        for (AnnotatedDeclaredType supertype : types) {
            if (supertype
                    .getUnderlyingType()
                    .asElement()
                    .getSimpleName()
                    .contentEquals(typeString)) {
                return supertype;
            }
        }
        stubWarnNotFound("Supertype " + typeString + " not found");
        if (debugAnnotationFileParser) {
            stubDebug("Supertypes that were searched:");
            for (AnnotatedDeclaredType supertype : types) {
                stubDebug(String.format("  %s", supertype));
            }
        }
        return null;
    }

    /**
     * Looks for the nested type element in the typeElt and returns it if the element has the same
     * name as provided class or interface declaration. In case nested element is not found it
     * returns null.
     *
     * @param typeElt an element where nested type element should be looked for
     * @param ciDecl class or interface declaration which name should be found among nested elements
     *     of the typeElt
     * @return nested in typeElt element with the name of the class or interface or null if nested
     *     element is not found
     */
    private @Nullable Element findElement(TypeElement typeElt, ClassOrInterfaceDeclaration ciDecl) {
        final String wantedClassOrInterfaceName = ciDecl.getNameAsString();
        for (TypeElement typeElement : ElementUtils.getAllTypeElementsIn(typeElt)) {
            if (wantedClassOrInterfaceName.equals(typeElement.getSimpleName().toString())) {
                return typeElement;
            }
        }

        stubWarnNotFound(
                "Class/interface " + wantedClassOrInterfaceName + " not found in type " + typeElt);
        if (debugAnnotationFileParser) {
            stubDebug(String.format("  Here are the type declarations of %s:", typeElt));
            for (TypeElement method : ElementFilter.typesIn(typeElt.getEnclosedElements())) {
                stubDebug(String.format("    %s", method));
            }
        }
        return null;
    }

    /**
     * Looks for the nested enum element in the typeElt and returns it if the element has the same
     * name as provided enum declaration. In case nested element is not found it returns null.
     *
     * @param typeElt an element where nested enum element should be looked for
     * @param enumDecl enum declaration which name should be found among nested elements of the
     *     typeElt
     * @return nested in typeElt enum element with the name of the provided enum or null if nested
     *     element is not found
     */
    private @Nullable Element findElement(TypeElement typeElt, EnumDeclaration enumDecl) {
        final String wantedEnumName = enumDecl.getNameAsString();
        for (TypeElement typeElement : ElementUtils.getAllTypeElementsIn(typeElt)) {
            if (wantedEnumName.equals(typeElement.getSimpleName().toString())) {
                return typeElement;
            }
        }

        stubWarnNotFound("Enum " + wantedEnumName + " not found in type " + typeElt);
        if (debugAnnotationFileParser) {
            stubDebug(String.format("  Here are the type declarations of %s:", typeElt));
            for (TypeElement method : ElementFilter.typesIn(typeElt.getEnclosedElements())) {
                stubDebug(String.format("    %s", method));
            }
        }
        return null;
    }

    /**
     * Looks for an enum constant element in the typeElt and returns it if the element has the same
     * name as provided. In case enum constant element is not found it returns null.
     *
     * @param typeElt type element where enum constant element should be looked for
     * @param enumConstDecl the declaration of the enum constant
     * @return enum constant element in typeElt with the provided name or null if enum constant
     *     element is not found
     */
    private @Nullable VariableElement findElement(
            TypeElement typeElt, EnumConstantDeclaration enumConstDecl) {
        final String enumConstName = enumConstDecl.getNameAsString();
        return findFieldElement(typeElt, enumConstName);
    }

    /**
     * Looks for method element in the typeElt and returns it if the element has the same signature
     * as provided method declaration. Returns null, and possibly issues a warning, if method
     * element is not found.
     *
     * @param typeElt type element where method element should be looked for
     * @param methodDecl method declaration with signature that should be found among methods in the
     *     typeElt
     * @return method element in typeElt with the same signature as the provided method declaration
     *     or null if method element is not found
     */
    private @Nullable ExecutableElement findElement(
            TypeElement typeElt, MethodDeclaration methodDecl) {
        if (isJdkAsStub && methodDecl.getModifiers().contains(Modifier.privateModifier())) {
            // Don't process private methods of the JDK.  They can't be referenced outside of the
            // JDK and might refer to types that are not accessible.
            return null;
        }
        final String wantedMethodName = methodDecl.getNameAsString();
        final int wantedMethodParams =
                (methodDecl.getParameters() == null) ? 0 : methodDecl.getParameters().size();
        final String wantedMethodString = AnnotationFileUtil.toString(methodDecl);
        for (ExecutableElement method : ElementFilter.methodsIn(typeElt.getEnclosedElements())) {
            if (wantedMethodParams == method.getParameters().size()
                    && wantedMethodName.contentEquals(method.getSimpleName().toString())
                    && ElementUtils.getSimpleSignature(method).equals(wantedMethodString)) {
                return method;
            }
        }
        if (methodDecl.getAccessSpecifier() == AccessSpecifier.PACKAGE_PRIVATE) {
            // This might be a false positive warning.  The stub parser permits a stub file to omit
            // the access specifier, but package-private methods aren't in the TypeElement.
            stubWarnNotFound(
                    "Package-private method "
                            + wantedMethodString
                            + " not found in type "
                            + typeElt);
        } else {
            stubWarnNotFound("Method " + wantedMethodString + " not found in type " + typeElt);
            if (debugAnnotationFileParser) {
                stubDebug(String.format("  Here are the methods of %s:", typeElt));
                for (ExecutableElement method :
                        ElementFilter.methodsIn(typeElt.getEnclosedElements())) {
                    stubDebug(String.format("    %s", method));
                }
            }
        }
        return null;
    }

    /**
     * Looks for a constructor element in the typeElt and returns it if the element has the same
     * signature as provided constructor declaration. In case constructor element is not found it
     * returns null.
     *
     * @param typeElt type element where constructor element should be looked for
     * @param constructorDecl constructor declaration with signature that should be found among
     *     constructors in the typeElt
     * @return constructor element in typeElt with the same signature as the provided constructor
     *     declaration or null if constructor element is not found
     */
    private @Nullable ExecutableElement findElement(
            TypeElement typeElt, ConstructorDeclaration constructorDecl) {
        if (isJdkAsStub && constructorDecl.getModifiers().contains(Modifier.privateModifier())) {
            // Don't process private constructors of the JDK.  They can't be referenced outside of
            // the JDK and might refer to types that are not accessible.
            return null;
        }
        final int wantedMethodParams =
                (constructorDecl.getParameters() == null)
                        ? 0
                        : constructorDecl.getParameters().size();
        final String wantedMethodString = AnnotationFileUtil.toString(constructorDecl);
        for (ExecutableElement method :
                ElementFilter.constructorsIn(typeElt.getEnclosedElements())) {
            if (wantedMethodParams == method.getParameters().size()
                    && ElementUtils.getSimpleSignature(method).equals(wantedMethodString)) {
                return method;
            }
        }

        stubWarnNotFound("Constructor " + wantedMethodString + " not found in type " + typeElt);
        if (debugAnnotationFileParser) {
            for (ExecutableElement method :
                    ElementFilter.constructorsIn(typeElt.getEnclosedElements())) {
                stubDebug(String.format("  %s", method));
            }
        }
        return null;
    }

    private VariableElement findElement(TypeElement typeElt, VariableDeclarator variable) {
        final String fieldName = variable.getNameAsString();
        return findFieldElement(typeElt, fieldName);
    }

    /**
     * Looks for a field element in the typeElt and returns it if the element has the same name as
     * provided. In case field element is not found it returns null.
     *
     * @param typeElt type element where field element should be looked for
     * @param fieldName field name that should be found
     * @return field element in typeElt with the provided name or null if field element is not found
     */
    private @Nullable VariableElement findFieldElement(TypeElement typeElt, String fieldName) {
        for (VariableElement field : ElementUtils.getAllFieldsIn(typeElt, elements)) {
            // field.getSimpleName() is a CharSequence, not a String
            if (fieldName.equals(field.getSimpleName().toString())) {
                return field;
            }
        }

        stubWarnNotFound("Field " + fieldName + " not found in type " + typeElt);
        if (debugAnnotationFileParser) {
            for (VariableElement field : ElementFilter.fieldsIn(typeElt.getEnclosedElements())) {
                stubDebug(String.format("  %s", field));
            }
        }
        return null;
    }

    /**
     * Given a fully-qualified type name, return a TypeElement for it, or null if none exists. Also
     * cache in importedTypes.
     *
     * @param name a fully-qualified type name
     * @return a TypeElement for the name, or null
     */
    private @Nullable TypeElement getTypeElementOrNull(@FullyQualifiedName String name) {
        TypeElement typeElement = elements.getTypeElement(name);
        if (typeElement != null) {
            importedTypes.put(name, typeElement);
        }
        // for debugging: warn("getTypeElementOrNull(%s) => %s", name, typeElement);
        return typeElement;
    }

    /**
     * Get the type element for the given fully-qualified type name. If none is found, issue a
     * warning and return null.
     *
     * @param typeName a type name
     * @param msg a warning message to issue if the type element for {@code typeName} cannot be
     *     found
     * @return the type element for the given fully-qualified type name, or null
     */
    private TypeElement getTypeElement(@FullyQualifiedName String typeName, String... msg) {
        TypeElement classElement = elements.getTypeElement(typeName);
        if (classElement == null) {
            if (msg.length == 0) {
                stubWarnNotFound("Type not found: " + typeName);
            } else {
                stubWarnNotFound(msg[0] + ": " + typeName);
            }
        }
        return classElement;
    }

    private PackageElement findPackage(String packageName) {
        PackageElement packageElement = elements.getPackageElement(packageName);
        if (packageElement == null) {
            stubWarnNotFound("Imported package not found: " + packageName);
        }
        return packageElement;
    }

    /**
     * Convert {@code annotation} into an AnnotationMirror. Returns null if the annotation isn't
     * supported by the checker or if some error occurred while converting it.
     *
     * @param annotation syntax tree for an annotation
     * @param allAnnotations map from simple name to annotation definition; side-effected by this
     *     method
     * @return the AnnotationMirror for the annotation
     */
    private AnnotationMirror getAnnotation(
            AnnotationExpr annotation, Map<String, TypeElement> allAnnotations) {

        @SuppressWarnings("signature") // https://tinyurl.com/cfissue/3094
        @FullyQualifiedName String annoNameFq = annotation.getNameAsString();
        TypeElement annoTypeElt = allAnnotations.get(annoNameFq);
        if (annoTypeElt == null) {
            // If the annotation was not imported, then #getallAnnotations did not add it to the
            // allAnnotations field. This code adds the annotation when it is encountered
            // (i.e. here).
            // Note that this goes not call AnnotationFileParser#getTypeElement to avoid a spurious
            // diagnostic
            // if the annotation is actually unknown.
            annoTypeElt = elements.getTypeElement(annoNameFq);
            if (annoTypeElt == null) {
                // Not a supported annotation -> ignore
                return null;
            }
            putAllNew(
                    allAnnotations,
                    createNameToAnnotationMap(Collections.singletonList(annoTypeElt)));
        }
        @SuppressWarnings("signature") // not anonymous, so name is not empty
        @CanonicalName String annoName = annoTypeElt.getQualifiedName().toString();

        if (annotation instanceof MarkerAnnotationExpr) {
            return AnnotationBuilder.fromName(elements, annoName);
        } else if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr nrmanno = (NormalAnnotationExpr) annotation;
            AnnotationBuilder builder = new AnnotationBuilder(processingEnv, annoName);
            List<MemberValuePair> pairs = nrmanno.getPairs();
            if (pairs != null) {
                for (MemberValuePair mvp : pairs) {
                    String member = mvp.getNameAsString();
                    Expression exp = mvp.getValue();
                    boolean success = builderAddElement(builder, member, exp);
                    if (!success) {
                        warn(
                                "Annotation expression, %s, could not be processed for annotation: %s.",
                                exp, annotation);
                        return null;
                    }
                }
            }
            return builder.build();
        } else if (annotation instanceof SingleMemberAnnotationExpr) {
            SingleMemberAnnotationExpr sglanno = (SingleMemberAnnotationExpr) annotation;
            AnnotationBuilder builder = new AnnotationBuilder(processingEnv, annoName);
            Expression valexpr = sglanno.getMemberValue();
            boolean success = builderAddElement(builder, "value", valexpr);
            if (!success) {
                warn(
                        "Annotation expression, %s, could not be processed for annotation: %s.",
                        valexpr, annotation);
                return null;
            }
            return builder.build();
        } else {
            throw new BugInCF("AnnotationFileParser: unknown annotation type: " + annotation);
        }
    }

    /**
     * Returns the value of {@code expr}, or null if some problem occurred getting the value.
     *
     * @param name the name of an annotation element/argument, used for diagnostic messages
     * @param expr the expression to determine the value of
     * @param valueKind the type of the result
     * @return the value of {@code expr}, or null if some problem occurred getting the value
     */
    private @Nullable Object getValueOfExpressionInAnnotation(
            String name, Expression expr, TypeKind valueKind) {
        if (expr instanceof FieldAccessExpr || expr instanceof NameExpr) {
            VariableElement elem;
            if (expr instanceof NameExpr) {
                elem = findVariableElement((NameExpr) expr);
            } else {
                elem = findVariableElement((FieldAccessExpr) expr);
            }
            if (elem == null) {
                warn("Field not found: " + expr);
                return null;
            }
            Object value = elem.getConstantValue() != null ? elem.getConstantValue() : elem;
            if (value instanceof Number) {
                return convert((Number) value, valueKind);
            } else {
                return value;
            }
        } else if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).asString();
        } else if (expr instanceof BooleanLiteralExpr) {
            return ((BooleanLiteralExpr) expr).getValue();
        } else if (expr instanceof CharLiteralExpr) {
            return convert((int) ((CharLiteralExpr) expr).asChar(), valueKind);
        } else if (expr instanceof DoubleLiteralExpr) {
            // No conversion needed if the expression is a double, the annotation value must be a
            // double, too.
            return ((DoubleLiteralExpr) expr).asDouble();
        } else if (expr instanceof IntegerLiteralExpr) {
            return convert(((IntegerLiteralExpr) expr).asNumber(), valueKind);
        } else if (expr instanceof LongLiteralExpr) {
            return convert(((LongLiteralExpr) expr).asNumber(), valueKind);
        } else if (expr instanceof UnaryExpr) {
            switch (expr.toString()) {
                    // Special-case the minimum values.  Separately parsing a "-" and a value
                    // doesn't correctly handle the minimum values, because the absolute value of
                    // the smallest member of an integral type is larger than the largest value.
                case "-9223372036854775808L":
                case "-9223372036854775808l":
                    return convert(Long.MIN_VALUE, valueKind, false);
                case "-2147483648":
                    return convert(Integer.MIN_VALUE, valueKind, false);
                default:
                    if (((UnaryExpr) expr).getOperator() == UnaryExpr.Operator.MINUS) {
                        Object value =
                                getValueOfExpressionInAnnotation(
                                        name, ((UnaryExpr) expr).getExpression(), valueKind);
                        if (value instanceof Number) {
                            return convert((Number) value, valueKind, true);
                        }
                    }
                    warn("Unexpected Unary annotation expression: " + expr);
                    return null;
            }
        } else if (expr instanceof ClassExpr) {
            ClassExpr classExpr = (ClassExpr) expr;
            @SuppressWarnings("signature") // Type.toString(): @FullyQualifiedName
            @FullyQualifiedName String className = classExpr.getType().toString();
            if (importedTypes.containsKey(className)) {
                return importedTypes.get(className).asType();
            }
            TypeElement typeElement = findTypeOfName(className);
            if (typeElement == null) {
                warn("AnnotationFileParser: unknown class name " + className);
                return null;
            }

            return typeElement.asType();
        } else if (expr instanceof NullLiteralExpr) {
            warn("Illegal annotation value null, for %s", name);
            return null;
        } else {
            warn("Unexpected annotation expression: " + expr);
            return null;
        }
    }

    /**
     * Returns the TypeElement with the name {@code name}, if one exists. Otherwise, checks the
     * class and package of {@code typeBeingParsed} for a class named {@code name}.
     *
     * @param name classname (simple, or Outer.Inner, or fully-qualified)
     * @return the TypeElement for {@code name}, or null if not found
     */
    @SuppressWarnings("signature:argument.type.incompatible") // string concatenation
    private @Nullable TypeElement findTypeOfName(@FullyQualifiedName String name) {
        String packageName = typeBeingParsed.packageName;
        String packagePrefix = (packageName == null) ? "" : packageName + ".";

        // warn("findTypeOfName(%s), typeBeingParsed %s %s", name, packageName, enclosingClass);

        // As soon as typeElement is set to a non-null value, it will be returned.
        TypeElement typeElement = getTypeElementOrNull(name);
        if (typeElement == null && packageName != null) {
            typeElement = getTypeElementOrNull(packagePrefix + name);
        }
        String enclosingClass = typeBeingParsed.className;
        while (typeElement == null && enclosingClass != null) {
            typeElement = getTypeElementOrNull(packagePrefix + enclosingClass + "." + name);
            int lastDot = enclosingClass.lastIndexOf('.');
            if (lastDot == -1) {
                break;
            } else {
                enclosingClass = enclosingClass.substring(0, lastDot);
            }
        }
        if (typeElement == null && !"java.lang".equals(packageName)) {
            typeElement = getTypeElementOrNull("java.lang." + name);
        }
        return typeElement;
    }

    /**
     * Converts {@code number} to {@code expectedKind}.
     *
     * <pre><code>
     * &nbsp; @interface Anno { long value(); }
     * &nbsp; @Anno(1)
     * </code></pre>
     *
     * To properly build @Anno, the IntegerLiteralExpr "1" must be converted from an int to a long.
     */
    private Object convert(Number number, TypeKind expectedKind) {
        return convert(number, expectedKind, false);
    }

    /**
     * Converts {@code number} to {@code expectedKind}. The value converted is multiplied by -1 if
     * {@code negate} is true
     *
     * @param number a Number value to be converted
     * @param expectedKind one of type {byte, short, int, long, char, float, double}
     * @param negate whether to negate the value of the Number Object while converting
     * @return the converted Object
     */
    private Object convert(Number number, TypeKind expectedKind, boolean negate) {
        byte scalefactor = (byte) (negate ? -1 : 1);
        switch (expectedKind) {
            case BYTE:
                return number.byteValue() * scalefactor;
            case SHORT:
                return number.shortValue() * scalefactor;
            case INT:
                return number.intValue() * scalefactor;
            case LONG:
                return number.longValue() * scalefactor;
            case CHAR:
                // It's not possible for `number` to be negative when `expectedkind` is a CHAR, and
                // casting a negative value to char is illegal.
                if (negate) {
                    throw new BugInCF(
                            "convert(%s, %s, %s): can't negate a char",
                            number, expectedKind, negate);
                }
                return (char) number.intValue();
            case FLOAT:
                return number.floatValue() * scalefactor;
            case DOUBLE:
                return number.doubleValue() * scalefactor;
            default:
                throw new BugInCF("Unexpected expectedKind: " + expectedKind);
        }
    }

    /**
     * Adds an annotation element (argument) to {@code builder}. The element is a Java expression.
     *
     * @param builder the builder to side-effect
     * @param name the element name
     * @param expr the element value
     * @return true if the expression was parsed and added to {@code builder}, false otherwise
     */
    private boolean builderAddElement(AnnotationBuilder builder, String name, Expression expr) {
        ExecutableElement var = builder.findElement(name);
        TypeMirror expected = var.getReturnType();
        TypeKind valueKind;
        if (expected.getKind() == TypeKind.ARRAY) {
            valueKind = ((ArrayType) expected).getComponentType().getKind();
        } else {
            valueKind = expected.getKind();
        }
        if (expr instanceof ArrayInitializerExpr) {
            if (expected.getKind() != TypeKind.ARRAY) {
                warn("unhandled annotation attribute type: " + expr + " and expected: " + expected);
                return false;
            }

            List<Expression> arrayExpressions = ((ArrayInitializerExpr) expr).getValues();
            Object[] values = new Object[arrayExpressions.size()];

            for (int i = 0; i < arrayExpressions.size(); ++i) {
                values[i] =
                        getValueOfExpressionInAnnotation(name, arrayExpressions.get(i), valueKind);
                if (values[i] == null) {
                    return false;
                }
            }
            builder.setValue(name, values);
        } else {
            Object value = getValueOfExpressionInAnnotation(name, expr, valueKind);
            if (value == null) {
                return false;
            }
            if (expected.getKind() == TypeKind.ARRAY) {
                Object[] valueArray = {value};
                builder.setValue(name, valueArray);
            } else {
                builderSetValue(builder, name, value);
            }
        }
        return true;
    }

    /**
     * Cast to non-array values so that correct the correct AnnotationBuilder#setValue method is
     * called. (Different types of values are handled differently.)
     *
     * @param builder the builder to side-effect
     * @param name the element name
     * @param value the element value
     */
    private void builderSetValue(AnnotationBuilder builder, String name, Object value) {
        if (value instanceof Boolean) {
            builder.setValue(name, (Boolean) value);
        } else if (value instanceof Character) {
            builder.setValue(name, (Character) value);
        } else if (value instanceof Class<?>) {
            builder.setValue(name, (Class<?>) value);
        } else if (value instanceof Double) {
            builder.setValue(name, (Double) value);
        } else if (value instanceof Enum<?>) {
            builder.setValue(name, (Enum<?>) value);
        } else if (value instanceof Float) {
            builder.setValue(name, (Float) value);
        } else if (value instanceof Integer) {
            builder.setValue(name, (Integer) value);
        } else if (value instanceof Long) {
            builder.setValue(name, (Long) value);
        } else if (value instanceof Short) {
            builder.setValue(name, (Short) value);
        } else if (value instanceof String) {
            builder.setValue(name, (String) value);
        } else if (value instanceof TypeMirror) {
            builder.setValue(name, (TypeMirror) value);
        } else if (value instanceof VariableElement) {
            builder.setValue(name, (VariableElement) value);
        } else {
            throw new BugInCF("Unexpected builder value: %s", value);
        }
    }

    /**
     * Mapping of a name access expression that has already been encountered to the resolved
     * variable element.
     */
    private final Map<NameExpr, VariableElement> findVariableElementNameCache = new HashMap<>();

    private @Nullable VariableElement findVariableElement(NameExpr nexpr) {
        if (findVariableElementNameCache.containsKey(nexpr)) {
            return findVariableElementNameCache.get(nexpr);
        }

        VariableElement res = null;
        boolean importFound = false;
        for (String imp : importedConstants) {
            Pair<@FullyQualifiedName String, String> partitionedName =
                    AnnotationFileUtil.partitionQualifiedName(imp);
            String typeName = partitionedName.first;
            String fieldName = partitionedName.second;
            if (fieldName.equals(nexpr.getNameAsString())) {
                TypeElement enclType =
                        getTypeElement(
                                typeName,
                                String.format(
                                        "Enclosing type of static import %s not found", fieldName));

                if (enclType == null) {
                    return null;
                } else {
                    importFound = true;
                    res = findFieldElement(enclType, fieldName);
                    break;
                }
            }
        }

        // Imported but invalid types or fields will have warnings from above,
        // only warn on fields missing an import
        if (res == null && !importFound) {
            stubWarnNotFound("Static field " + nexpr.getName() + " is not imported");
        }

        findVariableElementNameCache.put(nexpr, res);
        return res;
    }

    /**
     * Mapping of a field access expression that has already been encountered to the resolved
     * variable element.
     */
    private final Map<FieldAccessExpr, VariableElement> findVariableElementFieldCache =
            new HashMap<>();

    /**
     * Returns the VariableElement for the given field access.
     *
     * @param faexpr a field access expression
     * @return the VariableElement for the given field access
     */
    @SuppressWarnings("signature:argument.type.incompatible") // string manipulation
    private @Nullable VariableElement findVariableElement(FieldAccessExpr faexpr) {
        if (findVariableElementFieldCache.containsKey(faexpr)) {
            return findVariableElementFieldCache.get(faexpr);
        }
        TypeElement rcvElt = elements.getTypeElement(faexpr.getScope().toString());
        if (rcvElt == null) {
            // Search importedConstants for full annotation name.
            for (String imp : importedConstants) {
                // TODO: should this use AnnotationFileUtil.partitionQualifiedName?
                String[] importDelimited = imp.split("\\.");
                if (importDelimited[importDelimited.length - 1].equals(
                        faexpr.getScope().toString())) {
                    StringBuilder fullAnnotation = new StringBuilder();
                    for (int i = 0; i < importDelimited.length - 1; i++) {
                        fullAnnotation.append(importDelimited[i]);
                        fullAnnotation.append('.');
                    }
                    fullAnnotation.append(faexpr.getScope().toString());
                    rcvElt = elements.getTypeElement(fullAnnotation);
                    break;
                }
            }

            if (rcvElt == null) {
                stubWarnNotFound("Type " + faexpr.getScope() + " not found");
                return null;
            }
        }

        VariableElement res = findFieldElement(rcvElt, faexpr.getNameAsString());
        findVariableElementFieldCache.put(faexpr, res);
        return res;
    }

    ///////////////////////////////////////////////////////////////////////////
    /// Map utilities
    ///

    /**
     * Just like Map.put, but does not override any existing value in the map.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param m a map
     * @param key a key
     * @param value the value to associate with the key, if the key isn't already in the map
     */
    private static <K, V> void putIfAbsent(Map<K, V> m, K key, V value) {
        if (key == null) {
            throw new BugInCF("AnnotationFileParser: key is null for value " + value);
        }
        if (!m.containsKey(key)) {
            m.put(key, value);
        }
    }

    /**
     * If the key is already in the map, then add the annos to the list. Otherwise put the key and
     * the annos in the map
     */
    private static void putOrAddToMap(
            Map<String, Set<AnnotationMirror>> map, String key, Set<AnnotationMirror> annos) {
        if (map.containsKey(key)) {
            map.get(key).addAll(annos);
        } else {
            map.put(key, new HashSet<>(annos));
        }
    }

    /**
     * Just like Map.put, but modifies an existing annotated type for the given key in {@code m}. If
     * {@code m} already has an annotated type for {@code key}, each annotation in {@code newType}
     * will replace annotations from the same hierarchy at the same location in the existing
     * annotated type. Annotations in other hierarchies will be preserved.
     *
     * @param m the map to put the new type into
     * @param key the key for the map
     * @param newType the new type for the key
     */
    private void putMerge(
            Map<Element, AnnotatedTypeMirror> m, Element key, AnnotatedTypeMirror newType) {
        if (key == null) {
            throw new BugInCF("AnnotationFileParser: key is null");
        }
        if (m.containsKey(key)) {
            AnnotatedTypeMirror existingType = m.get(key);
            // If the newType is from a JDK stub file, then keep the existing type.  This
            // way user supplied stub files override JDK stub files.
            if (!isJdkAsStub) {
                AnnotatedTypeReplacer.replace(newType, existingType);
            }
            m.put(key, existingType);
        } else {
            m.put(key, newType);
        }
    }

    /**
     * Just like Map.putAll, but modifies existing values using {@link #putIfAbsent(Map, Object,
     * Object)}.
     *
     * @param m the destination map
     * @param m2 the source map
     * @param <K> the key type for the maps
     * @param <V> the value type for the maps
     */
    private static <K, V> void putAllNew(Map<K, V> m, Map<K, V> m2) {
        for (Map.Entry<K, V> e2 : m2.entrySet()) {
            putIfAbsent(m, e2.getKey(), e2.getValue());
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    /// Issue warnings
    ///

    /** The warnings that have been issued so far. */
    private static final Set<String> warnings = new HashSet<>();

    /**
     * Issues the given warning about missing elements, only if it has not been previously issued
     * and the -AstubWarnIfNotFound command-line argument was passed.
     *
     * @param warning warning to print
     */
    private void stubWarnNotFound(String warning) {
        if (warnings.add(warning)
                && ((!isJdkAsStub && warnIfNotFound) || debugAnnotationFileParser)) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.WARNING,
                            "AnnotationFileParser: " + warning);
        }
    }

    /**
     * Issues the given warning about overwriting bytecode, only if it has not been previously
     * issued and the -AstubWarnIfOverwritesBytecode command-line argument was passed.
     *
     * @param message the warning message to print
     */
    @SuppressWarnings("UnusedMethod") // not currently used
    private void stubWarnOverwritesBytecode(String message) {
        if (warnings.add(message) && (warnIfStubOverwritesBytecode || debugAnnotationFileParser)) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.WARNING,
                            "AnnotationFileParser: " + message);
        }
    }

    /**
     * Issues a warning, only if it has not been previously issued.
     *
     * @param warning a format string
     * @param args the arguments for {@code warning}
     */
    private void warn(String warning, Object... args) {
        warning = String.format(warning, args);
        if (warnings.add(warning) && !isJdkAsStub) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.WARNING,
                            "AnnotationFileParser: " + warning);
        }
    }

    /**
     * If {@code warning} hasn't been printed yet, and {@code debugAnnotationFileParser} is true,
     * prints the given warning as a diagnostic message.
     *
     * @param warning warning to print
     */
    private void stubDebug(String warning) {
        if (warnings.add(warning) && debugAnnotationFileParser) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.NOTE, "AnnotationFileParser: " + warning);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    /// Parse state
    ///

    /** Represents a class: its package name and name (including outer class names if any). */
    private static class FqName {
        /** Name of the package being parsed, or null. */
        public @Nullable String packageName;

        /**
         * Name of the type being parsed. Includes outer class names if any. Null if the parser has
         * parsed a package declaration but has not yet gotten to a type declaration.
         */
        public @Nullable String className;

        /**
         * Create a new FqName, which represents a class.
         *
         * @param packageName name of the package, or null
         * @param className unqualified name of the type, including outer class names if any. May be
         *     null.
         */
        public FqName(String packageName, @Nullable String className) {
            this.packageName = packageName;
            this.className = className;
        }

        /** Fully-qualified name of the class. */
        @Override
        @SuppressWarnings("signature") // string concatenation
        public @FullyQualifiedName String toString() {
            if (packageName == null) {
                return className;
            } else {
                return packageName + "." + className;
            }
        }
    }
}
