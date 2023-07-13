package org.evosuite.kex;

import java.net.URL;
import java.net.URLClassLoader;

public class KexClassLoader extends URLClassLoader {
    private final ClassLoader loader;

    public KexClassLoader(URL[] urls) {
        super(urls, null);

        this.loader = KexClassLoader.class.getClassLoader();
    }

    @Override
    protected Class<?> findClass(String s) throws ClassNotFoundException {
        try {
            return super.findClass(s);
        } catch (ClassNotFoundException e) {
            return loader.loadClass(s);
        }
    }

}
