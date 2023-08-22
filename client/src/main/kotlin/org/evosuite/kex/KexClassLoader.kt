package org.evosuite.kex

import org.evosuite.runtime.instrumentation.RuntimeInstrumentation
import java.net.URL
import java.net.URLClassLoader

class KexClassLoader(urls: Array<URL>) : URLClassLoader(urls, null) {
    private val loader = KexClassLoader::class.java.classLoader

    override fun loadClass(s: String): Class<*> {
        if (!RuntimeInstrumentation.checkIfCanInstrument(s)) {
            return loader.loadClass(s)
        }
        return super.loadClass(s)
    }

    override fun findClass(s: String): Class<*> =
        try {
            super.findClass(s)
        } catch (e: ClassNotFoundException) {
            loader.loadClass(s)
        }
}