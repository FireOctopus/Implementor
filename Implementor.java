import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Oleg Fadeev
 * <p>
 * Class implementing {@link Impler}.
 * Provides public methods for given interface
 * </p>
 */
public class Implementor implements JarImpler {

    /**
     * Tab for generated code.
     */
    private final String TAB = "   ";

    /**
     * Constructs new instance of implementor.
     */
    public Implementor() {
        super();
    }

    /**
     * @see info.kgeorgiy.java.advanced.implementor.JarImpler#implementJar(Class, Path)
     */
    @Override
    public void implementJar(Class<?> token, Path jarFile) throws ImplerException {
        Path parentPath = jarFile.toAbsolutePath().normalize().getParent();
        if (parentPath != null) {
            try {
                Files.createDirectories(parentPath);
            } catch (IOException e) {
                throw new ImplerException("Could not create path");
            }
        }
        Path tempPath;
        try {
            tempPath = Files.createTempDirectory(jarFile.toAbsolutePath().getParent(), "temp");
        } catch (IOException e) {
            throw new ImplerException("Could not create temp path");
        }
        try {
            implement(token, tempPath);
            compile(token, tempPath);
            try (JarOutputStream jarWriter = new JarOutputStream(Files.newOutputStream(jarFile))) {
                jarWriter.putNextEntry(new JarEntry(
                        createFileName(token, "/", ".class")));
                Files.copy(tempPath.resolve(createFileName(token, File.separator, ".class")), jarWriter);
            } catch (IOException e) {
                throw new ImplerException("Could not create jar file");
            }
        } finally {
            try {
                assert tempPath != null;
                Files.walkFileTree(tempPath, CLEANER);
            } catch (IOException e) {
                System.err.printf("Could not clear temp path%n%s", e.getMessage());
            }
        }
    }


    /**
     * @see info.kgeorgiy.java.advanced.implementor.Impler#implement(Class, Path)
     */
    @Override
    public void implement(Class<?> token, Path root) throws ImplerException {
        if (!token.isInterface()) {
            throw new ImplerException("Interface expected");
        }
        if (Modifier.isPrivate(token.getModifiers())) {
            throw new ImplerException("Can not implement private interface");
        }
        try (BufferedWriter writer = Files.newBufferedWriter(getPath(root, token))) {
            writer.write(convertToUnicode(createHeader(token)));
            writer.write(convertToUnicode(implementMethods(token)));
            writer.write(convertToUnicode("}"));
        } catch (IOException e) {
            System.err.printf("Could not create file%n%s", e.getMessage());

        }
    }

