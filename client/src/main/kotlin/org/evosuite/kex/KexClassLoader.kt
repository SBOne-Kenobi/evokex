package org.evosuite.kex

import org.evosuite.runtime.instrumentation.RuntimeInstrumentation
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes


class KexClassLoader(private val paths: Array<Path>) : ClassLoader(null) {
    private val loader = KexClassLoader::class.java.classLoader
    private val classes = HashMap<String, Class<*>>()

    override fun loadClass(s: String): Class<*> {
        if (!RuntimeInstrumentation.checkIfCanInstrument(s)) {
            return loader.loadClass(s)
        }
        return super.loadClass(s)
    }

    override fun findClass(s: String): Class<*> {
        classes[s]?.let {
            return it
        }

        val targetPath = s.replace('.', '/') + ".class"

        for (path in paths) {
            try {
                val resource = path.resolve(targetPath)
                if (!resource.exists() || !resource.isRegularFile()) {
                    continue
                }
                val code = resource.readBytes()
                val result = defineClass(s, code, 0, code.size)
                classes[s] = result
                return result
            } catch (_: Throwable) {
            }
        }

        loader.getResourceAsStream(targetPath).use { inputStream ->
            if (inputStream == null) {
                throw ClassNotFoundException("Class $s is not found")
            }
            val code = inputStream.readBytes()
            val result = defineClass(s, code, 0, code.size)
            classes[s] = result
            return result
        }
    }

}