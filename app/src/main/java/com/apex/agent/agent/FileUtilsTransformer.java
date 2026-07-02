package com.apex.agent.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

public class FileUtilsTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // Check if this is the FileUtils class we're looking for
        if (className != null && className.contains("FileUtils")) {
            System.out.println("FileUtilsTransformer: Found class " + className);
            
            try {
                ClassPool pool = ClassPool.getDefault();
                CtClass clazz = pool.makeClass(new java.io.ByteArrayInputStream(classfileBuffer));
                
                // Find the getPath method
                CtMethod getPathMethod = clazz.getDeclaredMethod("getPath");
                
                if (getPathMethod != null) {
                    System.out.println("FileUtilsTransformer: Found getPath method, adding check for colons");
                    
                    // Replace the method body with a version that returns null for paths with colons
                    getPathMethod.setBody("{ " +
                        "String path = $1; " +
                        "if (path != null && path.contains(\":\")) { " +
                        "    System.out.println(\"FileUtilsTransformer: Skipping path with colon: \" + path); " +
                        "    return null; " +
                        "} " +
                        "return new java.io.File(path).toPath().toString(); " +
                    "}");
                    
                    return clazz.toBytecode();
                }
            } catch (Exception e) {
                System.out.println("FileUtilsTransformer: Error transforming class: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return classfileBuffer;
    }
}