    /**
     * Compiles implemented class for {@code token}.
     * @param token type token to compile for.
     * @param tempPath temporary directory path.
     * @throws ImplerException if an I/O error or URI Syntax error occurs.
     */
    private void compile(Class<?> token, Path tempPath) throws ImplerException {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            String classPath = String.format("%s%s%s", tempPath.getFileName(), File.pathSeparator,
                    Paths.get(token.getProtectionDomain().getCodeSource().getLocation().toURI()));
            int exitCode = compiler.run(null, null, null, "-cp", classPath,
                    tempPath.resolve(getPath(tempPath, token)).toString());
            if (exitCode != 0) {
                throw new ImplerException("Could not compile class");
            }
        } catch (URISyntaxException e) {
            throw new ImplerException("URISyntaxError");
        } catch (IOException e) {
            throw new ImplerException("Error");
        }
    }

    /**
     * Returns the path of file for {@code token} in {@code path}.
     * If parent directories of {@code path} do not exist, creates them.
     * @param path path to look for file in.
     * @param token type token to look for file of.
     * @return {@link Path} path of file.
     * @throws IOException if file cannot be found.
     */
    private Path getPath(Path path, Class<?> token) throws IOException {
        path = path.resolve(createFileName(token, File.separator, ".java"));
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        return path;
    }

    /**
     * Creates file name for {@code token}.
     * @param token type token to create name for.
     * @param separator separator between directories.
     * @param fileExtension file extension of created file.
     * @return {@link String} the name of the file.
     */
    private String createFileName(Class<?> token, String separator, String fileExtension) {
        return String.format("%s.%s", token.getPackageName(), createImplName(token)).replace(".", separator)
                .concat(fileExtension);
    }

    /**
     * Implements methods of {@code token}.
     * @param token type token to implement the methods of.
     * @return {@link String} the implementation of {@link Method} methods.
     */
    private String implementMethods(Class<?> token) {
        StringBuilder builder = new StringBuilder();
        for (Method method : getMethods(token)) {
            builder.append(TAB);
            builder.append(createMethodBody(method));

        }
        return builder.toString();
    }

    /**
     * Returns list abstract methods of {@code token}.
     * @param token type token to get methods of.
     * @return {@link List} list of {@link Method} abstract methods.
     */
    private List<Method> getMethods(Class<?> token) {
        return Stream.of(token.getMethods())/*.filter(method -> method.getAnnotation(Deprecated.class) == null)*/
                .filter(method -> Modifier.isAbstract(method.getModifiers())).collect(Collectors.toList());
    }

    /**
     * Creates the body of {@code method}.
     * @param method method to create the body of.
     * @return {@link String} the body of method.
     */
    private String createMethodBody(Method method) {
        return String.format("public %s %s (%s) {%n%s return %s;%n}%n", method.getReturnType().getCanonicalName(),
                method.getName(), getParameters(method.getParameters()), TAB, getDefaultValue(method.getReturnType()));
    }

    /**
     * Gets parameters type and name of {@code parameters}.
     * @param parameters parameters to get type and name of.
     * @return {@link String} names and types of parameters.
     */
    private String getParameters(Parameter[] parameters) {
        return Arrays.stream(parameters).
                map(param -> String.format("%s %s", param.getType().getCanonicalName(), param.getName())).
                collect(Collectors.joining(", "));
    }

    /**
     * Gets the default value of {@code returnType}.
     * @param returnType objects to get default value for.
     * @return default value converted to {@link String}.
     */
    private String getDefaultValue(Class<?> returnType) {
        if (returnType.isPrimitive()) {
            if (boolean.class.equals(returnType)) {
                return "false";
            } else if (void.class.equals(returnType)) {
                return "";
            } else {
                return "0";
            }
        } else {
            return "null";
        }
    }

    /**
     * Creates the header for implementation of {@code token}.
     * @param token type token to create the header for.
     * @return {@link String} header of implementation.
     */
    private String createHeader(Class<?> token) {
        return createPackage(token) + String.format("public class %s implements %s {%n",
                createImplName(token), token.getCanonicalName());
    }

    /**
     * Creates name for implementation of {@code token}.
     * @param token type token to create the name for.
     * @return {@link String} name of implementation.
     */
    private String createImplName(Class<?> token) {
        return token.getSimpleName().concat("Impl");
    }

    /**
     * Creates the package for implementation of {@code token}.
     * @param token type token to create package for.
     * @return {@link String} the package of implementation.
     */
    private String createPackage(Class<?> token) {
        String packageName = token.getPackageName();
        return packageName.equals("") ? "" :
                String.format("package %s;%n", packageName);
    }

    /**
     * Encodes {@code str} to Unicode.
     * @param str a {@link String} to be encoded.
     * @return a {@link String} encoded to Unicode.
     */
    private String convertToUnicode(String str) {
        StringBuilder stringBuilder = new StringBuilder();
        for (char ch : str.toCharArray()) {
            stringBuilder.append(ch < 128 ? String.valueOf(ch) : String.format("\\u%04x", (int) ch));
        }
        return stringBuilder.toString();
    }

    /**
     * Cleaner for deleting files.
     */
    private final SimpleFileVisitor<Path> CLEANER = new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }
    };
}