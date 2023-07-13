package org.evosuite.kex;

import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionObserver;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vorpal.research.kex.config.FileConfig;
import org.vorpal.research.kex.config.GlobalConfig;
import org.vorpal.research.kex.config.GlobalConfigKt;
import org.vorpal.research.kex.config.RuntimeConfig;
import org.vorpal.research.kex.launcher.ConcolicLauncher;
import org.vorpal.research.kex.util.RtKt;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class KexService {

    private static final Logger logger = LoggerFactory.getLogger(KexService.class);

    private static KexService instance = null;

    private final ConcolicLauncher launcher;

    private final KexClassLoader loader;

    private KexService(List<String> classPaths, String targetName, URL[] instrumented) {
        launcher = new ConcolicLauncher(classPaths, targetName);
        loader = new KexClassLoader(instrumented);
    }

    public static KexService getInstance() {
        if (instance == null) {
            GlobalConfig kexConfig = GlobalConfigKt.getKexConfig();
            kexConfig.initialize(RuntimeConfig.INSTANCE, new FileConfig("kex.ini"));
            URL instrumentedDir;
            try {
                instrumentedDir = RtKt.getInstrumentedCodeDirectory(kexConfig).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }

            String[] classPaths = ClassPathHandler.getInstance().getClassPathElementsForTargetProject();
            instance = new KexService(Arrays.asList(classPaths), Properties.TARGET_CLASS, new URL[]{instrumentedDir});
        }
        return instance;
    }

    public void execute(DefaultTestCase defaultTestCase) {
        // Kex preparation
        KexObserver kexObserver = new KexObserver(launcher.getContext());

        // Evosuite preparation
        defaultTestCase.changeClassLoader(loader);

        // Execution preparation
        Set<ExecutionObserver> originalExecutionObservers = TestCaseExecutor.getInstance().getExecutionObservers();
        TestCaseExecutor.getInstance().newObservers();
        TestCaseExecutor.getInstance().addObserver(kexObserver);

        // Execution
        ExecutionResult result = null;
        try {
            result = TestCaseExecutor.getInstance().execute(defaultTestCase, Properties.CONCOLIC_TIMEOUT);
        } catch (Exception e) {
            logger.error("Exception during kex execution: ", e);
        } finally {
            TestCaseExecutor.getInstance().setExecutionObservers(originalExecutionObservers);
        }
    }

}
