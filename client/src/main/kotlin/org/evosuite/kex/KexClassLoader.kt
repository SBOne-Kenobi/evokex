package org.evosuite.kex

import java.net.URL
import java.net.URLClassLoader

class KexClassLoader(urls: Array<URL>) : URLClassLoader(urls, null) {
    private val loader = KexClassLoader::class.java.classLoader

    override fun findClass(s: String): Class<*> =
        try {
            super.findClass(s)
        } catch (e: ClassNotFoundException) {
            loader.loadClass(s)
        }
}