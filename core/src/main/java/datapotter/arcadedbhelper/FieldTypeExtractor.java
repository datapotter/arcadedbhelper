package datapotter.arcadedbhelper;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Type;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class to extract field types from compiled classes using ASM bytecode analysis.
 * This allows schema initialization without reflection, which is more reliable and TeaVM-compatible.
 */
public class FieldTypeExtractor {

    /**
     * Extract the type of a field from a class using bytecode analysis.
     *
     * @param clazz the class containing the field
     * @param fieldName the name of the field
     * @return the Class object representing the field type
     */
    public static Class<?> extractFieldType(Class<?> clazz, String fieldName) {
        var classAsPath = clazz.getName().replace('.', '/') + ".class";
        var type = new AtomicReference<Class<?>>();

        try (var in = clazz.getClassLoader().getResourceAsStream(classAsPath)) {
            if (in == null) {
                throw new IllegalStateException("Could not load class file: " + classAsPath);
            }

            var classReader = new ClassReader(in);
            classReader.accept(new ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    if (name.equals(fieldName)) {
                        Type fieldType = Type.getType(descriptor);
                        if (fieldType == null) {
                            throw new IllegalStateException("Could not determine type for " + fieldName);
                        }
                        type.set(getClassForType(fieldType));
                    }
                    return super.visitField(access, name, descriptor, signature, value);
                }

                private Class<?> getClassForType(Type type) {
                    if (type.getSort() == Type.ARRAY) {
                        return getClassForType(type.getElementType()).arrayType();
                    } else if (type.getSort() == Type.OBJECT) {
                        try {
                            return Class.forName(type.getClassName());
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        }
                    } else {
                        // Primitive types
                        switch(type.getSort()) {
                            case Type.BOOLEAN:
                                return boolean.class;
                            case Type.BYTE:
                                return byte.class;
                            case Type.CHAR:
                                return char.class;
                            case Type.DOUBLE:
                                return double.class;
                            case Type.FLOAT:
                                return float.class;
                            case Type.INT:
                                return int.class;
                            case Type.LONG:
                                return long.class;
                            case Type.SHORT:
                                return short.class;
                            default:
                                // Should not reach here
                                return null;
                        }
                    }
                    return null;
                }
            }, 0);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return type.get();
    }
}
