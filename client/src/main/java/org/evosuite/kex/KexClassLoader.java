package org.evosuite.kex;

public class KexClassLoader extends ClassLoader {

    private final ClassLoader instrumentedLoader;

    public KexClassLoader(ClassLoader instrumentedLoader) {
        super(KexClassLoader.class.getClassLoader());

        this.instrumentedLoader = instrumentedLoader;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        Class<?> result = findLoadedClass(name);
        if (result != null) {
            return result;
        }
        try {
            result = this.instrumentedLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
            result = getParent().loadClass(name);
        }
        return result;
    }
}
